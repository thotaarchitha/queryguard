-- Per user per day. Not a "risk score": just the counts someone reviewing access would
-- want side by side, with masking-guard hits separated from ordinary policy blocks, because
-- trying to unmask data is a different conversation than forgetting a WHERE clause.
select
    event_date,
    db_user,
    role,
    count(*)                                                        as queries,
    {{ count_if('is_blocked') }}                                    as blocked,
    {{ count_if("is_blocked and rule_family = 'policy'") }}         as policy_blocks,
    {{ count_if("is_blocked and rule_family = 'masking'") }}        as masking_guard_hits,
    {{ count_if('touched_masked_data') }}                           as masked_reads,
    {{ count_if("error_code != '' and not is_blocked") }}           as database_errors,
    sum(coalesce(rows_returned, 0))                                 as rows_returned
from {{ ref('stg_query_events') }}
group by event_date, db_user, role
