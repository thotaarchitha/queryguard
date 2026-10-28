package dev.queryguard.common.masking;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Locale;

/**
 * {@code customers.email} is masked for {@code roles} (if given), or for everyone
 * except {@code except_roles} (if given), or for everyone (if neither).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MaskRule(String table, String column, MaskStrategy strategy, List<String> roles, List<String> exceptRoles) {

    public boolean appliesTo(String role) {
        if (roles != null && !roles.isEmpty()) {
            return roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
        }
        if (exceptRoles != null) {
            return exceptRoles.stream().noneMatch(r -> r.equalsIgnoreCase(role));
        }
        return true;
    }

    /** Table name without schema, lowercased. */
    public String tableName() {
        String t = table.toLowerCase(Locale.ROOT);
        int dot = t.lastIndexOf('.');
        return dot < 0 ? t : t.substring(dot + 1);
    }

    public String schemaName() {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? "public" : table.substring(0, dot).toLowerCase(Locale.ROOT);
    }

    public String columnName() {
        return column.toLowerCase(Locale.ROOT);
    }
}
