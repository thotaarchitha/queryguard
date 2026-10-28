package dev.queryguard.proxy.config;

import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.rules.RuleEngine;

/**
 * An immutable snapshot of the policy. Queries grab one at the start of their decision
 * and use it to the end, so a reload mid-query can't mix old and new rules.
 */
public record ActivePolicy(long version, QueryGuardConfig config, RuleEngine engine, String source) {

    public static ActivePolicy of(long version, QueryGuardConfig config, String source) {
        return new ActivePolicy(version, config, new RuleEngine(config), source);
    }
}
