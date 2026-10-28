
    
    

with all_values as (

    select
        rule_family as value_field,
        count(*) as n_records

    from `qg_staging`.`stg_query_events`
    group by rule_family

)

select *
from all_values
where value_field not in (
    'none','policy','masking','failsafe'
)


