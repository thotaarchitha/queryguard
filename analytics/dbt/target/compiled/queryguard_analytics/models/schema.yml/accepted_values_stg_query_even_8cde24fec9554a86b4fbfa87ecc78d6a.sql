
    
    

with all_values as (

    select
        role as value_field,
        count(*) as n_records

    from `qg_staging`.`stg_query_events`
    group by role

)

select *
from all_values
where value_field not in (
    'admin','analyst','app','etl','app_readonly'
)


