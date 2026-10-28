#!/usr/bin/env sh
# Phase 1 overhead: pgbench straight to PostgreSQL vs through the pass-through proxy.
# Run with the compose stack up:
#   docker run --rm --network queryguard_default -e PGPASSWORD=dev \
#     -v "$PWD/bench/pgbench:/bench" postgres:16 sh /bench/phase1.sh
# Uses its own "bench" database so the shop data stays untouched.
set -eu

CLIENTS=${CLIENTS:-20}
SECONDS_PER_RUN=${SECONDS_PER_RUN:-20}
RUNS=${RUNS:-3}

psql -h postgres -U postgres -d postgres -qc "DROP DATABASE IF EXISTS bench" -c "CREATE DATABASE bench"
pgbench -h postgres -U postgres -i -s 10 -q bench

run() {
  label=$1 host=$2 port=$3
  for i in $(seq 1 "$RUNS"); do
    out=$(pgbench -h "$host" -p "$port" -U postgres -S -c "$CLIENTS" -j 4 -T "$SECONDS_PER_RUN" bench 2>&1)
    tps=$(echo "$out" | sed -n 's/^tps = \([0-9.]*\).*/\1/p')
    lat=$(echo "$out" | sed -n 's/^latency average = \([0-9.]*\) ms/\1/p')
    echo "$label run=$i tps=$tps latency_avg_ms=$lat"
  done
}

run direct postgres 5432
run proxy  proxy    6432
