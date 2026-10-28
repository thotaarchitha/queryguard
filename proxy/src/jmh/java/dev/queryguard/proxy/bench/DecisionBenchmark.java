package dev.queryguard.proxy.bench;

import dev.queryguard.common.analysis.QueryAnalyzer;
import dev.queryguard.common.analysis.QueryInfo;
import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.cost.CostEstimate;
import dev.queryguard.common.rules.Decision;
import dev.queryguard.common.rules.EvalContext;
import dev.queryguard.common.rules.RuleEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * The decision step without the network: lexer + parser + rules. EXPLAIN is stubbed to a
 * fixed cost, which is what a warm Caffeine hit looks like; a cold EXPLAIN is a round trip to
 * Postgres and is measured end to end in bench/RESULTS.md instead.
 *
 * shapeCache=0 is "parse every query"; 20000 is the default proxy setting. Literals are
 * randomized on every call so the cache only helps when shapes repeat, like in real traffic.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DecisionBenchmark {

    @Param({"0", "20000"})
    int shapeCache;

    @Param({"point_lookup", "analyst_join", "update"})
    String query;

    QueryAnalyzer analyzer;
    RuleEngine engine;

    @Setup
    public void setup() throws Exception {
        Path rules = Path.of("infra/rules/default-rules.yml");
        if (!Files.exists(rules)) {
            rules = Path.of("../infra/rules/default-rules.yml");
        }
        QueryGuardConfig config = ConfigMapper.load(rules);
        engine = new RuleEngine(config);
        analyzer = new QueryAnalyzer(shapeCache);
    }

    private String sql() {
        int n = ThreadLocalRandom.current().nextInt(1, 5_000_000);
        return switch (query) {
            case "point_lookup" -> "SELECT id, status, total_cents, created_at FROM orders WHERE id = " + n;
            case "analyst_join" -> "SELECT o.status, c.country, count(*) FROM orders o JOIN customers c ON c.id = o.customer_id "
                    + "WHERE o.created_at > now() - interval '7 days' AND c.id < " + n + " GROUP BY 1, 2 ORDER BY 3 DESC";
            default -> "UPDATE orders SET status = 'shipped' WHERE id = " + n + " AND status = 'paid'";
        };
    }

    @Benchmark
    public Decision decide() {
        QueryInfo q = analyzer.analyze(sql());
        String user = query.equals("analyst_join") ? "alice" : "bob";
        String role = query.equals("analyst_join") ? "analyst" : "app";
        return engine.evaluate(q, new EvalContext(user, role, Instant.now(), st -> new CostEstimate(1200, 40)));
    }
}
