
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      
    
    



select db_user
from `qg_marts`.`user_activity_daily`
where db_user is null



    ) dbt_internal_test