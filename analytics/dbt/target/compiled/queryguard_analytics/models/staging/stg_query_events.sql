-- One cleaned row per query. The only place downstream models read raw events from.
-- FINAL: the raw table can hold Kafka redeliveries until ClickHouse merges them (see the DDL).
select
    event_id,
    ts,
    toDate(ts)                                         as event_date,
    CAST(user AS String)                                     as db_user,
    CAST(role AS String)                                     as role,
    CAST(database AS String)                                 as database_name,
    CAST(application AS String)                              as application,
    CAST(statement_type AS String)                           as statement_type,
    fingerprint,
    sql_normalized,
    CAST(decision AS String)                                 as decision,
    -- R* come from the policy, M1 is the masking guard, QG-* are the proxy deciding without
    -- full information (parse failure, EXPLAIN down, timeout). Worth telling apart.
    ifNull(rule_id, '')                                as rule_id,
    multiIf(rule_id is null, 'none',
            startsWith(rule_id, 'QG-'), 'failsafe',
            startsWith(rule_id, 'M'), 'masking',
            'policy')                                  as rule_family,
    CAST(decision AS String) = 'BLOCK'                       as is_blocked,
    estimated_cost,
    estimated_rows,
    decision_latency_us,
    query_latency_ms,
    rows_returned,
    length(masked_columns) > 0                         as touched_masked_data,
    masked_columns,
    error_code
from `qg`.`query_events` final