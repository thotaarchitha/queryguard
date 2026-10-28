
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      
    
    



select fingerprint
from `qg_staging`.`stg_query_events`
where fingerprint is null



    ) dbt_internal_test