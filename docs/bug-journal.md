# Bug journal

Things that broke, how I noticed, and what I learned. Mostly for me, partly for interviews.

---

### 2026-10-28: seed script dies at row ~271K

**Symptom.** `docker compose up` failed with the postgres container exiting with code 3. The logs showed
`ERROR: integer out of range` on the customers insert.

**Cause.** `generate_series(1, 1000000)` produces `int4`, so `g * 7919` (a cheap way to scatter
`card_last4` values) overflows once `g` passes 2³¹ / 7919 ≈ 271,181. Products only goes to 10K,
so that insert ran fine and hid the problem.

**Fix.** `generate_series(1::bigint, ...)` everywhere. Cheaper than casting inside each expression.

**Lesson.** Synthetic-data math needs the same overflow care as real code. Test seeds at full scale,
not at 1K rows.

---

### 2026-10-28: "healthy" before the database existed

**Symptom.** After fixing the seed, compose reported postgres healthy and started the proxy, but the
orders insert hadn't run yet. Connecting would have hit a half-loaded database.

**Cause.** The postgres image runs init scripts on a temporary server that only listens on the Unix
socket. `pg_isready` with no `-h` checks that socket, so it says "ready" during the seed.

**Fix.** `pg_isready -h 127.0.0.1`. TCP isn't open until the real server starts after init.

**Lesson.** A healthcheck should test the same path the clients use, not just "is the process up."

---

### 2026-10-28: `ORDER BY email` leaked a masked column

**Symptom.** The masking guard test `SELECT id FROM customers ORDER BY email` (as an analyst) passed
straight through. Sorting by a column you can't see tells you its order, and with a LIMIT and a few
queries you can recover values.

**Cause.** I find column references by riding on JSqlParser's `TablesNamesFinder`, which walks
every expression... except ORDER BY and GROUP BY, because there are never tables in there.

**Fix.** The inspector walks ORDER BY and GROUP BY itself after the normal traversal.

**Lesson.** When you borrow someone else's tree walker, you inherit their idea of what matters.
The test list of "ways to leak a masked column" is now the most valuable test file in the repo.

---

### 2026-10-28: cold start unmasked `upper(email)`

**Symptom.** In the integration tests, the very first query of a run, `SELECT upper(email) FROM customers`
as an analyst, returned `USER1@EXAMPLE.COM`. Every later run of the same test passed.

**Cause.** Two fail-open paths lining up. The first query on a cold proxy had to open the metadata
connection pool and warm up JSqlParser, which took longer than the 1 s decision budget. A timed-out
decision for a read was allowed through (the parse-error policy is `allow_reads`), and since masking
metadata hadn't loaded yet either, the result went out unmasked.

**Fix.** Three changes:
1. A timed-out decision for a role that has masked columns is always blocked. Masking never fails open.
2. Loading masking metadata gets its own budget (5 s) and happens *before* the 1 s decision clock starts.
   It's also prefetched when a session starts.
3. The analyzer is warmed up at startup with a few hundred parses (about 2 s on a cold JVM).

**Lesson.** "Fail-open for reads" sounded reasonable for cost rules and is plainly wrong for privacy.
Each guard needs its own failure policy.

---

### 2026-10-28: the cost rules stopped working exactly when they mattered

**Symptom.** With the traffic generator running at a few hundred queries per second, Postgres was at
500% CPU, and `pg_stat_activity` showed `SELECT * FROM orders` (5M rows) running for three minutes, plus
several analyst full-table joins. R3 and R5 exist to block exactly those.

**Cause.** Under load, EXPLAIN blew its 200 ms `statement_timeout`. The policy said `on_cost_error: allow`,
so every expensive query got through. They made the database slower, so more EXPLAINs timed out. A
positive feedback loop: the overloaded database made the guard weaker, and the weaker guard
overloaded the database further.

**Fix.** A third mode, `on_cost_error: estimate`, now the default. When EXPLAIN fails, the proxy uses
`pg_class.reltuples/relpages` (cached for 5 minutes) to compute what a full scan of every table in the
query would cost. That's an upper bound: it ignores WHERE clauses, so it overestimates selective
queries. That's the right direction to be wrong in during an outage. After a timeout the proxy also stops
sending EXPLAINs for 2 s and goes straight to the estimate, so it isn't piling planning work onto a
struggling server. The error message says when it's an estimate.

**Lesson.** Fail-open is only safe if what it lets through can't make the failure worse.
This is the one I'd tell in an interview.

---

### 2026-10-28: the proxy was parsing every query from scratch

**Symptom.** Same load test: proxy at 3+ cores for ~260 queries/s, decision p50 68 ms, p99 777 ms.

**Cause.** JSqlParser ran on every query. Most traffic is the same few dozen query shapes with different
literals, so nearly all of that work was repeated.

