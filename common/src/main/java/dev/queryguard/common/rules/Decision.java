package dev.queryguard.common.rules;

import java.util.List;

public record Decision(
        Verdict verdict,
        String ruleId,
        String sqlstate,
        String message,
        String rewrittenSql,
        List<String> appliedRules,
        Double estimatedCost,
        Double estimatedRows) {

    public enum Verdict { ALLOW, REWRITE, BLOCK }

    public static Decision allow(Double cost, Double rows) {
        return new Decision(Verdict.ALLOW, null, null, null, null, List.of(), cost, rows);
    }

    public static Decision block(String ruleId, String sqlstate, String message, Double cost, Double rows) {
        return new Decision(Verdict.BLOCK, ruleId, sqlstate, message, null, List.of(ruleId), cost, rows);
    }

    public static Decision rewrite(List<String> rules, String sql, Double cost, Double rows) {
        return new Decision(Verdict.REWRITE, rules.get(0), null, null, sql, List.copyOf(rules), cost, rows);
    }

    public boolean blocked() {
        return verdict == Verdict.BLOCK;
    }
}
