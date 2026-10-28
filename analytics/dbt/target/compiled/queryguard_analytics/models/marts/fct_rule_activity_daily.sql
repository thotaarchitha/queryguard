-- How often each rule fired, per day and role. The "is the policy too strict?" table: a rule
-- that blocks a big share of one role's traffic is either catching real problems or in the way.
select
    event_date,
    role,
    rule_id,
    rule_family,
    decision,
    count()                                    as events,
    uniqExact(db_user)                         as distinct_users,
    uniqExact(fingerprint)                     as distinct_query_shapes,
    round(avg(decision_latency_us), 1)         as avg_decision_us,
    sum(ifNull(estimated_cost, 0))             as total_estimated_cost
from `qg_staging`.`stg_query_events`
where decision != 'ALLOW'
group by event_date, role, rule_id, rule_family, decision