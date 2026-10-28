
    
    

select
    fingerprint as unique_field,
    count(*) as n_records

from `qg_marts`.`dim_query_shapes`
where fingerprint is not null
group by fingerprint
having count(*) > 1


