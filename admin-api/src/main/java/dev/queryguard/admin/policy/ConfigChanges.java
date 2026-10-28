package dev.queryguard.admin.policy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every write goes through here, inside the caller's transaction: audit row, version bump,
 * and pg_notify. Postgres holds the notification until COMMIT, so a proxy can never be told
 * about a change it can't see yet, and a rolled-back change never notifies anyone.
 */
@Component
public class ConfigChanges {

    public static final String CHANNEL = "qg_rules_changed";

    private final JdbcClient jdbc;

    public ConfigChanges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long record(String actor, String action, String entity, String entityId, JsonNode before, JsonNode after) {
        jdbc.sql("""
                        INSERT INTO audit_log (actor, action, entity, entity_id, before, after)
                        VALUES (:actor, :action, :entity, :id, CAST(:before AS jsonb), CAST(:after AS jsonb))
                        """)
                .param("actor", actor)
                .param("action", action)
                .param("entity", entity)
                .param("id", entityId)
                .param("before", before == null ? null : before.toString())
                .param("after", after == null ? null : after.toString())
                .update();
        long version = jdbc.sql("UPDATE config_version SET version = version + 1 RETURNING version")
                .query(Long.class).single();
        // version:millis, so the proxy can report how long the change took to go live
        jdbc.sql("SELECT pg_notify(:channel, :payload)")
                .param("channel", CHANNEL)
                .param("payload", version + ":" + System.currentTimeMillis())
                .query().singleRow();
        return version;
    }

    public long currentVersion() {
        return jdbc.sql("SELECT version FROM config_version").query(Long.class).single();
    }
}
