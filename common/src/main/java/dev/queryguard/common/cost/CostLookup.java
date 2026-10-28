package dev.queryguard.common.cost;

import dev.queryguard.common.analysis.StatementInfo;

@FunctionalInterface
public interface CostLookup {

    CostEstimate estimate(StatementInfo statement) throws CostUnavailableException;

    CostLookup NONE = st -> {
        throw new CostUnavailableException("no cost estimator configured");
    };
}
