package dev.queryguard.proxy.events;

import dev.queryguard.common.analysis.QueryInfo;
import dev.queryguard.common.rules.Decision;
import dev.queryguard.proxy.session.Session;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class EventFactory {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final String instance;

    public EventFactory(String instance) {
        this.instance = instance;
    }

    public QueryEvent create(Session s, QueryInfo q, Decision d, long decisionNanos) {
        QueryEvent e = new QueryEvent();
        e.eventId = newId();
        e.ts = now();
        e.proxyInstance = instance;
        e.user = s.user();
        e.role = s.role();
        e.database = s.database();
        e.clientIp = s.clientIp();
        e.application = s.application();
        e.statementType = statementType(q);
        e.fingerprint = q.fingerprint();
        e.sqlNormalized = truncate(q.normalized(), 4000);
        e.tables = java.util.List.copyOf(q.tables());
        e.decision = d.verdict().name();
        e.ruleId = d.ruleId();
        e.estimatedCost = d.estimatedCost();
        e.estimatedRows = d.estimatedRows();
        e.decisionLatencyUs = decisionNanos / 1000;
        return e;
    }

    /**
     * A decision that timed out never got as far as parsing, so the query has no statements.
     * The first keyword still says SELECT or UPDATE, which is better than OTHER on a dashboard.
     */
    private static String statementType(QueryInfo q) {
        if (!q.isEmpty()) {
            return q.primaryKind().name();
        }
        String n = q.normalized();
        int space = n.indexOf(' ');
        String first = space < 0 ? n : n.substring(0, space);
        return first.isEmpty() ? "OTHER" : dev.queryguard.common.analysis.StatementKind.fromKeyword(first).name();
    }

    public String newId() {
        // SecureRandom-backed randomUUID() contends under load; this doesn't need to be unguessable.
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long hi = (r.nextLong() & ~0xF000L) | 0x4000L;
        long lo = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(hi, lo).toString();
    }

    public String now() {
        return TS.format(java.time.Instant.now());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
