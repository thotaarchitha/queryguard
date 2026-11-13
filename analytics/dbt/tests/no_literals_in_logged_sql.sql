-- Privacy check on the pipeline itself: the proxy is supposed to log normalized SQL only.
-- A quoted string or a run of 4+ digits standing on its own means a literal value
-- (an email, a card number) leaked into the analytics store. Any row returned fails the test.
-- The character classes stand in for \b, see regex_contains() for why.
select fingerprint, sql_normalized
from {{ ref('stg_query_events') }}
where sql_normalized like '%''%'
   or {{ regex_contains('sql_normalized', '(^|[^0-9A-Za-z_])[0-9]{4,}([^0-9A-Za-z_]|$)') }}
limit 20
