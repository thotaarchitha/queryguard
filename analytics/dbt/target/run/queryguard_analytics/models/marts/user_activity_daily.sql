
  
    
    
    
        
         


        insert into `qg_marts`.`user_activity_daily`
        ("event_date", "db_user", "role", "queries", "blocked", "policy_blocks", "masking_guard_hits", "masked_reads", "database_errors", "rows_returned")-- Per user per day. Not a "risk score": just the counts someone reviewing access would
-- want side by side, with masking-guard hits separated from ordinary policy blocks, because
-- trying to unmask data is a different conversation than forgetting a WHERE clause.
select
    event_date,
    db_user,
    role,
    count()                                              as queries,
    countIf(is_blocked)                                  as blocked,
    countIf(is_blocked and rule_family = 'policy')       as policy_blocks,
    countIf(is_blocked and rule_family = 'masking')      as masking_guard_hits,
    countIf(touched_masked_data)                         as masked_reads,
    countIf(error_code != '' and not is_blocked)         as database_errors,
    sum(ifNull(rows_returned, 0))                        as rows_returned
from `qg_staging`.`stg_query_events`
group by event_date, db_user, role
  