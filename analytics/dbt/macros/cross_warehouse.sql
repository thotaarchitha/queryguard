{#-
  The few places ClickHouse and Snowflake disagree. Everything else in the models is SQL both
  accept (count(distinct), coalesce, case, like, cast), and I'd rather keep it that way than wrap
  every function: a model full of macros hides what it actually computes.

  Only clickhouse and snowflake are implemented. Any other adapter fails at compile time with
  dbt's "no macro found" error, which is what I want.
-#}


{#- Raw events, one row per event_id.
    ClickHouse: the table is a ReplacingMergeTree, so Kafka redeliveries collapse in the
    background and FINAL forces that at read time.
    Snowflake: no such engine, and loading the same export twice is easy, so dedupe here.
    ingested_at breaks ties the same way the ReplacingMergeTree version column does. -#}
{% macro deduped_events() %}{{ return(adapter.dispatch('deduped_events')()) }}{% endmacro %}

{% macro clickhouse__deduped_events() -%}
{{ source('qg', 'query_events') }} final
{%- endmacro %}

{% macro snowflake__deduped_events() -%}
(
    select *
    from {{ source('qg', 'query_events') }}
    qualify row_number() over (partition by event_id order by ingested_at desc) = 1
)
{%- endmacro %}


{#- DATABASE is a reserved word in Snowflake, so the raw column has to be quoted there. -#}
{% macro database_column() %}{{ return(adapter.dispatch('database_column')()) }}{% endmacro %}

{% macro clickhouse__database_column() %}database{% endmacro %}
{% macro snowflake__database_column() %}"DATABASE"{% endmacro %}


{% macro count_if(condition) %}{{ return(adapter.dispatch('count_if')(condition)) }}{% endmacro %}

{% macro clickhouse__count_if(condition) %}countIf({{ condition }}){% endmacro %}
{% macro snowflake__count_if(condition) %}count_if({{ condition }}){% endmacro %}


{% macro array_length(column) %}{{ return(adapter.dispatch('array_length')(column)) }}{% endmacro %}

{% macro clickhouse__array_length(column) %}length({{ column }}){% endmacro %}
{% macro snowflake__array_length(column) %}array_size({{ column }}){% endmacro %}


{#- Distinct values of a column, sorted, as one array. -#}
{% macro sorted_distinct(column) %}{{ return(adapter.dispatch('sorted_distinct')(column)) }}{% endmacro %}

{% macro clickhouse__sorted_distinct(column) %}arraySort(groupUniqArray({{ column }})){% endmacro %}
{% macro snowflake__sorted_distinct(column) %}array_sort(array_unique_agg({{ column }})){% endmacro %}


{#- Value of `column` on the row with the highest `by`. -#}
{% macro latest(column, by) %}{{ return(adapter.dispatch('latest')(column, by)) }}{% endmacro %}

{% macro clickhouse__latest(column, by) %}argMax({{ column }}, {{ by }}){% endmacro %}
{% macro snowflake__latest(column, by) %}max_by({{ column }}, {{ by }}){% endmacro %}


{#- Both are approximate (ClickHouse samples, Snowflake uses t-digest), so the two warehouses
    won't report identical p95s for the same data. Close, not equal. -#}
{% macro p95(expr) %}{{ return(adapter.dispatch('p95')(expr)) }}{% endmacro %}

{% macro clickhouse__p95(expr) %}quantile(0.95)({{ expr }}){% endmacro %}
{% macro snowflake__p95(expr) %}approx_percentile({{ expr }}, 0.95){% endmacro %}


{#- Unanchored regex match. Keep patterns free of backslashes: ClickHouse and Snowflake both
    unescape string literals, and \b in particular isn't something I trust to mean the same
    thing in RE2 and Snowflake's POSIX flavour. -#}
{% macro regex_contains(column, pattern) %}{{ return(adapter.dispatch('regex_contains')(column, pattern)) }}{% endmacro %}

{% macro clickhouse__regex_contains(column, pattern) %}match({{ column }}, '{{ pattern }}'){% endmacro %}
{% macro snowflake__regex_contains(column, pattern) %}regexp_instr({{ column }}, '{{ pattern }}') > 0{% endmacro %}
