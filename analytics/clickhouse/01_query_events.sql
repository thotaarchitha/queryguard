-- QueryGuard query log in ClickHouse.
--
--   Kafka topic qg.query-events
--     -> query_events_kafka   (Kafka engine: a consumer, not storage)
--     -> mv_query_events      -> query_events          (raw events, 90 days)
--     -> mv_query_events_errors -> query_events_errors (rows that didn't parse, 14 days)
--   query_events
--     -> mv_query_stats_hourly   -> query_stats_hourly     (per hour/role/decision/rule)
--     -> mv_top_fingerprints     -> top_fingerprints_daily (per day/query shape)

CREATE DATABASE IF NOT EXISTS qg;

CREATE TABLE IF NOT EXISTS qg.query_events_kafka
(
    event_id            String,
    ts                  DateTime64(3, 'UTC'),
    proxy_instance      String,
    user                String,
    role                String,
    database            String,
    client_ip           String,
    application         String,
    statement_type      String,
    fingerprint         String,
    sql_normalized      String,
    tables              Array(String),
    decision            String,
    rule_id             Nullable(String),
    estimated_cost      Nullable(Float64),
    estimated_rows      Nullable(Float64),
    decision_latency_us UInt64,
    query_latency_ms    Nullable(Float64),
    rows_returned       Nullable(UInt64),
    masked_columns      Array(String),
    error_code          Nullable(String)
)
ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = 'qg.query-events',
         kafka_group_name = 'clickhouse-qg',
         kafka_format = 'JSONEachRow',
         -- bad rows go to the _error/_raw_message virtual columns instead of stopping the consumer
         kafka_handle_error_mode = 'stream',
         kafka_num_consumers = 1,
         kafka_max_block_size = 65536,
         kafka_flush_interval_ms = 1000;

-- Sort key: dashboards almost always filter on decision first (blocked vs allowed), then role,
-- then a user. ts next so each (decision, role, user) range is in time order. Partition by day
-- so the 90-day TTL drops whole parts instead of rewriting them.
--
-- ReplacingMergeTree with event_id last in the key: the Kafka engine is at-least-once, and under
-- load (consumer rebalances) it redelivers batches. Duplicates are identical rows with the same
-- event_id; merges collapse them, and readers that need exact counts use FINAL (the dbt staging
-- model does). Found by dbt's unique test on the first big run: 0.7% duplicates.
CREATE TABLE IF NOT EXISTS qg.query_events
(
    event_id            String,
    ts                  DateTime64(3, 'UTC'),
    proxy_instance      LowCardinality(String),
    user                LowCardinality(String),
    role                LowCardinality(String),
    database            LowCardinality(String),
    client_ip           String,
    application         LowCardinality(String),
    statement_type      LowCardinality(String),
    fingerprint         String,
    sql_normalized      String CODEC(ZSTD(3)),
    tables              Array(LowCardinality(String)),
    decision            LowCardinality(String),
    rule_id             Nullable(String),
    estimated_cost      Nullable(Float64),
    estimated_rows      Nullable(Float64),
    decision_latency_us UInt64,
    query_latency_ms    Nullable(Float64),
    rows_returned       Nullable(UInt64),
    masked_columns      Array(LowCardinality(String)),
    error_code          Nullable(String),
    ingested_at         DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toDate(ts)
ORDER BY (decision, role, user, ts, event_id)
TTL toDateTime(ts) + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS qg.query_events_errors
(
    raw        String,
    error      String,
    topic      String,
    partition  UInt64,
    offset     UInt64,
    at         DateTime DEFAULT now()
)
ENGINE = MergeTree
ORDER BY at
TTL at + INTERVAL 14 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS qg.mv_query_events TO qg.query_events AS
SELECT event_id, ts, proxy_instance, user, role, database, client_ip, application, statement_type,
       fingerprint, sql_normalized, tables, decision, rule_id, estimated_cost, estimated_rows,
       decision_latency_us, query_latency_ms, rows_returned, masked_columns, error_code
FROM qg.query_events_kafka
WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW IF NOT EXISTS qg.mv_query_events_errors TO qg.query_events_errors AS
SELECT _raw_message AS raw, _error AS error, _topic AS topic, _partition AS partition, _offset AS offset
FROM qg.query_events_kafka
WHERE length(_error) > 0;

-- Hourly rollup. Aggregate *states*, so hours can be merged into days/weeks later without
-- going back to raw events (p95 of p95s would be wrong; merging quantile states isn't).
-- These aggregate at insert time, so Kafka redeliveries are counted twice: good for
-- dashboards, not for exact numbers. Exact numbers come from the dbt marts (deduplicated).
CREATE TABLE IF NOT EXISTS qg.query_stats_hourly
(
    hour         DateTime,
    role         LowCardinality(String),
    decision     LowCardinality(String),
    rule_id      String,
    queries      AggregateFunction(count),
    latency_avg  AggregateFunction(avg, UInt64),
    latency_p95  AggregateFunction(quantile(0.95), UInt64),
    query_ms_p95 AggregateFunction(quantile(0.95), Float64),
    cost_sum     AggregateFunction(sum, Float64),
    users        AggregateFunction(uniq, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (hour, role, decision, rule_id)
TTL hour + INTERVAL 400 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS qg.mv_query_stats_hourly TO qg.query_stats_hourly AS
SELECT toStartOfHour(ts)                              AS hour,
       role,
       decision,
       ifNull(rule_id, '')                            AS rule_id,
       countState()                                   AS queries,
       avgState(decision_latency_us)                  AS latency_avg,
       quantileState(0.95)(decision_latency_us)       AS latency_p95,
       quantileState(0.95)(ifNull(query_latency_ms, 0)) AS query_ms_p95,
       sumState(ifNull(estimated_cost, 0))            AS cost_sum,
       uniqState(toString(user))                      AS users
FROM qg.query_events
GROUP BY hour, role, decision, rule_id;

-- Query shapes per day: how often, how expensive, who runs them.
CREATE TABLE IF NOT EXISTS qg.top_fingerprints_daily
(
    day            Date,
    fingerprint    String,
    sql_normalized SimpleAggregateFunction(any, String),
    statement_type SimpleAggregateFunction(any, String),
    executions     AggregateFunction(count),
    blocked        AggregateFunction(countIf, UInt8),
    cost_max       SimpleAggregateFunction(max, Float64),
    cost_sum       SimpleAggregateFunction(sum, Float64),
    query_ms_avg   AggregateFunction(avg, Float64),
    roles          AggregateFunction(groupUniqArray, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(day)
ORDER BY (day, fingerprint)
TTL day + INTERVAL 400 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS qg.mv_top_fingerprints TO qg.top_fingerprints_daily AS
SELECT toDate(ts)                                   AS day,
       fingerprint,
       any(sql_normalized)                          AS sql_normalized,
       any(toString(statement_type))                AS statement_type,
       countState()                                 AS executions,
       countIfState(decision = 'BLOCK')             AS blocked,
       max(ifNull(estimated_cost, 0))               AS cost_max,
       sum(ifNull(estimated_cost, 0))               AS cost_sum,
       avgState(ifNull(query_latency_ms, 0))        AS query_ms_avg,
       groupUniqArrayState(toString(role))          AS roles
FROM qg.query_events
GROUP BY day, fingerprint;
