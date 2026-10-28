
    
    

with all_values as (

    select
        decision as value_field,
        count(*) as n_records

    from `qg_staging`.`stg_query_events`
    group by decision

)

select *
from all_values
where value_field not in (
    'ALLOW','REWRITE','BLOCK'
)


