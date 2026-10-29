"""
Generates the two Grafana dashboards. Edit this, run it, commit both the script and the JSON.

    python analytics/grafana/build_dashboards.py

Why a script: dashboard JSON is ~1000 lines of noise per dashboard and impossible to review
in a diff. This is ~200 lines of what actually matters (queries, layout, colors).

Visual language: three colors mean the same thing everywhere.
  ALLOW   muted teal   (most traffic; shouldn't shout)
  REWRITE amber
  BLOCK   vermilion    (the thing you came here to see)
"""
import json
from pathlib import Path

OUT = Path(__file__).parent / "dashboards"
CH = {"type": "grafana-clickhouse-datasource", "uid": "clickhouse"}
PROM = {"type": "prometheus", "uid": "prometheus"}

ALLOW, REWRITE, BLOCK = "#4E8C82", "#E0A526", "#E0492B"
INK, MUTED = "#E8E4DA", "#8A877F"
DECISION_COLORS = [("ALLOW", ALLOW), ("REWRITE", REWRITE), ("BLOCK", BLOCK)]

_id = 0


def nid():
    global _id
    _id += 1
    return _id


def color_overrides(names_colors):
    return [{"matcher": {"id": "byName", "options": n},
             "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}
            for n, c in names_colors]


def ch_target(sql, fmt="table"):
    # plugin v4: format 0 = time series, 1 = table
    return {"datasource": CH, "editorType": "sql", "rawSql": sql.strip(),
            "format": 0 if fmt == "timeseries" else 1,
            "queryType": "timeseries" if fmt == "timeseries" else "table", "refId": "A"}


def prom_target(expr, legend, ref="A"):
    return {"datasource": PROM, "expr": expr, "legendFormat": legend, "refId": ref, "range": True}


def panel(kind, title, x, y, w, h, targets, desc=None, **extra):
    p = {"id": nid(), "type": kind, "title": title, "gridPos": {"x": x, "y": y, "w": w, "h": h},
         "targets": targets, "datasource": targets[0]["datasource"] if targets else None,
         "fieldConfig": {"defaults": {}, "overrides": []}, "options": {}}
    if desc:
        p["description"] = desc
    for k, v in extra.items():
        p[k] = v
    return p


def text(title, md, x, y, w, h):
    return {"id": nid(), "type": "text", "title": title, "gridPos": {"x": x, "y": y, "w": w, "h": h},
            "options": {"mode": "markdown", "content": md.strip()}, "transparent": True}


def dashboard(uid, title, panels, refresh="10s", time_from="now-1h", tags=()):
    return {"uid": uid, "title": title, "tags": ["queryguard", *tags], "timezone": "utc",
            "schemaVersion": 39, "version": 1, "editable": True, "graphTooltip": 1,
            "refresh": refresh, "time": {"from": time_from, "to": "now"},
            "panels": panels, "templating": {"list": []}, "annotations": {"list": []}}


def stacked_bars(p, label=None):
    p["fieldConfig"]["defaults"]["custom"] = {"drawStyle": "bars", "fillOpacity": 85, "lineWidth": 0,
                                              "stacking": {"mode": "normal", "group": "A"}, "barAlignment": 0}
    if label:
        # long-format SQL results come back as "queries ALLOW"; name series by the label so the
        # color overrides (and the legend) just say ALLOW / REWRITE / BLOCK
        p["fieldConfig"]["defaults"]["displayName"] = "${__field.labels.%s}" % label
    p["fieldConfig"]["overrides"] = color_overrides(DECISION_COLORS)
    p["options"] = {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                    "tooltip": {"mode": "multi", "sort": "desc"}}
    return p


def big_stat(p, color, unit="short", decimals=None):
    p["fieldConfig"]["defaults"] = {"unit": unit, "color": {"mode": "fixed", "fixedColor": color}}
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    p["options"] = {"colorMode": "value", "graphMode": "none", "justifyMode": "auto", "textMode": "value",
                    "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}}
    return p


# ---------------------------------------------------------------------------------------------
# Query analytics (ClickHouse)
# ---------------------------------------------------------------------------------------------

_id = 0
analytics = [
    stacked_bars(panel("timeseries", "Every query, by what QueryGuard did with it", 0, 0, 17, 11, [ch_target("""
        SELECT $__timeInterval(ts) AS time, decision, count() AS queries
        FROM qg.query_events
        WHERE $__timeFilter(ts)
        GROUP BY time, decision
        ORDER BY time
    """, "timeseries")], desc="One bar per interval, stacked. ALLOW is most of it; watch the red."), label="decision"),
    big_stat(panel("stat", "Blocked", 17, 0, 7, 4, [ch_target("""
        SELECT countIf(decision = 'BLOCK') AS blocked FROM qg.query_events WHERE $__timeFilter(ts)
    """)]), BLOCK),
    big_stat(panel("stat", "Block rate", 17, 4, 4, 4, [ch_target("""
        SELECT countIf(decision = 'BLOCK') / greatest(count(), 1) AS rate FROM qg.query_events WHERE $__timeFilter(ts)
    """)]), INK, unit="percentunit", decimals=2),
    big_stat(panel("stat", "Rewritten", 21, 4, 3, 4, [ch_target("""
        SELECT countIf(decision = 'REWRITE') FROM qg.query_events WHERE $__timeFilter(ts)
    """)]), REWRITE),
    big_stat(panel("stat", "Events in ClickHouse (all time)", 17, 8, 7, 3, [ch_target("""
        SELECT count() FROM qg.query_events
    """)]), MUTED),

    panel("barchart", "What got blocked, by rule", 0, 11, 8, 11, [ch_target("""
        SELECT ifNull(rule_id, '?') AS rule, count() AS blocked
        FROM qg.query_events
        WHERE $__timeFilter(ts) AND decision = 'BLOCK'
        GROUP BY rule ORDER BY blocked DESC
    """)], desc="R = rule from the policy, M1 = masking guard, QG-* = fail-safe decisions (parse/cost/timeout).",
          options={"orientation": "horizontal", "showValue": "always", "legend": {"showLegend": False},
                   "xTickLabelRotation": 0, "barWidth": 0.7},
          fieldConfig={"defaults": {"color": {"mode": "fixed", "fixedColor": BLOCK}}, "overrides": []}),
    panel("table", "Most expensive query shapes, last 7 days", 8, 11, 16, 11, [ch_target("""
        SELECT any(sql_normalized)                              AS query,
               anyIf(statement_type, statement_type != 'OTHER') AS kind,
               countMerge(executions)                           AS runs,
               countIfMerge(blocked)                            AS blocked,
               round(max(cost_max))                             AS max_cost,
               round(avgMerge(query_ms_avg), 1)                 AS avg_ms,
               arrayStringConcat(groupUniqArrayMerge(roles), ' ') AS roles
        FROM qg.top_fingerprints_daily
        WHERE day >= today() - 7
        GROUP BY fingerprint
        ORDER BY max_cost DESC
        LIMIT 15
    """)], desc="From the top_fingerprints_daily rollup. Literals are already stripped; the proxy never logs values.",
          options={"showHeader": True, "cellHeight": "sm"},
          fieldConfig={"defaults": {"custom": {"align": "auto"}}, "overrides": [
              {"matcher": {"id": "byName", "options": "query"}, "properties": [{"id": "custom.width", "value": 520}]},
              {"matcher": {"id": "byName", "options": "blocked"}, "properties": [
                  {"id": "custom.cellOptions", "value": {"type": "color-text"}},
                  {"id": "color", "value": {"mode": "fixed", "fixedColor": BLOCK}}]}]}),

    panel("table", "Who gets blocked", 0, 22, 11, 9, [ch_target("""
        SELECT user, role,
               countIf(decision = 'BLOCK')                AS blocked,
               count()                                    AS total,
               round(100 * blocked / total, 2)            AS pct,
               topKIf(1)(rule_id, decision = 'BLOCK')[1]  AS usual_rule
        FROM qg.query_events
        WHERE $__timeFilter(ts)
        GROUP BY user, role
        ORDER BY blocked DESC
    """)], options={"showHeader": True, "cellHeight": "sm"}),
    panel("barchart", "Masked values served, by role and column", 11, 22, 7, 9, [ch_target("""
        SELECT concat(toString(role), ' / ', toString(col)) AS who, count() AS reads
        FROM qg.query_events
        ARRAY JOIN masked_columns AS col
        WHERE $__timeFilter(ts)
        GROUP BY who ORDER BY reads DESC
    """)], options={"orientation": "horizontal", "showValue": "always", "legend": {"showLegend": False}},
          fieldConfig={"defaults": {"color": {"mode": "fixed", "fixedColor": REWRITE}}, "overrides": []}),
    text("How to read this", """
**Bars** are every query that went through the proxy.

**M1** means someone tried to use a masked column in a way that would leak it
(`upper(email)`, `WHERE email LIKE ...`). Masking itself is in the right panel.

**QG-COST / QG-TIMEOUT** are the proxy deciding without full information.
If those climb, EXPLAIN is slow or Postgres is struggling.

Raw events live 90 days in `qg.query_events`. The two rollups keep 400.
""", 18, 22, 6, 9),
]

# ---------------------------------------------------------------------------------------------
# Proxy health (Prometheus)
# ---------------------------------------------------------------------------------------------

_id = 0
health = [
    stacked_bars(panel("timeseries", "Queries per second", 0, 0, 14, 9, [
        prom_target('sum by (decision) (rate(qg_queries_total[1m]))', "{{decision}}")])),
    panel("timeseries", "Decision latency", 14, 0, 10, 9, [
        prom_target('histogram_quantile(0.50, sum by (le) (rate(qg_decision_latency_seconds_bucket[1m])))', "p50"),
        prom_target('histogram_quantile(0.99, sum by (le) (rate(qg_decision_latency_seconds_bucket[1m])))', "p99", "B")],
          desc="Parse + rules + EXPLAIN when a rule needs it. Doesn't include the query itself.",
          fieldConfig={"defaults": {"unit": "s", "custom": {"lineWidth": 2, "fillOpacity": 8}},
                       "overrides": color_overrides([("p50", ALLOW), ("p99", REWRITE)])}),

    big_stat(panel("stat", "Client connections", 0, 9, 5, 5, [prom_target('sum(qg_connections_active)', "")]), INK),
    big_stat(panel("stat", "EXPLAIN cache hit rate", 5, 9, 5, 5, [prom_target(
        'sum(rate(cache_gets_total{cache="qg_explain_cache",result="hit"}[5m])) / sum(rate(cache_gets_total{cache="qg_explain_cache"}[5m]))', "")]),
        ALLOW, unit="percentunit", decimals=1),
    panel("stat", "Backend circuit", 10, 9, 4, 5, [prom_target('max(qg_backend_breaker_open)', "")],
          fieldConfig={"defaults": {"mappings": [
              {"type": "value", "options": {"0": {"text": "closed", "color": ALLOW},
                                            "0.5": {"text": "half-open", "color": REWRITE},
                                            "1": {"text": "OPEN", "color": BLOCK}}}],
              "color": {"mode": "thresholds"},
              "thresholds": {"mode": "absolute", "steps": [{"color": ALLOW, "value": None}, {"color": REWRITE, "value": 0.5},
                                                           {"color": BLOCK, "value": 1}]}}, "overrides": []},
          options={"colorMode": "background", "graphMode": "none", "textMode": "value",
                   "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}}),
    big_stat(panel("stat", "Policy version", 14, 9, 4, 5, [prom_target('max(qg_config_version)', "")]), INK),
    big_stat(panel("stat", "Last reload, ms after commit", 18, 9, 6, 5, [prom_target('max(qg_config_reload_lag_ms)', "")]), MUTED, unit="ms"),

    panel("timeseries", "Query events to Kafka", 0, 14, 12, 8, [
        prom_target('sum(rate(qg_events_sent_total[1m]))', "sent/s"),
        prom_target('sum(rate(qg_events_dropped_total[1m]))', "dropped/s", "B")],
          desc="Dropped means Kafka was down or slow and the bounded queue filled. Queries are never delayed for this.",
          fieldConfig={"defaults": {"custom": {"lineWidth": 2, "fillOpacity": 6}},
                       "overrides": color_overrides([("sent/s", ALLOW), ("dropped/s", BLOCK)])}),
    panel("timeseries", "Masked values/s and backend errors", 12, 14, 12, 8, [
        prom_target('sum(rate(qg_masked_values_total[1m]))', "masked values/s"),
        prom_target('sum(rate(qg_backend_errors_total[1m]))', "backend errors/s", "B"),
        prom_target('sum(rate(qg_connections_rejected_total[1m]))', "rejected connections/s", "C")],
          fieldConfig={"defaults": {"custom": {"lineWidth": 2, "fillOpacity": 0}},
                       "overrides": color_overrides([("masked values/s", REWRITE), ("backend errors/s", BLOCK),
                                                     ("rejected connections/s", MUTED)])}),
    panel("timeseries", "Proxy JVM heap", 0, 22, 24, 6, [
        prom_target('sum(jvm_memory_used_bytes{app="queryguard-proxy",area="heap"})', "used"),
        prom_target('sum(jvm_memory_committed_bytes{app="queryguard-proxy",area="heap"})', "committed", "B")],
          fieldConfig={"defaults": {"unit": "bytes", "custom": {"lineWidth": 1, "fillOpacity": 10}},
                       "overrides": color_overrides([("used", INK), ("committed", MUTED)])}),
]

OUT.mkdir(exist_ok=True)
(OUT / "query-analytics.json").write_text(json.dumps(
    dashboard("qg-analytics", "QueryGuard / what happened to every query", analytics, time_from="now-24h", tags=["clickhouse"]), indent=2))
(OUT / "proxy-health.json").write_text(json.dumps(
    dashboard("qg-health", "QueryGuard / proxy health", health, refresh="5s", time_from="now-30m", tags=["prometheus"]), indent=2))
print("wrote", *sorted(p.name for p in OUT.glob("*.json")))
