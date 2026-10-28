# Design decisions

The calls I made, what each one cost, and the questions I'd expect in an interview, answered the way
I'd answer them. Bugs that changed a decision are in [bug-journal.md](bug-journal.md); when a
decision changed, the old reasoning stays here with the reason it changed.

---

## 1. What happens, byte by byte, when psql connects

1. psql opens TCP to 6432. Netty accepts the connection, and the proxy immediately opens a second
   connection to Postgres on the **same event loop**. Reads from the client stay off until that
   connection is up.
2. psql sends **SSLRequest**: 8 bytes, length `8` + code `80877103`, with no type byte. The proxy answers
   a single `N` itself and never forwards it. (No TLS termination yet; see limitations.)
3. psql sends the **StartupMessage**: length, protocol `196608` (3.0), then `user\0alice\0database\0shop\0...\0`.
   The decoder switches from "untyped" to "typed" mode here. The proxy records user, database and
   application name, looks up the role (alice → analyst), starts prefetching masking metadata and table
   statistics for `shop`, and forwards the bytes untouched.
4. **Auth passes straight through.** Postgres sends `R` (AuthenticationSASL), psql answers with `p`
   messages, and so on. Since I never touch these bytes, SCRAM-SHA-256 works without me implementing it.
   The catch is that the proxy can't authenticate anyone itself, which is why pooling is hard (question 7).
5. Postgres sends `S` ParameterStatus ×N, `K` BackendKeyData, and `Z` ReadyForQuery. From here on,
   every message is `type(1) + length(4, counts itself) + body`.
6. alice types a query, and psql sends `Q` with the SQL. The proxy pauses that client, decides on a virtual
   thread, and comes back to the event loop with ALLOW / REWRITE / BLOCK.
7. Results come back as `T` RowDescription (column names, **plus the table OID and column number each
   column came from**), then `D` DataRow ×N, `C` CommandComplete, and `Z`. `T` and `D` are where masking happens.

## 2. Why Netty and not a thread per connection? What if you block the event loop?

Proxies mostly hold idle connections, and thread-per-connection pins a thread to each one. Java 21
virtual threads make that cheap again, and I seriously considered it. I went with Netty for two reasons:
its `ByteToMessageDecoder` is the framing layer I needed (question 3), and backpressure is explicit:
`isWritable()` plus manual `read()` calls. With blocking streams backpressure is implicit, and implicit
is hard to reason about while a 5M-row result is in flight.

One event loop thread serves hundreds of connections. Block it, and every one of them stalls. So
nothing slow runs on it: parsing, rules and EXPLAIN run on a virtual-thread executor. The client's
reads stay paused until the verdict comes back, and the verdict is applied back on that connection's
own loop. The client and backend channels of one connection share a loop, so there are no locks and
no thread handoffs between them.

## 3. A message split across two TCP reads, or two in one?

`ByteToMessageDecoder` keeps unread bytes in a cumulation buffer. My decoder returns early if fewer
than 5 bytes (type + length) or fewer than `length + 1` bytes are available, and Netty calls it again
when more arrive. When one read contains several messages, it's called in a loop. The test
`messageSplitAcrossReadsAtEveryPossibleByte` cuts a startup + query stream at **every** byte offset.
Each message keeps its original bytes as a retained slice, so anything I don't change is forwarded
byte for byte, never re-encoded.

## 4. Slow EXPLAIN or Postgres down: fail-open or fail-closed?

Neither, globally. It depends on what's being guarded, and I got this wrong twice before landing here:

