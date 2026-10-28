package dev.queryguard.common.config;

/**
 * What to do when we can't make an informed decision.
 *
 * @param onParseError  unparsed statements: ALLOW, BLOCK, or ALLOW_READS (reads pass, anything else is blocked)
 * @param onCostError   EXPLAIN failed or timed out and a rule needed the cost: ALLOW, BLOCK, or ESTIMATE
 *                      (fall back to a full-scan upper bound from table statistics; ALLOW if those are missing too)
 * @param explainTimeoutMs statement_timeout on the EXPLAIN connection
 * @param decisionTimeoutMs ceiling on parse + rules + EXPLAIN for one query
 */
public record Settings(FailMode onParseError, FailMode onCostError, Integer explainTimeoutMs, Integer decisionTimeoutMs) {

    public enum FailMode { ALLOW, BLOCK, ALLOW_READS, ESTIMATE }

    public static Settings defaults() {
        return new Settings(FailMode.ALLOW_READS, FailMode.ESTIMATE, 200, 1000);
    }

    public Settings withDefaults() {
        Settings d = defaults();
        return new Settings(
                onParseError == null ? d.onParseError : onParseError,
                onCostError == null ? d.onCostError : onCostError,
                explainTimeoutMs == null ? d.explainTimeoutMs : explainTimeoutMs,
                decisionTimeoutMs == null ? d.decisionTimeoutMs : decisionTimeoutMs);
    }
}
