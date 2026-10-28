-- Fake shop database for QueryGuard demos. All data is synthetic.
--   products:   10K rows
--   customers:  1M rows (email, phone, card_last4 are what masking protects)
--   orders:     5M rows (big enough for the cost rules to have something to block)
-- Runs automatically on first start of the postgres container (POSTGRES_DB=shop).

\timing on

CREATE TABLE products (
    id          bigint PRIMARY KEY,
    name        text   NOT NULL,
    category    text   NOT NULL,
    price_cents int    NOT NULL
);

INSERT INTO products
SELECT g,
       'Product ' || g,
       (ARRAY['books', 'toys', 'garden', 'kitchen', 'sports', 'electronics'])[1 + g % 6],
       199 + (g * 37) % 49800
FROM generate_series(1::bigint, 10000) AS g;

CREATE TABLE customers (
    id         bigint      PRIMARY KEY,
    full_name  text        NOT NULL,
    email      text        NOT NULL,
    phone      text        NOT NULL,
    card_last4 char(4)     NOT NULL,
    country    text        NOT NULL,
    created_at timestamptz NOT NULL
);

-- 555-01xx style numbers and example.com addresses are reserved for fiction,
-- so none of this can collide with a real person.
INSERT INTO customers
SELECT g,
       'Customer ' || g,
       'user' || g || '@example.com',
       '+1-555-01' || lpad((g % 100)::text, 2, '0'),
       lpad(((g * 7919) % 10000)::text, 4, '0'),
       (ARRAY['US', 'IN', 'GB', 'DE', 'CA', 'BR'])[1 + g % 6],
       now() - ((g % 1500) || ' days')::interval
FROM generate_series(1::bigint, 1000000) AS g;

CREATE TABLE orders (
    id          bigint      PRIMARY KEY,
    customer_id bigint      NOT NULL,
    product_id  bigint      NOT NULL,
    quantity    int         NOT NULL,
    total_cents bigint      NOT NULL,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL
);

INSERT INTO orders
SELECT g,
       1 + (g * 7) % 1000000,
       1 + (g * 13) % 10000,
       1 + g % 5,
       (1 + g % 5) * (199 + ((g * 13) % 10000 * 37) % 49800),
       (ARRAY['placed', 'paid', 'shipped', 'delivered', 'cancelled'])[1 + g % 5],
       now() - ((g % 730) || ' days')::interval - ((g % 86400) || ' seconds')::interval
FROM generate_series(1::bigint, 5000000) AS g;

-- Constraints and indexes after the bulk load; much faster than maintaining them row by row.
ALTER TABLE orders ADD CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES customers (id);
ALTER TABLE orders ADD CONSTRAINT orders_product_fk  FOREIGN KEY (product_id)  REFERENCES products (id);
CREATE INDEX orders_customer_idx   ON orders (customer_id);
CREATE INDEX orders_created_at_idx ON orders (created_at);
CREATE INDEX customers_country_idx ON customers (country);

ANALYZE;
