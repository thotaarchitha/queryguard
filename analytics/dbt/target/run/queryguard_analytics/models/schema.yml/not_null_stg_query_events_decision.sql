
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      
    
    



select decision
from `qg_staging`.`stg_query_events`
where decision is null



    ) dbt_internal_test