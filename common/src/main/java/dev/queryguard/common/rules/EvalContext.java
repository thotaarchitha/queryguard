package dev.queryguard.common.rules;

import dev.queryguard.common.cost.CostLookup;
import java.time.Instant;

public record EvalContext(String user, String role, Instant now, CostLookup costs) {
}
