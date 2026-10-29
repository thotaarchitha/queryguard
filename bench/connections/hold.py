"""
Opens N connections through the proxy, runs one query on each, then holds them idle so the
proxy's memory per connection can be measured from outside (docker stats, /metrics).

    python hold.py 200 60     # 200 connections, hold 60 s

The ceiling here is Postgres, not the proxy: QueryGuard maps client connections 1:1 to server
connections and the demo Postgres has max_connections=300. See design-decisions.md, question 7.
"""
import os
import sys
import time

import psycopg

n = int(sys.argv[1])
hold = float(sys.argv[2]) if len(sys.argv) > 2 else 30
host = os.getenv("QG_HOST", "proxy")
dsn = f"host={host} port=6432 dbname=shop user=bob password=bob sslmode=disable application_name=hold"

conns = []
t0 = time.monotonic()
for i in range(n):
    c = psycopg.connect(dsn, autocommit=True)
    c.execute("SELECT 1").fetchone()
    conns.append(c)
print(f"opened {n} connections in {time.monotonic() - t0:.1f}s, holding {hold:.0f}s", flush=True)
time.sleep(hold)
for c in conns:
    c.close()
print("closed", flush=True)
