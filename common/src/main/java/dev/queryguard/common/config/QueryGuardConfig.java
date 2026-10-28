package dev.queryguard.common.config;

import dev.queryguard.common.masking.MaskRule;
import dev.queryguard.common.rules.Rule;
import java.util.List;
import java.util.Map;

/** The whole policy: who has which role, the rules, and the masking policy. */
public record QueryGuardConfig(
        Map<String, String> roleMapping,
        String defaultRole,
        Settings settings,
        List<Rule> rules,
        List<MaskRule> masking) {

    public QueryGuardConfig {
        roleMapping = roleMapping == null ? Map.of() : Map.copyOf(roleMapping);
        defaultRole = defaultRole == null ? "app" : defaultRole;
        settings = (settings == null ? Settings.defaults() : settings).withDefaults();
        rules = rules == null ? List.of() : List.copyOf(rules);
        masking = masking == null ? List.of() : List.copyOf(masking);
    }

    public String roleFor(String user) {
        if (user == null) {
            return defaultRole;
        }
        return roleMapping.getOrDefault(user, defaultRole);
    }

    public QueryGuardConfig withPolicy(Map<String, String> newRoles, List<Rule> newRules, List<MaskRule> newMasking) {
        return new QueryGuardConfig(newRoles, defaultRole, settings, newRules, newMasking);
    }
}
