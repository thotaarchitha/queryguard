-- Every distinct query shape ever seen, with its whole history in one row.
-- "Which query is this, who runs it, how bad does it get" starts here.
select
    fingerprint,
    -- identical on every row of a fingerprint; min() just picks one, and unlike any() it picks
    -- the same one on every run
    min(sql_normalized)                                  as sql_normalized,
    -- NOT identical: some shapes carry two labels, from VACUUM being called ALTER before the
    -- MAINTENANCE kind existed, and from OTHER on timed-out decisions that never got
    -- classified. The newest label is the proxy's current, best answer. any() used to pick
    -- one at random.
    {{ latest('statement_type', 'ts') }}                 as statement_type,
    min(ts)                                              as first_seen,
    max(ts)                                              as last_seen,
    count(*)                                             as executions,
    {{ count_if('is_blocked') }}                         as times_blocked,
    round({{ count_if('is_blocked') }} / count(*), 4)    as block_rate,
    max(coalesce(estimated_cost, 0))                     as max_estimated_cost,
    {{ p95('coalesce(query_latency_ms, 0)') }}           as p95_query_ms,
    {{ sorted_distinct('role') }}                        as roles,
    count(distinct db_user)                              as distinct_users,
    {{ count_if('touched_masked_data') }} > 0            as reads_masked_data
from {{ ref('stg_query_events') }}
group by fingerprint
