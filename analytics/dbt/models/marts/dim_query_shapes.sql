-- Every distinct query shape ever seen, with its whole history in one row.
-- "Which query is this, who runs it, how bad does it get" starts here.
select
    fingerprint,
    any(sql_normalized)                                  as sql_normalized,
    any(statement_type)                                  as statement_type,
    min(ts)                                              as first_seen,
    max(ts)                                              as last_seen,
    count()                                              as executions,
    countIf(is_blocked)                                  as times_blocked,
    round(countIf(is_blocked) / count(), 4)              as block_rate,
    max(ifNull(estimated_cost, 0))                       as max_estimated_cost,
    quantile(0.95)(ifNull(query_latency_ms, 0))          as p95_query_ms,
    arraySort(groupUniqArray(role))                      as roles,
    uniqExact(db_user)                                   as distinct_users,
    max(touched_masked_data)                             as reads_masked_data
from {{ ref('stg_query_events') }}
group by fingerprint
