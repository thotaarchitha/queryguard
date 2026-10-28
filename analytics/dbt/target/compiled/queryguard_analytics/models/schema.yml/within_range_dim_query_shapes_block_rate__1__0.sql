
select *
from `qg_marts`.`dim_query_shapes`
where block_rate < 0 or block_rate > 1
