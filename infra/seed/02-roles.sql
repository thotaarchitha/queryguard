-- Demo users and the QueryGuard service accounts.
-- Passwords equal usernames. Local demo only; the Helm chart takes real ones from Secrets.

CREATE ROLE alice     LOGIN PASSWORD 'alice';      -- analyst
CREATE ROLE bob       LOGIN PASSWORD 'bob';        -- app
CREATE ROLE etl_user  LOGIN PASSWORD 'etl_user';   -- etl
CREATE ROLE report_ro LOGIN PASSWORD 'report_ro';  -- app_readonly

GRANT SELECT ON ALL TABLES IN SCHEMA public TO alice;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO bob, etl_user, report_ro;
-- report_ro gets write grants on purpose: the database would allow the write, and
-- rule R7 is what stops it outside the maintenance window.

-- qg_explain runs EXPLAIN for cost rules and reads pg_attribute for masking.
-- Postgres checks write privileges when it *plans* an UPDATE/DELETE, so EXPLAIN UPDATE needs
-- UPDATE. The read-only default transaction means this account can never actually write.
CREATE ROLE qg_explain LOGIN PASSWORD 'qg_explain';
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO qg_explain;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO qg_explain;
ALTER ROLE qg_explain SET default_transaction_read_only = on;

-- Config database for the admin API (rules, masking, role mappings, audit log).
CREATE ROLE qg_config LOGIN PASSWORD 'qg_config';
CREATE DATABASE queryguard_config OWNER qg_config;
