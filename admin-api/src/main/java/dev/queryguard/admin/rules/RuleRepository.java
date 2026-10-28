package dev.queryguard.admin.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RuleRepository {

    public record RuleRow(String id, int version, boolean enabled, JsonNode body, String createdBy,
                          OffsetDateTime createdAt, String updatedBy, OffsetDateTime updatedAt) {
    }

    public record VersionRow(int version, boolean enabled, JsonNode body, String changedBy, OffsetDateTime changedAt) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RuleRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<RuleRow> findAll() {
        return jdbc.sql("SELECT id, version, enabled, body::text, created_by, created_at, updated_by, updated_at FROM rules ORDER BY (body->>'priority')::int NULLS LAST, id")
                .query((rs, i) -> new RuleRow(rs.getString(1), rs.getInt(2), rs.getBoolean(3), json(rs.getString(4)),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getString(7), rs.getObject(8, OffsetDateTime.class)))
                .list();
    }

    public Optional<RuleRow> find(String id) {
        return jdbc.sql("SELECT id, version, enabled, body::text, created_by, created_at, updated_by, updated_at FROM rules WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new RuleRow(rs.getString(1), rs.getInt(2), rs.getBoolean(3), json(rs.getString(4)),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getString(7), rs.getObject(8, OffsetDateTime.class)))
                .optional();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM rules").query(Long.class).single();
    }

    public void insert(String id, JsonNode body, boolean enabled, String actor) {
        jdbc.sql("""
                        INSERT INTO rules (id, version, body, enabled, created_by, updated_by)
                        VALUES (:id, 1, CAST(:body AS jsonb), :enabled, :actor, :actor)
                        """)
                .param("id", id).param("body", body.toString()).param("enabled", enabled).param("actor", actor)
                .update();
        snapshot(id);
    }

    /** Optimistic lock: only updates if nobody else changed the rule since the caller read it. */
    public boolean update(String id, JsonNode body, boolean enabled, int expectedVersion, String actor) {
        int n = jdbc.sql("""
                        UPDATE rules SET body = CAST(:body AS jsonb), enabled = :enabled, version = version + 1,
                                         updated_by = :actor, updated_at = now()
                        WHERE id = :id AND version = :expected
                        """)
                .param("id", id).param("body", body.toString()).param("enabled", enabled)
                .param("actor", actor).param("expected", expectedVersion)
                .update();
        if (n == 1) {
            snapshot(id);
        }
        return n == 1;
    }

    public List<VersionRow> history(String id) {
        return jdbc.sql("SELECT version, enabled, body::text, changed_by, changed_at FROM rule_versions WHERE rule_id = :id ORDER BY version DESC")
                .param("id", id)
                .query((rs, i) -> new VersionRow(rs.getInt(1), rs.getBoolean(2), json(rs.getString(3)), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class)))
                .list();
    }

    private void snapshot(String id) {
        jdbc.sql("""
                        INSERT INTO rule_versions (rule_id, version, body, enabled, changed_by)
                        SELECT id, version, body, enabled, updated_by FROM rules WHERE id = :id
                        """)
                .param("id", id).update();
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
