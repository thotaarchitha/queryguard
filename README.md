# QueryGuard

A PostgreSQL proxy that blocks dangerous SQL, masks sensitive columns, and turns every query into analytics.

![CI](https://github.com/thotaarchitha/queryguard/actions/workflows/ci.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)

> **Status: in progress.** Phase 1 (TCP pass-through proxy) is done. See the roadmap below.

## Quick start

```bash
git clone https://github.com/thotaarchitha/queryguard.git && cd queryguard
docker compose up -d --build --wait
psql -h localhost -p 6432 -U postgres -d shop   # password: dev
```

The first start seeds a fake shop database (1M customers, 5M orders, 10K products) in about 30 seconds.
All data is synthetic.

## How it works (so far)

```
psql / JDBC / psycopg  ──►  QueryGuard :6432  ──►  PostgreSQL :5432
```

- Java 21 + Netty, non-blocking I/O
- Client and database connections share one event loop thread (no thread hops)
- Backpressure in both directions so a slow client can't make the proxy buffer a whole result set
- The client is disconnected right away if PostgreSQL goes down

Configured through environment variables:

| Variable | Default |
|---|---|
| `QG_LISTEN_PORT` | `6432` |
| `QG_PG_HOST` | `localhost` |
| `QG_PG_PORT` | `5432` |
| `QG_CONNECT_TIMEOUT_MS` | `3000` |

## Results so far

| Benchmark | Direct | Through proxy |
|---|---|---|
| pgbench `-S`, 20 clients, median TPS | 34,568 | 21,591 |
| Avg latency | 0.58 ms | 0.93 ms |

Laptop numbers with everything in Docker on shared CPUs. Details in
[bench/pgbench/results/phase1.md](bench/pgbench/results/phase1.md).

## Roadmap

- [x] Phase 1: TCP pass-through proxy
- [ ] Phase 2: PostgreSQL wire protocol decoding
- [ ] Phase 3: Rule engine with EXPLAIN cost estimates ([starting rules](infra/rules/default-rules.yml))
- [ ] Phase 4: Column masking and resilience
- [ ] Phase 5: Spring Boot admin API with live rule reload
- [ ] Phase 6: Kafka → ClickHouse → Grafana query analytics
- [ ] Phase 7: Test suite and benchmarks
- [ ] Phase 8: Docker, Helm and CI/CD
- [ ] Phase 9: Demo and write-up

## Development

```bash
./gradlew build
```

No local Java? Build in Docker:

```bash
docker run --rm -v "$PWD:/src" -w /src gradle:8.10.2-jdk21 ./gradlew build
```

Design notes: [docs/design-decisions.md](docs/design-decisions.md)

## License

MIT
