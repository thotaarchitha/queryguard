-- Small version of infra/seed for integration tests: same schema and roles, fewer rows.
CREATE TABLE products (id bigint PRIMARY KEY, name text NOT NULL, category text NOT NULL, price_cents int NOT NULL);
INSERT INTO products SELECT g, 'Product ' || g, 'books', 100 + g FROM generate_series(1::bigint, 100) g;

CREATE TABLE customers (id bigint PRIMARY KEY, full_name text NOT NULL, email text NOT NULL, phone text NOT NULL,
                        card_last4 char(4) NOT NULL, country text NOT NULL, created_at timestamptz NOT NULL);
INSERT INTO customers SELECT g, 'Customer ' || g, 'user' || g || '@example.com', '+1-555-01' || lpad((g % 100)::text, 2, '0'),
       lpad(((g * 7919) % 10000)::text, 4, '0'), 'IN', now() FROM generate_series(1::bigint, 500) g;

CREATE TABLE orders (id bigint PRIMARY KEY, customer_id bigint NOT NULL REFERENCES customers (id),
                     product_id bigint NOT NULL REFERENCES products (id), quantity int NOT NULL,
                     total_cents bigint NOT NULL, status text NOT NULL, created_at timestamptz NOT NULL);
INSERT INTO orders SELECT g, 1 + g % 500, 1 + g % 100, 1, 100, 'placed', now() FROM generate_series(1::bigint, 3000) g;
ANALYZE;

CREATE ROLE alice LOGIN PASSWORD 'alice';
CREATE ROLE bob LOGIN PASSWORD 'bob';
CREATE ROLE etl_user LOGIN PASSWORD 'etl_user';
GRANT SELECT ON ALL TABLES IN SCHEMA public TO alice;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO bob, etl_user;
CREATE ROLE qg_explain LOGIN PASSWORD 'qg_explain';
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO qg_explain;
ALTER ROLE qg_explain SET default_transaction_read_only = on;
