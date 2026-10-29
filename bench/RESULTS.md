# Benchmark results

Everything here was measured on one laptop, with every component running at once. Read the
numbers with that in mind; I've noted where it clearly distorts them.

**Machine.** Intel Core i7-1355U (10 cores / 12 threads, laptop power limits), 16 GB RAM, Windows 11.
Docker Desktop (WSL2) with 8 GB and all 12 threads. Postgres 16, Kafka 3.7 (KRaft), ClickHouse 24.8,
Prometheus, Grafana, the proxy and the admin API all in containers on one Docker network, along with
the load generator. Proxy container capped at 1 GB. Measured 2026-10-28/29.

**The short version**

| What | Result |
|---|---|
| Decision step (parse + rules), known query shape | **5 µs p50**, 38 µs p99 (JMH) |
| Same, parsing every query from scratch | 797 µs p50 (≈160× slower) |
| pgbench select-only, prepared statements, 32 clients | 25,067 TPS direct → 9,401 through QueryGuard |
| pgbench select-only, simple protocol, 32 clients | 35,798 TPS direct → 5,806 (events on) / 10,316 (events off) |
| Rule change via API → live in the proxy | **24 ms median** (21-26 typical, 170 ms worst, n=10) |
| Admin API, 200 users, realistic think time | list p95 **10 ms**, dry-run p95 **16 ms**, 0 errors |
| Admin API max throughput | **1,936 req/s**, 0 errors in 290K requests |
| Query events stored in ClickHouse | **3.6M+** (1,004,924 from one traffic run), 0 dropped by the proxy |
| Kafka → ClickHouse catch-up after a 1-minute ClickHouse outage | **130,818 events/s** peak, nothing lost |
| Memory per proxied connection | ~240 KB of heap |
| Expensive queries leaking through under overload | 3-minute `SELECT *` before the fix, **0** after |

---

## 1. Decision step (JMH)

`proxy/src/jmh/.../DecisionBenchmark.java`: lexer + parser + full shipped rule set, EXPLAIN stubbed
to a warm cache hit. Literals are randomized on every call, so the shape cache only helps the way it
would with real traffic. One thread, 3×3 s warmup, 5×4 s measurement.

| Query | Shape cache | p50 | p99 | p99.9 |
|---|---|---|---|---|
| point lookup (app) | off | 797 µs | 3,899 µs | 6,305 µs |
| point lookup (app) | **on** | **4.9 µs** | 37.7 µs | 277 µs |
| analyst join, gets `LIMIT 1000` added | off | 2,998 µs | 16,705 µs | 43,486 µs |
| analyst join, gets `LIMIT 1000` added | **on** | **16.5 µs** | 162 µs | 954 µs |
| UPDATE ... WHERE (app) | off | 978 µs | 4,358 µs | 11,628 µs |
| UPDATE ... WHERE (app) | **on** | **5.5 µs** | 50.9 µs | 642 µs |

Two changes produced this. Rule facts are cached by normalized query shape, so JSqlParser runs
once per shape. And the LIMIT rewrite appends to the query's own text instead of re-parsing and
re-serializing the AST. Before the second change, the analyst row was 2,359 µs even with the cache on.

Raw: `bench/jmh/results.json`. Summary table: `python bench/jmh/summarize.py bench/jmh/results.json`.

## 2. Proxy overhead (pgbench)

`bench/pgbench/run.sh`. `pgbench -S` (select by primary key), scale 10, 32 clients, 4 threads,
run as `bob` (role app), so the full rule set and masking checks are evaluated on every query.
Nothing pgbench sends gets blocked.

**Prepared protocol** (Parse once, then Bind/Execute; decided once per statement). Median of 3 runs × 30 s:

| | TPS | avg latency | p99 |
|---|---|---|---|
| Postgres direct | 25,067 | 1.28 ms | 8.9 ms |
| through QueryGuard | 9,401 | 3.40 ms | 19.7 ms |

**Simple protocol** (every query is a fresh Q message that gets decided). Runs interleaved so all
three see the same machine state. Median of 3 × 20 s:

| | TPS | avg latency |
|---|---|---|
| Postgres direct | 35,798 | 0.89 ms |
| QueryGuard, query events **off** | 10,316 | 3.10 ms |
| QueryGuard, query events **on** | 5,806 | 5.51 ms |
| QueryGuard, events on, ClickHouse **paused** | 8,665 | 3.69 ms |

**Where the cost goes.** Java Flight Recorder during a simple-mode run (1,676 samples):

| Proxy CPU spent on | Share |
|---|---|
| Netty I/O and protocol handling (read, frame, forward, write) | ~47% |
| The decision (lexer, cached facts, rules, masking guard) | ~21% |
| Publishing query events (Jackson JSON ~8%, Kafka producer ~3%) | ~12% |
| Event creation, metrics, scheduling | ~6% |

**What limits it, honestly:**
- **This is CPU on one shared laptop.** At saturation, pgbench, Postgres and the proxy all fight for the
  same 12 threads. The proxy used ~4 cores at ~6K queries/s, about 0.7 ms of CPU per query including
  protocol work. On separate machines the proxy would have its own cores; here it's taking them from Postgres.
- **The events-on vs events-off gap is mostly not the proxy.** Pausing ClickHouse brought events-on
  close to events-off (8.7K vs 10.3K). Most of the drop was ClickHouse ingesting on the same laptop.
  Publishing itself costs the proxy ~15%.
- **The guide's target was 0.3-1.5 ms added per query.** I didn't hit that under saturation.
  Single-client runs (one query at a time) were too noisy on this machine to quote: direct Postgres alone
  ranged 0.34-1.09 ms between runs. Every extra hop costs a CPU wake-up when the box is mostly idle. I'd rerun both on a
  dedicated Linux box before putting a latency number on a resume.
