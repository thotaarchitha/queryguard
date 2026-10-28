"""
Fake workload for QueryGuard: five kinds of users with their own habits, a few bad ones.

Each worker is a thread with its own connection through the proxy. psycopg 3 switches to
server-side prepared statements after 5 executions of the same query, so this exercises both
the simple and the extended protocol without trying.

    python generator.py                      # defaults: 2 minutes, ~200 qps
    DURATION_S=3600 RATE=800 python generator.py

Prints a summary of what happened (allowed / blocked by rule / errors) at the end.
"""
import os
import random
import re
import signal
import threading
import time
from collections import Counter

import psycopg

HOST = os.getenv("QG_HOST", "localhost")
PORT = int(os.getenv("QG_PORT", "6432"))
DB = os.getenv("QG_DB", "shop")
DURATION_S = float(os.getenv("DURATION_S", "120"))
RATE = float(os.getenv("RATE", "200"))          # total queries/second across all workers
TARGET = int(os.getenv("TARGET_QUERIES", "0"))  # stop after this many (0 = run for DURATION_S)
SCALE = int(os.getenv("WORKER_SCALE", "1"))      # more connections per role for higher rates

stop = threading.Event()
lock = threading.Lock()
outcomes = Counter()
sent = 0
RULE = re.compile(r"QueryGuard (R\d+|M\d+|QG-[A-Z]+)")


def rid(hi):
    return random.randint(1, hi)


def day_range():
    start = random.randint(1, 700)
    return f"now() - interval '{start} days'", f"now() - interval '{max(0, start - random.choice([1, 7, 30]))} days'"


# (weight, sql, params) generators per role. Weights are relative within a role.
def app_queries():
    oid = rid(5_000_000)
    cid = rid(1_000_000)
    return random.choices([
        ("SELECT id, status, total_cents, created_at FROM orders WHERE id = %s", (oid,)),
        ("SELECT id, status, total_cents FROM orders WHERE customer_id = %s ORDER BY created_at DESC LIMIT 20", (cid,)),
        ("SELECT full_name, email, country FROM customers WHERE id = %s", (cid,)),
        ("UPDATE orders SET status = 'shipped' WHERE id = %s AND status = 'paid'", (oid,)),
        ("INSERT INTO orders (id, customer_id, product_id, quantity, total_cents, status, created_at) "
         "VALUES (%s, %s, %s, 1, 1999, 'placed', now()) ON CONFLICT (id) DO NOTHING",
         (random.randint(10_000_000, 99_000_000), cid, rid(10_000))),
        ("SELECT p.name, p.price_cents FROM products p WHERE p.category = %s ORDER BY p.price_cents LIMIT 10",
         (random.choice(["books", "toys", "garden", "kitchen"]),)),
        # the occasional mistake
        ("UPDATE orders SET status = 'cancelled'", None),
        ("SELECT o.id FROM orders o, customers c LIMIT 10", None),
        ("SELECT * FROM orders", None),
    ], weights=[40, 20, 15, 8, 6, 8, 0.3, 0.4, 0.5])[0]


def analyst_queries():
    a, b = day_range()
    return random.choices([
        (f"SELECT status, count(*), sum(total_cents) FROM orders WHERE created_at BETWEEN {a} AND {b} GROUP BY status", None),
        ("SELECT country, count(*) FROM customers GROUP BY country", None),
        ("SELECT id, email, phone, country FROM customers WHERE country = %s AND id < %s",
         (random.choice(["IN", "US", "DE"]), rid(5000))),
        ("SELECT id, email FROM customers WHERE id = %s", (rid(1_000_000),)),
        ("SELECT product_id, count(*) AS n FROM orders WHERE customer_id < %s GROUP BY product_id ORDER BY n DESC LIMIT 20",
         (rid(20_000),)),
        # expensive or sneaky
        ("SELECT customer_id, sum(total_cents) FROM orders GROUP BY customer_id ORDER BY 2 DESC", None),
        ("SELECT o.status, c.country, count(*) FROM orders o JOIN customers c ON c.id = o.customer_id GROUP BY 1, 2", None),
        ("SELECT upper(email) FROM customers WHERE id = %s", (rid(1000),)),
        ("SELECT id FROM customers WHERE email LIKE %s LIMIT 5", ("user1%",)),
        ("SELECT * FROM orders WHERE status = 'paid'", None),
    ], weights=[20, 10, 15, 15, 12, 4, 4, 1.5, 1.5, 3])[0]


