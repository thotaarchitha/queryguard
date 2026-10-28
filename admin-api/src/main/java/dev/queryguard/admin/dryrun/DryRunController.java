package dev.queryguard.admin.dryrun;

import dev.queryguard.admin.AdminProperties;
import dev.queryguard.admin.policy.PolicyStore;
import dev.queryguard.admin.seed.SeedPolicy;
import dev.queryguard.common.analysis.QueryAnalyzer;
import dev.queryguard.common.analysis.QueryInfo;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.cost.ExplainCostEstimator;
import dev.queryguard.common.cost.PgPools;
import dev.queryguard.common.rules.Decision;
import dev.queryguard.common.rules.EvalContext;
import dev.queryguard.common.rules.RuleEngine;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * "What would QueryGuard do with this?" without running it. Uses the same engine, the same
 * policy from the config DB, and real EXPLAIN against the shop database.
 */
@RestController
public class DryRunController {

    public record TestRequest(@NotBlank @Size(max = 100_000) String sql, @NotBlank String user) {
    }

    public record StatementSummary(String kind, boolean parsed, List<String> tables) {
    }

    public record TestResponse(String user, String role, String decision, String ruleId, String sqlstate, String message,
                               String rewrittenSql, Double estimatedCost, Double estimatedRows, String fingerprint,
                               String normalizedSql, List<StatementSummary> statements) {
    }

    private final PolicyStore store;
    private final SeedPolicy seed;
    private final QueryAnalyzer analyzer = new QueryAnalyzer();
    private final PgPools pools;
    private final ExplainCostEstimator estimator;
    private final dev.queryguard.common.cost.TableStats tableStats;
    private final String database;

    public DryRunController(PolicyStore store, SeedPolicy seed, AdminProperties props) {
        this.store = store;
        this.seed = seed;
        AdminProperties.Shop shop = props.shop();
        this.database = shop.database();
        this.pools = new PgPools(shop.host(), shop.port(), shop.explainUser(), shop.explainPassword(),
                seed.config().settings().explainTimeoutMs());
        this.estimator = new ExplainCostEstimator(pools);
        this.tableStats = new dev.queryguard.common.cost.TableStats(pools);
    }

    @Operation(summary = "Dry run a query", description = "Returns the decision and the reason. Nothing is executed.")
    @PostMapping("/rules/test")
    public TestResponse test(@Valid @RequestBody TestRequest req) {
        QueryGuardConfig config = store.current(seed.config());
        String role = config.roleFor(req.user());
        QueryInfo q = analyzer.analyze(req.sql());
        Decision d = new RuleEngine(config).evaluate(q, new EvalContext(req.user(), role, Instant.now(), st -> {
            try {
                return estimator.estimate(database, st.sql());
            } catch (dev.queryguard.common.cost.CostUnavailableException e) {
                if (config.settings().onCostError() != dev.queryguard.common.config.Settings.FailMode.ESTIMATE) {
                    throw e;
                }
                return tableStats.upperBound(database, st);
            }
        }));
        List<StatementSummary> statements = q.statements().stream()
                .map(s -> new StatementSummary(s.kind().name(), s.parsed(), List.copyOf(s.tables())))
                .toList();
        return new TestResponse(req.user(), role, d.verdict().name(), d.ruleId(), d.sqlstate(), d.message(),
                d.rewrittenSql(), d.estimatedCost(), d.estimatedRows(), q.fingerprint(), q.normalized(), statements);
    }

    @PreDestroy
    void close() {
        pools.close();
    }
}
