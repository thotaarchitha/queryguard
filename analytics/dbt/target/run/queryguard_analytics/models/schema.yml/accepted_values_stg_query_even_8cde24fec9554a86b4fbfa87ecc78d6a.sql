
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      
    
    

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



    ) dbt_internal_test