| Situation | Policy | Why |
|---|---|---|
| Parser can't read a statement | reads pass, writes/DDL blocked (`allow_reads`) | psql's own `\d` queries use syntax JSqlParser doesn't know; blocking them makes the proxy unusable. An unparseable `UPDATE` is a different story. |
| EXPLAIN fails or times out (200 ms) | **estimate** from `pg_class` stats (upper bound) | `allow` let expensive queries through exactly when the DB was overloaded, which made it worse (bug journal). `block` would turn a slow EXPLAIN into an outage. |
| Whole decision > 1 s | same as parse policy, **except** masked roles always block | masking never fails open (bug journal: cold-start leak) |
| Masking metadata unavailable | block queries from masked roles | an unmasked result is the one outcome I can't undo |
| Postgres down | circuit breaker: 5 failed connects → fail fast for 10 s | new clients get "unreachable" immediately instead of each waiting out the 3 s connect timeout |
| Kafka down | drop events, count them | question 9 |

All of these are settings, not code.

## 5. How could someone get around masking, and how did I stop it?

Masking matches columns by **identity, not name**. RowDescription tells me each result column's source
table OID and column number, which I map to `customers.email` with one catalog query per database.
So `SELECT email AS e` is still masked. Renaming does nothing.

The holes are anything that makes Postgres report a column as *computed* (OID 0): `upper(email)`,
`email || ''`, `email::text`, `row_to_json(c)`, a UNION, a subquery or CTE that re-selects it. And
anything that leaks without returning the value: `WHERE email LIKE 'a%'` (guess one letter at a time),
`ORDER BY email`, `count(DISTINCT email)`. The masking guard runs before the query and enforces a blunt
rule for roles that have the column masked: **a masked column may only appear as a bare item in the
top-level select list.** Plus COPY on masked tables is blocked, and so is a whole-row reference (`SELECT c FROM customers c`).

Other pieces: binary-format result columns become NULL (rewriting them as text would break the client's
decoder), and masks are remembered per prepared statement because pgjdbc stops sending Describe after
5 executions. Without that, the 6th execution would come back unmasked. There's a test for it.

Known gap: a SECURITY DEFINER function or a view owned by someone else could return masked data under
another table's OID. A policy on the view fixes that case by case; real column privileges in Postgres
are the better tool if you control the schema.

## 6. Rules change without a restart. What about queries already running?

The admin API writes the change, bumps `config_version`, writes an audit row, and calls `pg_notify`, all
in **one transaction**. Postgres holds notifications until COMMIT, so a proxy can never hear about a change
it can't see yet, and a rolled-back change never notifies anyone. Each proxy keeps one connection on
`LISTEN qg_rules_changed`, reloads everything in one REPEATABLE READ snapshot, builds a new immutable
policy, and swaps an `AtomicReference`. A query grabs the reference once, at the start of its decision, so
it finishes on the policy it started with. Measured: about 70 ms from commit to live (RESULTS.md).
Missed notifications (proxy disconnected) are handled by a full reload on reconnect.

## 7. Scaling to 50K connections: what breaks first?

On the proxy side, connections are cheap: a few KB each, idle ones cost nothing on an event loop.
What breaks first is **Postgres**. The proxy maps client connections 1:1 to server connections, and
Postgres runs one process per connection; 50K backends is out of the question. The fix is pooling, and
pooling is hard here because auth passes through: the proxy never learns the password, so it can't
open pooled connections on a user's behalf. It would need to terminate auth itself (SCRAM with stored
verifiers, like PgBouncer's `auth_query`). After that: session state (SET, prepared statements, temp tables)
makes transaction pooling leak between clients. I'd put PgBouncer behind QueryGuard before rebuilding it.

Next limits after that: the EXPLAIN pool (4 connections per database per proxy; the cache hit rate
matters a lot), and the event-queue to Kafka (bounded, drops when full).

## 8. Why ClickHouse and not Postgres for the logs? Why that ORDER BY?

The workload is append-only, millions of narrow rows, and queries are aggregations over time ranges.
That's columnar territory: ClickHouse reads only the columns a query touches, compresses
`sql_normalized` 10×+ with ZSTD, and the Kafka engine plus a materialized view does ingestion
without a separate consumer service. Postgres would need partitioning, BRIN indexes and a consumer, and
would still scan whole rows.