**Fix.** Everything the rules look at (statement type, tables, WHERE/LIMIT, columns, functions) depends
only on the normalized shape, so it's cached by normalized text (Caffeine, 20K shapes). The AST is
only parsed again, from the query's own text, when a REWRITE actually needs it. The JMH numbers in
`bench/RESULTS.md` show the difference.

---

### 2026-10-29: analyst queries didn't get faster with the cache

**Symptom.** JMH after the shape cache: point lookups went from 776 us to 5 us, but the analyst join
stayed at 2.4 ms.

**Cause.** Analyst queries without a LIMIT get one added by R4, and the rewrite re-parsed the full AST to
call `setLimit()` and re-serialize it. Every analyst query paid for a full JSqlParser run anyway.

**Fix.** Append ` LIMIT n` to the statement's own text when the cached facts say it has no LIMIT/FETCH
and no locking clause. 2.4 ms became 16 us, and the client's SQL is no longer reformatted.

**Lesson.** Benchmark per query type, not on average. The average looked great.

---

### 2026-10-29: the admin API got slower when I made it "faster"

The rule list endpoint parsed every rule's JSON on every request: 761 req/s and p95 571 ms at 200
users. Caching it by `config_version` was a clear win (1,936 req/s). Then I also turned on Spring's
virtual threads, expecting more, and got 562 req/s with p95 over a second. With platform threads,
Tomcat's pool paced requests; with virtual threads, all 200 hit the 20-connection DB pool and the shared CPU
at once. Reverted. Also: my first k6 script had zero think time, so its "p95 latency" was just
200 users divided by throughput. Realistic pacing (0.5-1.5 s per user) gives p95 10-16 ms.

---

### 2026-10-29: pgbench's VACUUM was refused as an "unparseable write"

pgbench vacuums its tables before every run. JSqlParser can't read `VACUUM`, my keyword table called it
ALTER, and the parse-error policy refuses unparsed writes, so the dashboards showed QG-PARSE blocks
for bob. VACUUM/ANALYZE/REINDEX/CLUSTER/CHECKPOINT now have their own MAINTENANCE kind, which is fine
to let through unparsed. Found by reading the "what got blocked" panel, not by a test.

---

### 2026-10-29: duplicate query events

dbt's `unique(event_id)` test failed on the first run over ~470K events: 3,275 duplicates, all
byte-identical, arriving in bursts. ClickHouse's Kafka consumer redelivers batches after rebalances, which
happened a lot while the laptop was overloaded. At-least-once is the deal with the Kafka engine,
so the raw table became a ReplacingMergeTree on `event_id` and dbt reads it with FINAL. The streaming
rollups still count at insert time; that's documented as approximate.

---

### 2026-10-29: the admin API crash-looped on Kubernetes, and only there

**Symptom.** First real `helm install` on kind: two proxy pods ready, the admin API in CrashLoopBackOff.
The log said it couldn't convert `"tcp://10.96.203.231:8080"` to an Integer.

**Cause.** Kubernetes injects "service link" env vars for every Service in the namespace. A Service named
`qg-admin-api` gives every pod `QG_ADMIN_API_PORT=tcp://10.96.203.231:8080`, and the admin API read its
HTTP port from... `QG_ADMIN_API_PORT`. Docker Compose never sets those, so it had only ever worked by accident.

**Fix.** `enableServiceLinks: false` on both deployments (nothing uses them), and the setting is now
`QG_HTTP_PORT`, so it can't collide with a service name again.

**Lesson.** `helm lint` and `helm template` passing says nothing about whether the thing runs. The chart
had been "done" for a day before it was ever installed.

---

### 2026-10-28: smaller ones

- **Testcontainers vs Docker Engine 29.** Every container test failed with an empty `Status 400`.
  Docker 29 dropped the old API version that Testcontainers 1.20's client asks for. 1.21.4 works. The
  admin API was still failing afterwards because Spring Boot's dependency management quietly pinned
  Testcontainers back to its own older version, so that needed `extra["testcontainers.version"]`.
- **`integrationTest` ran zero tests and reported success.** I'd put `excludeTags("integration")` on
  `withType<Test>`, which includes the integration task itself. I only caught it because the
  result XML was missing. A green build with no tests is the worst kind.
- **Parallel `docker compose build` broke Gradle.** Both images used the same BuildKit cache mount
  for `~/.gradle` and fought over its lock files. `sharing=locked` makes them take turns.
- **An empty answer looked like "not loaded".** Databases with no masked tables resolved to an empty
  map, and `isEmpty()` was also how I checked "not loaded yet", so every query took an extra async hop
  to "load" again. Now there's a separate loaded flag.
- **ClickHouse "unhealthy" while working fine.** The healthcheck ran an anonymous query, and with a
  password set that's refused. `/ping` needs no auth.
