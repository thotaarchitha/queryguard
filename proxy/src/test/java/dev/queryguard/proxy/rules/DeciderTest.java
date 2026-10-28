package dev.queryguard.proxy.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.cost.CostEstimate;
import dev.queryguard.common.cost.CostUnavailableException;
import dev.queryguard.common.cost.ExplainCostEstimator;
import dev.queryguard.common.cost.TableStats;
import dev.queryguard.common.rules.Decision.Verdict;
import dev.queryguard.proxy.config.ActivePolicy;
import dev.queryguard.proxy.config.PolicyHolder;
import dev.queryguard.proxy.masking.MaskTargets;
import dev.queryguard.proxy.metrics.ProxyMetrics;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The overload path: EXPLAIN times out, and the cost rules must keep working anyway. */
class DeciderTest {

    ExplainCostEstimator explain = mock(ExplainCostEstimator.class);
    TableStats stats = mock(TableStats.class);
    Decider decider;

    @BeforeEach
    void setUp() throws Exception {
        QueryGuardConfig shipped = ConfigMapper.load(Path.of("../infra/rules/default-rules.yml"));
        // Masking off: this test is about cost rules, and there's no database for column metadata.
        QueryGuardConfig config = shipped.withPolicy(shipped.roleMapping(), shipped.rules(), java.util.List.of());
        decider = new Decider(new PolicyHolder(ActivePolicy.of(1, config, "test")), explain, stats,
                new MaskTargets(null), new ProxyMetrics());
    }

    @Test
    void explainTimeoutFallsBackToTableStatisticsAndStillBlocks() throws Exception {
        when(explain.estimate(anyString(), anyString())).thenThrow(new CostUnavailableException("EXPLAIN timed out"));
        when(stats.upperBound(anyString(), any())).thenReturn(new CostEstimate(140_000, 5_000_000, "table-stats"));

        Decider.Verdict v = decider.decide("bob", "app", "shop", "SELECT * FROM orders").get();

        assertThat(v.decision().verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(v.decision().ruleId()).isEqualTo("R5");
        assertThat(v.decision().message()).contains("table statistics");
    }

    @Test
    void afterATimeoutExplainIsSkippedForAWhile() throws Exception {
        when(explain.estimate(anyString(), anyString())).thenThrow(new CostUnavailableException("EXPLAIN timed out"));
        when(stats.upperBound(anyString(), any())).thenReturn(new CostEstimate(10, 10, "table-stats"));

        decider.decide("bob", "app", "shop", "SELECT * FROM products WHERE id = 1").get();
        decider.decide("bob", "app", "shop", "SELECT * FROM customers WHERE id = 2").get();
        decider.decide("bob", "app", "shop", "SELECT * FROM orders WHERE id = 3").get();

        verify(explain, times(1)).estimate(anyString(), anyString());
        verify(stats, times(3)).upperBound(anyString(), any());
    }

    @Test
    void cheapQueriesNeverTouchExplain() throws Exception {
        decider.decide("bob", "app", "shop", "UPDATE orders SET status = 'paid' WHERE id = 1").get();
        verify(explain, times(0)).estimate(anyString(), anyString());
    }
}
