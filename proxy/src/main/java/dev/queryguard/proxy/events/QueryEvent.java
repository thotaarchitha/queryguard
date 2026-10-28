package dev.queryguard.proxy.events;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.util.List;

/**
 * One row in ClickHouse's query_events. Mutable on purpose: it's created when the decision is
 * made and finished when the result comes back (latency, rows), then published once.
 *
 * <p>Merged into one event instead of "decision" + "completion" events. Two events would mean a
 * join in ClickHouse for every dashboard panel, and the only thing gained is seeing in-flight
 * queries, which Prometheus already shows.
 *
 * <p>Only normalized SQL ({@code where id = ?}) is ever stored, never the literal values.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class QueryEvent {

    String eventId;
    String ts;               // "2026-11-20 14:03:22.118", UTC; ClickHouse parses this format natively
    String proxyInstance;
    String user;
    String role;
    String database;
    String clientIp;
    String application;
    String statementType;
    String fingerprint;
    String sqlNormalized;
    List<String> tables;
    String decision;
    String ruleId;
    Double estimatedCost;
    Double estimatedRows;
    long decisionLatencyUs;
    Double queryLatencyMs;
    Long rowsReturned;
    List<String> maskedColumns = List.of();
    String errorCode;

    // not serialized: when the query was sent to Postgres
    transient long startedNanos;

    public QueryEvent() {
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String decision() {
        return decision;
    }

    public void started(long nanos) {
        this.startedNanos = nanos;
    }

    public void finish(long nowNanos, long rows, String error) {
        this.queryLatencyMs = startedNanos == 0 ? null : (nowNanos - startedNanos) / 1_000_000.0;
        this.rowsReturned = rows;
        this.errorCode = error;
    }

    public void addMaskedColumns(List<String> columns) {
        if (columns.isEmpty()) {
            return;
        }
        if (maskedColumns.isEmpty()) {
            maskedColumns = List.copyOf(columns);
        } else {
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(maskedColumns);
            all.addAll(columns);
            maskedColumns = List.copyOf(all);
        }
    }

    /** A copy for the next Execute of the same prepared statement. */
    public QueryEvent copyForExecute(String id, String timestamp) {
        QueryEvent e = new QueryEvent();
        e.eventId = id;
        e.ts = timestamp;
        e.proxyInstance = proxyInstance;
        e.user = user;
        e.role = role;
        e.database = database;
        e.clientIp = clientIp;
        e.application = application;
        e.statementType = statementType;
        e.fingerprint = fingerprint;
        e.sqlNormalized = sqlNormalized;
        e.tables = tables;
        e.decision = decision;
        e.ruleId = ruleId;
        e.estimatedCost = estimatedCost;
        e.estimatedRows = estimatedRows;
        e.decisionLatencyUs = 0; // decided at Parse time; executes reuse it for free
        return e;
    }
}