def etl_queries():
    lo = rid(4_900_000)
    return random.choices([
        ("SELECT * FROM orders WHERE id BETWEEN %s AND %s", (lo, lo + 500)),
        ("SELECT id, customer_id, total_cents, created_at FROM orders WHERE created_at > now() - interval '1 day' LIMIT 5000", None),
        ("SELECT * FROM products", None),
        ("SELECT id, email, card_last4 FROM customers WHERE id BETWEEN %s AND %s", (lo % 999_000, lo % 999_000 + 200)),
    ], weights=[40, 20, 10, 30])[0]


def admin_queries():
    return random.choices([
        ("SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY n_live_tup DESC", None),
        ("SELECT count(*) FROM pg_stat_activity", None),
        ("CREATE TABLE IF NOT EXISTS scratch_admin (id int)", None),
        ("DROP TABLE IF EXISTS scratch_admin", None),
        ("SELECT pg_sleep(0.01)", None),
    ], weights=[40, 40, 8, 8, 4])[0]


def readonly_queries():
    return random.choices([
        ("SELECT status, count(*) FROM orders WHERE customer_id = %s GROUP BY status", (rid(1_000_000),)),
        ("UPDATE orders SET status = 'delivered' WHERE id = %s", (rid(5_000_000),)),
        ("DELETE FROM orders WHERE id = %s", (rid(5_000_000),)),
        ("TRUNCATE scratch_admin", None),
    ], weights=[85, 8, 5, 2])[0]


ROLES = {
    # user: (password, query generator, share of total traffic, workers)
    "bob": ("bob", app_queries, 0.55, 8),
    "alice": ("alice", analyst_queries, 0.20, 4),
    "etl_user": ("etl_user", etl_queries, 0.15, 3),
    "postgres": ("dev", admin_queries, 0.04, 1),
    "report_ro": ("report_ro", readonly_queries, 0.06, 2),
}


def record(key):
    global sent
    with lock:
        outcomes[key] += 1
        sent += 1


def worker(user, password, gen, qps):
    interval = 1.0 / qps if qps > 0 else 0.1
    conninfo = f"host={HOST} port={PORT} dbname={DB} user={user} password={password} sslmode=disable application_name=traffic-{user}"
    conn = None
    next_at = time.monotonic()
    while not stop.is_set():
        try:
            if conn is None or conn.closed:
                conn = psycopg.connect(conninfo, autocommit=True, connect_timeout=5)
            sql, params = gen()
            with conn.cursor() as cur:
                cur.execute(sql, params)
                if cur.description:
                    cur.fetchall()
            record("ok")
        except psycopg.Error as e:
            msg = str(e)
            m = RULE.search(msg)
            record(m.group(1) if m else f"error:{getattr(e, 'sqlstate', None) or type(e).__name__}")
            if conn is not None and conn.broken:
                conn = None
        except OSError:
            record("error:network")
            conn = None
            time.sleep(1)
        next_at += interval
        delay = next_at - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        elif delay < -1:
            next_at = time.monotonic()  # fell behind; don't try to catch up in a burst
        if TARGET and sent >= TARGET:
            stop.set()
    if conn is not None:
        conn.close()


def main():
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    threads = []
    for user, (pw, gen, share, n) in ROLES.items():
        n *= SCALE
        per_worker = RATE * share / n
        for _ in range(n):
            t = threading.Thread(target=worker, args=(user, pw, gen, per_worker), daemon=True)
            t.start()
            threads.append(t)
    started = time.monotonic()
    try:
        while not stop.is_set() and (TARGET or time.monotonic() - started < DURATION_S):
            time.sleep(5)
            with lock:
                print(f"[{time.monotonic() - started:6.0f}s] {sent} queries, {sent / (time.monotonic() - started):.0f}/s, "
                      f"blocked so far: {sum(v for k, v in outcomes.items() if k not in ('ok',) and not k.startswith('error'))}",
                      flush=True)
    except KeyboardInterrupt:
        pass
    stop.set()
    for t in threads:
        t.join(timeout=5)
    elapsed = time.monotonic() - started
    print(f"\n{sent} queries in {elapsed:.0f}s ({sent / elapsed:.0f}/s)")
    for k, v in outcomes.most_common():
        print(f"  {k:<22} {v:>9}  {100 * v / max(sent, 1):5.2f}%")


if __name__ == "__main__":
    main()
