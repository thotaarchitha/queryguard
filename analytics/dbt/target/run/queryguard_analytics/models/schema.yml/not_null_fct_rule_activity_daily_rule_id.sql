
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      
    
    



select rule_id
from `qg_marts`.`fct_rule_activity_daily`
where rule_id is null



    ) dbt_internal_test