
    
    

with child as (
    select fingerprint as from_field
    from `qg_staging`.`stg_query_events`
    where fingerprint is not null
),

parent as (
    select fingerprint as to_field
    from `qg_marts`.`dim_query_shapes`
)

select
    from_field

from child
left join parent
    on child.from_field = parent.to_field

where parent.to_field is null
-- end_of_sql
settings join_use_nulls = 1


