package dev.queryguard.common.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.queryguard.common.analysis.QueryAnalyzer;
import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.config.Settings;
import dev.queryguard.common.cost.CostEstimate;
import dev.queryguard.common.cost.CostLookup;
import dev.queryguard.common.cost.CostUnavailableException;
import dev.queryguard.common.rules.Decision.Verdict;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the real default-rules.yml, so the shipped policy is what's under test. */
class RuleEngineTest {

    private static final Instant TUESDAY_NOON = Instant.parse("2026-11-03T12:00:00Z");
    private static final Instant SATURDAY_3AM = Instant.parse("2026-11-07T03:00:00Z");

    private static QueryGuardConfig config;
    private static RuleEngine engine;
    private final QueryAnalyzer analyzer = new QueryAnalyzer();

    @BeforeAll
    static void load() throws Exception {
        config = ConfigMapper.load(Path.of("../infra/rules/default-rules.yml"));
        engine = new RuleEngine(config);
    }

    private Decision eval(String user, String sql, CostLookup costs, Instant now) {
        return engine.evaluate(analyzer.analyze(sql), new EvalContext(user, config.roleFor(user), now, costs));
    }

    private Decision eval(String user, String sql) {
        return eval(user, sql, st -> new CostEstimate(10, 1), TUESDAY_NOON);
    }

    @Test
    void loadsEveryShippedRule() {
        assertThat(engine.rules()).extracting(Rule::id)
                .containsExactly("R1", "R2", "R8", "R6", "R3", "R5", "R7", "R4");
        assertThat(config.roleFor("alice")).isEqualTo("analyst");
        assertThat(config.roleFor("someone-new")).isEqualTo("app");
    }