- **What I tried that didn't move it:** moving the decision timeout from `CompletableFuture.orTimeout`
  (one JDK-wide scheduler lock) to the connection's event loop, and an inline fast path that decides
  cached shapes on the event loop instead of handing them to a virtual thread. Both are better designs and both
  stayed in, but the end-to-end change was within noise here.

Phase 1 (plain TCP pass-through, before any protocol work): `bench/pgbench/results/phase1.md`.

## 3. Behaviour under overload (the run that found the worst bug)

Traffic generator (`bench/traffic`), five personas at a few hundred queries/s, everything on the
laptop, Postgres at ~500% CPU:

| | Before fixes | After fixes |
|---|---|---|
| Decision latency p50 / p99 | 68 ms / 777 ms | 5 ms / 253 ms |
| Longest-running query `R3`/`R5` should have blocked | `SELECT * FROM orders` for 3+ min | none over 3 s |

Before the fix, EXPLAIN timed out under load and the cost rules failed open, so the expensive queries
they exist to stop got through and made the overload worse. The fix was a table-statistics fallback
plus backing off EXPLAIN. Details in `docs/bug-journal.md`.

Final mix over 534,331 queries from that run: 95.1% allowed, 1.65% R3 (expensive analyst query),
1.60% R7 (write outside maintenance window), 0.44% M1 (masking bypass attempt), 1.2% other rules,
0.02% genuine database errors.

## 4. Live rule reload

10 consecutive `PUT /rules/R6` calls through the admin API. Measured inside the proxy as "now minus the
commit timestamp in the NOTIFY payload" (same machine, same clock):

```
21 22 23 23 24 24 25 26 40 170   (ms)
```

Median **24 ms**. The 170 ms outlier was the first change after a restart (cold JIT on the reload path).

## 5. Admin API (k6)

`bench/k6/admin-api.js`: 200 virtual users, 70% `GET /rules`, 30% `POST /rules/test` (dry run with real
parsing and EXPLAIN). 20 s ramp, 2 min at 200, 10 s ramp down.

| Scenario | req/s | list p95 | dry-run p95 | errors |
|---|---|---|---|---|
| Realistic: each user pauses 0.5-1.5 s | 178 | **10.0 ms** | **15.5 ms** | 0 / 26,966 |
| Saturation, before caching the rule list | 761 | 571 ms | 750 ms | 0 / 114,582 |
| Saturation, rule list cached by config version | **1,936** | 185 ms | 229 ms | 0 / 290,445 |
| (tried: Spring virtual threads on top) | 562 | 1,050 ms | 1,260 ms | 0 |

The list endpoint used to parse every rule's JSON on every request. Now it's cached and keyed by
`config_version`, so each request costs one single-row read and a stale list can't be served. Virtual threads
made it *worse*: 200 concurrent requests all hit the 20-connection pool and the shared CPU at once,
instead of being paced by Tomcat's thread pool. Reverted.

In the saturation runs latency just reflects queueing (200 users / throughput), so I only use them
for the max-throughput number.

## 6. Query log pipeline (Kafka → ClickHouse)

| | |
|---|---|
| Events in ClickHouse | 3,647,500 (1,004,924 from the long traffic run) |
| Busiest minute, sustained | 9,133 events/s |
| Catch-up after pausing ClickHouse for ~1 minute | 130,818 events/s peak; Kafka held the backlog, nothing lost |
| Proxy → queryable in ClickHouse, normal load | p50 ≈ 0.2 s, p95 0.3-2.2 s, p99 0.6-3.4 s (per-minute ranges) |
| Events dropped by the proxy | 0 of 1.37M sent in one session |
| On disk | 184 MB for 3.6M raw events; whole table compresses 4.1×, `sql_normalized` 100× |
| dbt | 19/19 models and tests pass, including "no literal values in logged SQL" |

The delay floor is mostly the Kafka engine's 1 s flush interval, which is a setting. `ingested_at` is
`DateTime` (second resolution) while `ts` has milliseconds, so the delay figures are ±1 s.

dbt's `unique` test on the first big run found 0.7% duplicate events. They came from Kafka redelivering batches
during consumer rebalances under load (at-least-once), not from the proxy. The raw table is now a
`ReplacingMergeTree` and dbt reads it with `FINAL`. The hourly rollups count at insert time, so they
can over-count by about that much during heavy redelivery.

## 7. Connections

`bench/connections/hold.py`, idle connections through the proxy, one query each:

| Connections | Proxy heap used | Container memory |
|---|---|---|
| 0 | 62 MB | 318 MB |
| 50 | | 428 MB |
| 100 | | 432 MB |
| 200 | | 411 MB |
| 250 | 122 MB | 418 MB |

About **240 KB of heap per connection**. Container memory is dominated by the JVM's own footprint
(ZGC commits its heap up front). The ceiling is **Postgres** (`max_connections=300` in the demo),
because the proxy maps client connections 1:1 to server connections. The guide's 500 / 1,000 / 2,000 /
5,000 steps aren't reachable without pooling behind the proxy; see question 7 in `docs/design-decisions.md`.

---

## Numbers I'm comfortable putting on a resume

- Decision step **~5 µs** per query for known query shapes (JMH), ~160× faster than parsing every query
- Rule changes live in **~25 ms** across running proxies, no restart
- **3.6M+** query events through Kafka into ClickHouse, **0** dropped by the proxy, with catch-up at 130K events/s after an outage
- Admin API **1,900+ req/s**, p95 **10-16 ms** at 200 concurrent users with realistic pacing
- Found and fixed a fail-open cascade that let expensive queries through under overload

And the one to be careful with: proxy throughput overhead. On this laptop it's large, because everything
shares one CPU. Quote it only with that context, or rerun on real hardware first.
