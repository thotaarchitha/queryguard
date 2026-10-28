# QueryGuard

![CI](https://github.com/thotaarchitha/queryguard/actions/workflows/ci.yml/badge.svg)

**A PostgreSQL proxy that reads every query before your database does.**

It sits on port 6432, speaks the Postgres wire protocol, and decides per query: let it through,
rewrite it, or refuse it with a real Postgres error. Later it also masks columns like `email` and
`card_last4` depending on who's asking, and streams every decision into ClickHouse so you can
see who's running what.

No client changes. psql, JDBC, psycopg and DBeaver all just connect to a different port.

```
$ psql -h localhost -p 6432 -U alice -d shop
shop=> DELETE FROM orders;
ERROR:  QueryGuard R1: DELETE without a WHERE clause would touch every row.
```

↑ That's the goal. **It doesn't do that yet.** Right now it's a fast, boring TCP pipe (Phase 1 of 9).
I'm building it in public, one phase at a time; the log is at the bottom.

---

## Why build this

Most database incidents I've seen up close weren't clever. Someone forgot a `WHERE`. Someone ran
`SELECT *` on the biggest table in the warehouse at 9am. A dashboard query quietly went from 200 ms
to 40 s and nobody noticed until the database did.

Database permissions answer *"can this user touch this table?"* They don't answer *"is this query
a bad idea?"* That needs something that can read the SQL, ask Postgres what it'll cost, and say no
before the database pays for it. That's the gap QueryGuard sits in.

It is **not** a pooler. PgBouncer is great at pooling and deliberately doesn't look inside your
queries. QueryGuard does the opposite.

## Run it

You need Docker. That's it, no local Java.

```bash
git clone https://github.com/thotaarchitha/queryguard.git
cd queryguard
docker compose up -d --build --wait
```

The first start seeds a fake shop: 1M customers, 5M orders, 10K products, about 30 s on my laptop.
Everything in it is generated with `generate_series`. Emails are `@example.com` and phones are in the
555-01xx fiction range, so none of it can belong to a real person.

```bash
psql -h localhost -p 6432 -U postgres -d shop     # password: dev
```

## What's actually built

**Phase 1: pass-through proxy.** Java 21 + Netty.

- Each client connection gets its own backend connection, created on the **same event loop**, so one
  thread owns both ends. No locks, no handoffs between threads.
- **Backpressure both ways.** Auto-read is off, and a side only reads more when the other side can
  take it. A slow client pulling a 5M-row result doesn't turn into 5M rows sitting in proxy memory.
- If Postgres dies mid-query the client gets disconnected right away instead of hanging. The backend
  connect gives up after 3 s.
- SCRAM auth passes straight through. I don't touch the handshake (yet).

Four tests run against an echo server pretending to be Postgres: round trip, an 8 MB payload
(big enough to actually hit the backpressure path), backend down at connect, and backend dying
mid-session.

## Numbers, with caveats

pgbench, select-only, 20 clients, median of 3 runs:

|                  | TPS    | avg latency |
|------------------|--------|-------------|
| straight to PG   | 34,568 | 0.58 ms     |
| through QueryGuard | 21,591 | 0.93 ms   |

So about **+0.35 ms per query**. The −37% TPS looks worse than it is, and I don't fully trust it yet:

- pgbench, Postgres and the proxy were all fighting over the same 12 laptop threads in Docker Desktop.
- `-S` queries take half a millisecond, so any extra network hop is a big *percentage*. On a 50 ms
  analytics query the same 0.35 ms is under 1%.
- One of the direct runs came in at 14.6K TPS for no reason I could find. Laptop noise.

I'll rerun this on a dedicated Linux box before these numbers go anywhere serious.
Raw runs: [`bench/pgbench/results/phase1.md`](bench/pgbench/results/phase1.md).

## How a query will get through (the plan)

```
client ──► decode wire msg ──► parse SQL ──► EXPLAIN (cached) ──► rules ──┬─► forward
                                                                          ├─► rewrite + forward
                                                                          └─► ErrorResponse, never reaches PG
results ◄── mask DataRow by table OID + column # ◄── Postgres
                    │
                    └─► every decision ─► Kafka ─► ClickHouse ─► Grafana
```

A few of these choices are deliberate and I'll defend them in [`docs/design-decisions.md`](docs/design-decisions.md):

- **Mask by table OID + column number, not by column name.** `SELECT email AS e` shouldn't
  get around masking just by renaming the column.
- **Parsing and EXPLAIN go off the event loop** onto virtual threads. A slow EXPLAIN shouldn't stall
  every other connection sharing that thread.
- **Logging never blocks a query.** If Kafka is down, events get dropped and counted. Queries keep working.
- **Only normalized SQL gets logged** (`where id = ?`), never the literal values.

The eight starting rules are already written down in
[`infra/rules/default-rules.yml`](infra/rules/default-rules.yml), even though nothing reads that file yet.

## Build log

| | phase | status |
|---|---|---|
| 1 | TCP pass-through, backpressure, pgbench baseline | **done** |
| 2 | Wire protocol: decode startup / Q / P / B / E, sessions, send our own errors | next |
| 3 | Rule engine: JSqlParser, fingerprints, EXPLAIN + Caffeine cache | |
| 4 | Column masking, circuit breaker, metrics | |
| 5 | Spring Boot admin API, live reload over LISTEN/NOTIFY | |
| 6 | Kafka → ClickHouse (MergeTree + hourly rollups, dbt) → Grafana | |
| 7 | Testcontainers suite, JMH, k6, honest benchmark write-up | |
| 8 | Helm on kind, CI image publishing | |

Known gaps I'm choosing to live with for now: no TLS termination (from Phase 2 the proxy will answer
`N` to SSLRequest), no pooling, single backend. Things that broke along the way are in
[`docs/bug-journal.md`](docs/bug-journal.md).

## Hacking on it

```bash
./gradlew build                     # with JDK 21
# or, no Java installed:
docker run --rm -v "$PWD:/src" -w /src gradle:8.10.2-jdk21 ./gradlew build
```

Config is four env vars: `QG_LISTEN_PORT` (6432), `QG_PG_HOST` (localhost), `QG_PG_PORT` (5432),
`QG_CONNECT_TIMEOUT_MS` (3000).

---

Built by **Architha Reddy Thota**. I'm a master's student, and before that I spent three years as a
software engineer on backend systems, with a fair amount of data work along the way. This project is
where those two halves meet. MIT licensed.