`ORDER BY (decision, role, user, ts)`: nearly every dashboard query filters on decision first
(blocked vs allowed), then role, then a user, over a time range. That key turns those into contiguous
range reads. Partitioning by day makes the 90-day TTL drop whole parts instead of rewriting them. The
rollups store aggregate **states** (`quantileState`, `uniqState`), so hours merge into days correctly.
A p95 of hourly p95s would be wrong; merging quantile states isn't.

## 9. How does the Kafka producer avoid slowing queries? What's lost if Kafka is down?

The event loop only does `queue.offer()` on a bounded queue (50K). A separate thread drains it into
the producer. `KafkaProducer.send()` can block for metadata or a full buffer, which is fine there and
would be terrible on the loop. `acks=1`, `linger.ms=5`, lz4, keyed by fingerprint. If Kafka is down
or slow, the queue fills, offers fail, and events are **dropped and counted**
(`qg_events_dropped_total`, on the health dashboard). Queries never wait.

What's lost: audit events for the outage window. That's a real trade. For compliance-grade audit I'd
add a local disk spool (or write to a Postgres table and use CDC), accepting the extra write. For
analytics, dropping is fine.

Events store **normalized SQL only** (`where id = ?`). The literal values never leave the proxy, which
is the whole point of a tool that masks columns.

## 10. What would make this production-grade?

TLS on both sides (terminate client TLS with Netty's SslHandler, verify the server), SCRAM
termination so pooling is possible, HA for the config DB (it's a single point of failure for *changes*;
proxies keep their last policy if it's down), Avro + Schema Registry instead of JSON, a disk spool for
events, per-rule fail modes instead of global ones, and a proper JSqlParser alternative.
For Postgres-only SQL I'd look at `libpg_query`, which is Postgres' own parser.

## 11. How is this different from PgBouncer or ProxySQL?

PgBouncer is a connection pooler and deliberately doesn't parse SQL. ProxySQL (MySQL) does query rules
and rewriting by regex/digest and has its own pooling, but no cost-based rules and no result masking.
QueryGuard's angle is **policy**: what a query will cost (EXPLAIN), what it's allowed to see (masking
by column identity), and a record of every decision. They stack: client → QueryGuard → PgBouncer → Postgres.

## 12. The hardest bug?

The cascading failure in [bug-journal.md](bug-journal.md): under load, EXPLAIN timed out, the cost rules
failed open, the expensive queries they exist to stop got through, and the database got slower, so more
EXPLAINs timed out. I found it with `pg_stat_activity` showing a 3-minute `SELECT * FROM orders` that
R5 should have stopped. The fix (a statistics-based upper bound plus backing off EXPLAIN) changed how I
think about fail-open: it's only safe if what it lets through can't make the failure worse.

---

## Smaller calls

- **Blocking by letting Postgres reject a placeholder.** A blocked Q or Parse is replaced with
  `QUERYGUARD_BLOCKED`, which fails to parse, and Postgres' ErrorResponse is swapped for mine on the way
  back. Postgres then does the protocol bookkeeping: it aborts an open transaction, skips the rest of an
  extended batch until Sync, and sends the correct ReadyForQuery status. I tried answering for
  Postgres first and the transaction-state edge cases were endless. Cost: one cheap round trip per
  blocked query, and a syntax error line in the Postgres log (which turns out to be a nice audit trail).
- **Parse once per query shape.** Rule facts depend only on the normalized text, so they're cached by it.
  The AST is re-parsed from the real text only for rewrites.
- **Rules in `common`.** The proxy and the admin API's dry run use the same engine, so `/rules/test`
  can't disagree with what the proxy does.
- **If-Match for rule edits.** Optimistic locking through HTTP's own ETag/If-Match. A stale edit is a 409,
  and an edit without If-Match is a 428.
- **Health on a separate port.** Database clients can't starve Kubernetes' probes.
- **Masking and role mappings are replaced as whole sets** (PUT the list). They're small, and per-item
  CRUD would be more API than the problem needs.
