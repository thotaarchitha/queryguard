-- One cleaned row per query. The only place downstream models read raw events from.
-- deduped_events(): the raw table can hold Kafka redeliveries (ClickHouse) or a file loaded
-- twice (Snowflake). How each warehouse gets rid of them is in macros/cross_warehouse.sql.
select
    event_id,
    ts,
    cast(ts as date)                                   as event_date,
    CAST(user AS String)                               as db_user,
    CAST(role AS String)                               as role,
    CAST({{ database_column() }} AS String)            as database_name,
    CAST(application AS String)                        as application,
    CAST(statement_type AS String)                     as statement_type,
    fingerprint,
    sql_normalized,
    CAST(decision AS String)                           as decision,
    -- R* come from the policy, M1 is the masking guard, QG-* are the proxy deciding without
    -- full information (parse failure, EXPLAIN down, timeout). Worth telling apart.
    coalesce(rule_id, '')                              as rule_id,
    case
        when rule_id is null       then 'none'
        when rule_id like 'QG-%'   then 'failsafe'
        when rule_id like 'M%'     then 'masking'
        else 'policy'
    end                                                as rule_family,
    CAST(decision AS String) = 'BLOCK'                 as is_blocked,
    estimated_cost,
    estimated_rows,
    decision_latency_us,
    query_latency_ms,
    rows_returned,
    {{ array_length('masked_columns') }} > 0           as touched_masked_data,
    masked_columns,
    error_code
from {{ deduped_events() }}
