-- Config store for QueryGuard. The proxy reads these tables; only the admin API writes them.

-- One row. Bumped on every change, and sent as the NOTIFY payload so proxies can tell
-- which version they're on (it's on their /health/ready).
CREATE TABLE config_version (
    id      int    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version bigint NOT NULL
);
INSERT INTO config_version (id, version) VALUES (1, 0);

-- Current state of each rule. body is the rule exactly as the API accepted it (snake_case JSON).
CREATE TABLE rules (
    id         text        PRIMARY KEY,
    version    int         NOT NULL DEFAULT 1,
    body       jsonb       NOT NULL,
    enabled    boolean     NOT NULL DEFAULT true,
    created_by text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Every version a rule has ever had, so GET /rules/{id}/history can show the whole story.
CREATE TABLE rule_versions (
    rule_id    text        NOT NULL REFERENCES rules (id),
    version    int         NOT NULL,
    body       jsonb       NOT NULL,
    enabled    boolean     NOT NULL,
    changed_by text        NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, version)
);

CREATE TABLE masking_policies (
    id           bigserial PRIMARY KEY,
    table_name   text      NOT NULL,
    column_name  text      NOT NULL,
    strategy     text      NOT NULL,
    roles        jsonb,
    except_roles jsonb,
    UNIQUE (table_name, column_name)
);

CREATE TABLE role_mappings (
    db_user text PRIMARY KEY,
    role    text NOT NULL
);

CREATE TABLE audit_log (
    id        bigserial   PRIMARY KEY,
    actor     text        NOT NULL,
    action    text        NOT NULL,
    entity    text        NOT NULL,
    entity_id text,
    before    jsonb,
    after     jsonb,
    at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity, entity_id, at DESC);
