
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      
    
    



select fingerprint
from `qg_marts`.`dim_query_shapes`
where fingerprint is null



    ) dbt_internal_test