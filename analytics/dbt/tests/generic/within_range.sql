{#- Range check. Not worth pulling in dbt_utils for one test. -#}
{% test within_range(model, column_name, min, max) %}
select *
from {{ model }}
where {{ column_name }} < {{ min }} or {{ column_name }} > {{ max }}
{% endtest %}
