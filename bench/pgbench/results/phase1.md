# Phase 1: pass-through overhead

Plain TCP forwarding, no protocol parsing or rules yet.

## Setup

- Machine: laptop, Intel Core i7-1355U (12 logical CPUs), Windows 11 + Docker Desktop (8 GB to Docker)
- PostgreSQL 16, proxy and pgbench all run as containers on the same Docker network
  and share the same CPUs, so the absolute numbers are a lower bound.
- Command: `bench/pgbench/phase1.sh` (pgbench `-S -c 20 -j 4 -T 20`, scale 10, 3 runs each)
- Date: 2026-10-28

## Raw runs

| Target | Run | TPS | Avg latency (ms) |
|---|---|---|---|
| direct | 1 | 43,423 | 0.461 |
| direct | 2 | 14,605 | 1.369 |
| direct | 3 | 34,568 | 0.579 |
| proxy | 1 | 21,591 | 0.926 |
| proxy | 2 | 17,686 | 1.131 |
| proxy | 3 | 22,021 | 0.908 |

## Median

| Target | TPS | Avg latency (ms) |
|---|---|---|
| direct | 34,568 | 0.579 |
| through proxy | 21,591 | 0.926 |
| **overhead** | **-37.5%** | **+0.35 ms** |

## Notes

- Direct run 2 is an outlier (laptop noise, probably thermal or background load). That's why
  the median of three runs is used.
- `-S` is select-only and each query is tiny, so this is the worst case for relative overhead:
  the extra network hop is a big share of a ~0.5 ms query. It shrinks as a percentage for real queries.
- The proxy competes with pgbench and PostgreSQL for the same CPUs here. Rerun on a dedicated
  Linux box or a Codespace before quoting these numbers anywhere.