    @Test
    void r1BlocksUnboundedWritesForEveryone() {
        Decision d = eval("postgres", "DELETE FROM orders");
        assertThat(d.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(d.ruleId()).isEqualTo("R1");
        assertThat(d.message()).contains("DELETE without a WHERE");
        assertThat(eval("bob", "UPDATE orders SET status = 'x' WHERE id = 1").verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void r1CatchesExplainAnalyze() {
        assertThat(eval("postgres", "EXPLAIN ANALYZE DELETE FROM orders").ruleId()).isEqualTo("R1");
    }

    @Test
    void r2DdlOnlyForAdmins() {
        assertThat(eval("bob", "DROP TABLE orders").ruleId()).isEqualTo("R2");
        assertThat(eval("bob", "TRUNCATE orders").ruleId()).isEqualTo("R2");
        assertThat(eval("postgres", "DROP TABLE IF EXISTS scratch").verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void multiStatementBlocksWhenAnyPartIsBad() {
        Decision d = eval("bob", "SELECT 1; DROP TABLE orders");
        assertThat(d.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(d.ruleId()).isEqualTo("R2");
    }

    @Test
    void r3UsesEstimatedCostForAnalystsOnly() {
        CostLookup expensive = st -> new CostEstimate(254_331.7, 4_980_112);
        Decision d = eval("alice", "SELECT status, count(*) FROM orders GROUP BY status LIMIT 5", expensive, TUESDAY_NOON);
        assertThat(d.ruleId()).isEqualTo("R3");
        assertThat(d.message()).contains("254332");
        assertThat(eval("bob", "SELECT status, count(*) FROM orders GROUP BY status", expensive, TUESDAY_NOON).verdict())
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    void r4AddsLimitForAnalysts() {
        Decision d = eval("alice", "SELECT id, total_cents FROM orders WHERE customer_id = $1");
        assertThat(d.verdict()).isEqualTo(Verdict.REWRITE);
        assertThat(d.rewrittenSql()).containsIgnoringCase("LIMIT 1000").contains("$1").doesNotContain("?1");
        assertThat(eval("alice", "SELECT id FROM orders LIMIT 5").verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(eval("bob", "SELECT id FROM orders").verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void rewriteOfACachedShapeUsesThisQuerysOwnLiterals() {
        // second query hits the shape cache; its rewrite must come from its own text
        eval("alice", "SELECT id FROM orders WHERE customer_id = 11");
        Decision d = eval("alice", "SELECT id FROM orders WHERE customer_id = 22");
        assertThat(d.rewrittenSql()).contains("22").doesNotContain("11").containsIgnoringCase("LIMIT 1000");
    }

    @Test
    void r4LeavesLockingSelectsAlone() {
        assertThat(eval("alice", "SELECT id FROM orders WHERE id = 1 FOR UPDATE").verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void r5BlocksSelectStarOnHugeTables() {
        CostLookup huge = st -> new CostEstimate(90_000, 5_000_000);
        assertThat(eval("bob", "SELECT * FROM orders", huge, TUESDAY_NOON).ruleId()).isEqualTo("R5");
        assertThat(eval("etl_user", "SELECT * FROM orders", huge, TUESDAY_NOON).verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void r6BlocksCartesianProducts() {
        assertThat(eval("bob", "SELECT o.id FROM orders o, customers c").ruleId()).isEqualTo("R6");
        assertThat(eval("bob", "SELECT o.id FROM orders o CROSS JOIN customers c").ruleId()).isEqualTo("R6");
    }

    @Test
    void r7OnlyLetsReadonlyAppWriteInTheWindow() {
        String sql = "UPDATE orders SET status = 'shipped' WHERE id = 1";
        assertThat(eval("report_ro", sql, st -> new CostEstimate(1, 1), TUESDAY_NOON).ruleId()).isEqualTo("R7");
        assertThat(eval("report_ro", sql, st -> new CostEstimate(1, 1), SATURDAY_3AM).verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void r8RiskyFunctionsAnywhere() {
        Decision d = eval("postgres", "SELECT id FROM orders WHERE id = (SELECT 1 FROM pg_sleep(10))");
        assertThat(d.ruleId()).isEqualTo("R8");
        assertThat(d.message()).contains("pg_sleep()");
    }

    @Test
    void costIsOnlyFetchedWhenARuleNeedsIt() throws Exception {
        CostLookup costs = mock(CostLookup.class);
        eval("bob", "UPDATE orders SET status = 'x' WHERE id = 1", costs, TUESDAY_NOON);
        verify(costs, never()).estimate(any());
    }

    @Test
    void costFailureWithNoFallbackDoesNotBlock() throws Exception {
        CostLookup broken = mock(CostLookup.class);
        when(broken.estimate(any())).thenThrow(new CostUnavailableException("EXPLAIN timed out"));
        assertThat(eval("alice", "SELECT status FROM orders GROUP BY status LIMIT 3", broken, TUESDAY_NOON).verdict())
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    void costFailureCanFailClosed() throws Exception {
        QueryGuardConfig strict = new QueryGuardConfig(config.roleMapping(), config.defaultRole(),
                new Settings(Settings.FailMode.ALLOW_READS, Settings.FailMode.BLOCK, 200, 1000),
                config.rules(), config.masking());
        CostLookup broken = mock(CostLookup.class);
        when(broken.estimate(any())).thenThrow(new CostUnavailableException("EXPLAIN timed out"));
        Decision d = new RuleEngine(strict).evaluate(analyzer.analyze("SELECT status FROM orders LIMIT 3"),
                new EvalContext("alice", "analyst", TUESDAY_NOON, broken));
        assertThat(d.ruleId()).isEqualTo("QG-COST");
        assertThat(d.message()).contains("EXPLAIN timed out");
    }

    @Test
    void unparseableReadsPassButUnparseableWritesDont() {
        assertThat(eval("bob", "SELECT 1 WHERE 'a' OPERATOR(pg_catalog.~) 'b'").verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(eval("bob", "UPDATE orders SET status = 'x' WHERE id OPERATOR(pg_catalog.=) 1").ruleId())
                .isEqualTo("QG-PARSE");
    }

    @Test
    void ordinaryQueriesAreAllowed() {
        assertThat(eval("bob", "SELECT id, status FROM orders WHERE customer_id = 42").verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(eval("bob", "BEGIN").verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(eval("bob", "SET application_name = 'x'").verdict()).isEqualTo(Verdict.ALLOW);
    }
}
