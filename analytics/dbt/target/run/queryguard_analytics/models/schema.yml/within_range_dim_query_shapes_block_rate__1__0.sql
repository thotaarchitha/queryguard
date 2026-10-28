
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      
select *
from `qg_marts`.`dim_query_shapes`
where block_rate < 0 or block_rate > 1

    ) dbt_internal_test