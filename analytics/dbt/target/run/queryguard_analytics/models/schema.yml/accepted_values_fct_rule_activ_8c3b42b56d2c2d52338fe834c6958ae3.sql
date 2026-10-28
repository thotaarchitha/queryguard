
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      
    
    

with all_values as (

    select
        decision as value_field,
        count(*) as n_records

    from `qg_marts`.`fct_rule_activity_daily`
    group by decision

)

select *
from all_values
where value_field not in (
    'REWRITE','BLOCK'
)



    ) dbt_internal_test