
    select
      count(*) as failures,
      count(*) != 0 as should_warn,
      count(*) != 0 as should_error
    from (
      -- Privacy check on the pipeline itself: the proxy is supposed to log normalized SQL only.
-- A quoted string or a long run of digits in sql_normalized means a literal value
-- (an email, a card number) leaked into the analytics store. Any row returned fails the test.
select fingerprint, sql_normalized
from `qg_staging`.`stg_query_events`
where position(sql_normalized, '''') > 0
   or match(sql_normalized, '\\b[0-9]{4,}\\b')
limit 20
    ) dbt_internal_test