package dev.queryguard.common.cost;

/** The top node of an EXPLAIN plan: planner cost units and estimated rows. */
public record CostEstimate(double totalCost, double planRows) {
}
