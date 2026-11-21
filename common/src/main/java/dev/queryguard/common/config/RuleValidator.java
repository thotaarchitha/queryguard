package dev.queryguard.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.queryguard.common.rules.MaintenanceWindow;
import dev.queryguard.common.rules.Rule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks a rule body against {@code rule.schema.json}, plus a few things a schema
 * can't express cleanly (a REWRITE must say how, a window's times and zone must parse).
 */
public final class RuleValidator {

    private static final JsonSchema SCHEMA = loadSchema();

    private RuleValidator() {
    }

    public static List<String> validate(JsonNode body) {
        List<String> errors = new ArrayList<>();
        Set<ValidationMessage> messages = SCHEMA.validate(body);
        messages.forEach(m -> errors.add(m.getMessage()));
        if (!errors.isEmpty()) {
            return errors;
        }
        Rule rule;
        try {
            rule = ConfigMapper.json().treeToValue(body, Rule.class);
        } catch (IOException e) {
            errors.add("couldn't read rule: " + e.getMessage());
            return errors;
        }
        if (rule.action() == Rule.Action.REWRITE && (rule.rewrite() == null || rule.rewrite().addLimit() == null)) {
            errors.add("$.rewrite.add_limit: a REWRITE rule needs add_limit");
        }
        if (rule.when() != null && rule.when().roles() != null && rule.when().rolesExcept() != null) {
            errors.add("$.when: use roles or roles_except, not both");
        }
        if (rule.when() != null && rule.when().outsideWindow() != null) {
            checkWindow(rule.when().outsideWindow(), errors);
        }
        return errors;
    }

    // The schema only checks the shape of these. A bad value would otherwise get saved and then
    // throw inside the proxy every time the rule is evaluated.
    private static void checkWindow(MaintenanceWindow w, List<String> errors) {
        checkTime("from", w.from(), errors);
        checkTime("to", w.to(), errors);
        if (w.tz() != null) {
            try {
                ZoneId.of(w.tz());
            } catch (DateTimeException e) {
                errors.add("$.when.outside_window.tz: unknown time zone '" + w.tz()
                        + "' (use an IANA name like 'America/New_York' or 'UTC')");
            }
        }
    }

    private static void checkTime(String field, String value, List<String> errors) {
        if (value == null) {
            return;
        }
        try {
            LocalTime.parse(value);
        } catch (DateTimeException e) {
            errors.add("$.when.outside_window." + field + ": '" + value + "' isn't a valid time (00:00 to 23:59)");
        }
    }

    private static JsonSchema loadSchema() {
        try (InputStream in = RuleValidator.class.getResourceAsStream("/rule.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("rule.schema.json missing from classpath");
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
