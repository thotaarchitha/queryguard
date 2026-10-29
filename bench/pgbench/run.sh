#!/usr/bin/env sh
# Proxy overhead with the full rule set on: pgbench straight to Postgres vs through QueryGuard.
#
#   docker run --rm --network queryguard_default -e PGPASSWORD=dev \
#     -v "$PWD/bench/pgbench:/bench" postgres:16 sh /bench/run.sh
#
# Runs as bob (role "app"), so every rule is evaluated for every query; nothing pgbench sends
# is actually blocked. Two protocol modes:
#   simple   - every query is a Q message, so every query goes through a decision
#   prepared - Parse once, then Bind/Execute; the decision happens once per statement
# Each (target, mode) runs RUNS times; the summary uses the median run by TPS.
set -eu

CLIENTS=${CLIENTS:-32}
THREADS=${THREADS:-4}
SECONDS_PER_RUN=${SECONDS_PER_RUN:-30}
RUNS=${RUNS:-3}
OUT=/tmp/pgb

psql -h postgres -U postgres -d postgres -qc "DROP DATABASE IF EXISTS bench" -c "CREATE DATABASE bench"
pgbench -h postgres -U postgres -i -s 10 -q bench >/dev/null 2>&1
psql -h postgres -U postgres -d bench -qc "GRANT SELECT, UPDATE, INSERT ON ALL TABLES IN SCHEMA public TO bob, qg_explain"

mkdir -p $OUT
printf "%-8s %-9s %-4s %10s %9s %9s\n" target mode run tps avg_ms p99_ms

run() {
  target=$1 host=$2 port=$3 mode=$4 i=$5
  rm -f $OUT/pgbench_log.*
  PGPASSWORD=bob pgbench -h "$host" -p "$port" -U bob -S -M "$mode" -c "$CLIENTS" -j "$THREADS" \
    -T "$SECONDS_PER_RUN" --log --log-prefix=$OUT/pgbench_log bench > $OUT/out.txt 2>&1 || { cat $OUT/out.txt; exit 1; }
  tps=$(sed -n 's/^tps = \([0-9.]*\).*/\1/p' $OUT/out.txt)
  avg=$(sed -n 's/^latency average = \([0-9.]*\) ms/\1/p' $OUT/out.txt)
  # per-transaction log: 3rd column is latency in microseconds
  p99=$(cat $OUT/pgbench_log.* | awk '{print $3}' | sort -n | awk '{a[NR]=$1} END {printf "%.3f", a[int(NR*0.99)]/1000}')
  printf "%-8s %-9s %-4s %10.0f %9s %9s\n" "$target" "$mode" "$i" "$tps" "$avg" "$p99" | tee -a $OUT/all.txt
}

: > $OUT/all.txt
for mode in simple prepared; do
  for i in $(seq 1 "$RUNS"); do
    run direct postgres 5432 "$mode" "$i"
    run proxy  proxy    6432 "$mode" "$i"
  done
done

echo
echo "median by TPS:"
for mode in simple prepared; do
  for target in direct proxy; do
    grep "^$target *$mode " $OUT/all.txt | sort -k4 -n | awk -v n="$RUNS" 'NR == int((n + 1) / 2)'
  done
done
