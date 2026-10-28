package dev.queryguard.common.cost;

/**
 * The top node of an EXPLAIN plan: planner cost units and estimated rows.
 * {@code source} is "explain" normally, or "table-stats" when EXPLAIN was unavailable and
 * this is a full-scan upper bound computed from pg_class.
 */
public record CostEstimate(double totalCost, double planRows, String source) {

    public CostEstimate(double totalCost, double planRows) {
        this(totalCost, planRows, "explain");
    }

    public boolean fromTableStats() {
        return "table-stats".equals(source);
    }
}
