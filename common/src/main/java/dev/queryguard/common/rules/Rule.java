package dev.queryguard.common.rules;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One rule, exactly as it appears in YAML and in the admin API's JSON bodies.
 * Fields in {@link When} are all optional; a null means "don't care".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Rule(
        String id,
        String name,
        Integer priority,
        Boolean enabled,
        When when,
        Action action,
        String sqlstate,
        String message,
        Rewrite rewrite) {

    public enum Action { BLOCK, REWRITE }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record When(
            List<String> roles,
            List<String> rolesExcept,
            List<String> users,
            List<String> statement,
            Boolean hasWhere,
            Boolean hasLimit,
            Boolean selectStar,
            Boolean hasCrossJoin,
            List<String> tablesAny,
            List<String> functionsAny,
            Double estimatedCostGt,
            Double estimatedRowsGt,
            MaintenanceWindow outsideWindow) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rewrite(Integer addLimit) {
    }

    public int effectivePriority() {
        return priority == null ? 100 : priority;
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public String effectiveSqlstate() {
        return sqlstate == null || sqlstate.isBlank() ? "42501" : sqlstate;
    }

    public Rule withEnabled(boolean value) {
        return new Rule(id, name, priority, value, when, action, sqlstate, message, rewrite);
    }

    public Rule withId(String newId) {
        return new Rule(newId, name, priority, enabled, when, action, sqlstate, message, rewrite);
    }
}
