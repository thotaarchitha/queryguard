-- Triggers every rule from psql. Run through the proxy:
--
--   psql "postgresql://postgres:dev@localhost:6432/shop" -f infra/demo/rules.sql
--
-- (In docker compose, the host is "proxy" instead of localhost; CI's smoke job does that.)
-- Each block switches user with \connect so the right role is in play. Errors are expected;
-- that's the point.

\set ON_ERROR_STOP off
\set QUIET on
\pset pager off
\if :{?qg_host}
\else
  \set qg_host localhost
\endif

\echo '== R1  bob (app): DELETE with no WHERE'
\connect postgresql://bob:bob@:qg_host:6432/shop
DELETE FROM orders;

\echo '== R2  bob (app): DROP TABLE'
DROP TABLE orders;

\echo '== R8  bob (app): pg_sleep hidden in a subquery'
SELECT id FROM orders WHERE id = (SELECT 1 FROM pg_sleep(30));

\echo '== R6  bob (app): comma join with no condition'
SELECT o.id, c.id FROM orders o, customers c LIMIT 5;

\echo '== R5  bob (app): SELECT * over 5M rows'
SELECT * FROM orders;

\echo '== multi-statement: the DROP rides along with a harmless SELECT'
SELECT 1; DROP TABLE customers;

\echo '== R3  alice (analyst): a full aggregation over orders'
\connect postgresql://alice:alice@:qg_host:6432/shop
SELECT customer_id, sum(total_cents) FROM orders GROUP BY customer_id ORDER BY 2 DESC;

\echo '== R4  alice (analyst): no LIMIT, so one gets added (count the rows)'
SELECT count(*) AS rows_returned FROM (SELECT 1) x;
SELECT id FROM orders WHERE customer_id < 2000 \g /dev/null
\echo '   (rewritten to LIMIT 1000; see the proxy log or the REWRITE count in Grafana)'

\echo '== masking: same query, analyst vs admin'
SELECT id, email, phone, card_last4 FROM customers WHERE id IN (7, 42) LIMIT 2;
\echo '== M1  alice: trying to unmask with an expression'
SELECT upper(email) FROM customers WHERE id = 42 LIMIT 1;
\echo '== M1  alice: guessing emails one letter at a time'
SELECT id FROM customers WHERE email LIKE 'user4%' LIMIT 1;

\connect postgresql://postgres:dev@:qg_host:6432/shop
SELECT id, email, phone, card_last4 FROM customers WHERE id IN (7, 42);

\echo '== R7  report_ro (app_readonly): a write outside the Sat/Sun 02:00-04:00 UTC window'
\connect postgresql://report_ro:report_ro@:qg_host:6432/shop
UPDATE orders SET status = 'delivered' WHERE id = 1;

\echo '== allowed: an ordinary app query'
\connect postgresql://bob:bob@:qg_host:6432/shop
SELECT id, status, total_cents FROM orders WHERE id = 42;
