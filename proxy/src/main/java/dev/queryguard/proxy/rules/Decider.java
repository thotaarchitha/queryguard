package dev.queryguard.proxy.rules;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.queryguard.common.analysis.QueryAnalyzer;
import dev.queryguard.common.analysis.QueryInfo;
import dev.queryguard.common.analysis.StatementInfo;
import dev.queryguard.common.config.Settings.FailMode;
import dev.queryguard.common.cost.CostEstimate;
import dev.queryguard.common.cost.CostLookup;
import dev.queryguard.common.cost.CostUnavailableException;
import dev.queryguard.common.cost.ExplainCostEstimator;
import dev.queryguard.common.masking.MaskRule;
import dev.queryguard.common.rules.Decision;
import dev.queryguard.common.rules.EvalContext;
import dev.queryguard.proxy.config.ActivePolicy;
import dev.queryguard.proxy.config.PolicyHolder;
import dev.queryguard.proxy.masking.MaskTargets;
import dev.queryguard.proxy.metrics.ProxyMetrics;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the ALLOW / REWRITE / BLOCK call for one query.
 *
 * <p>Runs on virtual threads, never on the Netty event loop: JSqlParser can take a
 * millisecond on a big query and EXPLAIN is a network round trip, and the event loop is
 * shared by hundreds of connections. The caller pauses reading from that one client while
 * it waits and gets the result back on its own loop.
 */
public final class Decider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Decider.class);

    public record Verdict(QueryInfo query, Decision decision, long nanos) {
    }

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final QueryAnalyzer analyzer = new QueryAnalyzer();
    private final PolicyHolder policies;
    private final ExplainCostEstimator estimator;
    private final MaskTargets maskTargets;
    private final ProxyMetrics metrics;
    private final Cache<String, CostEstimate> costCache;

    public Decider(PolicyHolder policies, ExplainCostEstimator estimator, MaskTargets maskTargets, ProxyMetrics metrics) {
        this.policies = policies;
        this.estimator = estimator;
        this.maskTargets = maskTargets;
        this.metrics = metrics;
        // Keyed by statement fingerprint: WHERE id = 5 and WHERE id = 9 share one EXPLAIN.
        // Five minutes because table sizes drift; stale-by-minutes is fine for a guard rail.
        this.costCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(metrics.registry(), costCache, "qg_explain_cache");
        warmUp();
    }

    /**
     * JSqlParser's generated parser is huge and the first parse takes several hundred ms on
     * a cold JVM. Without this, the first real query after a restart can blow the decision
     * timeout (found that the hard way in the integration tests).
     */
    private void warmUp() {
        long t0 = System.nanoTime();
        String[] samples = {
                "SELECT a, upper(b) FROM t JOIN u ON u.id = t.id WHERE c = $1 AND d IN (1, 2) ORDER BY a LIMIT 10",
                "UPDATE t SET a = 1 WHERE id = $1",
                "DELETE FROM t WHERE id = 1",
                "INSERT INTO t (a, b) VALUES ($1, 'x') RETURNING id",
                "WITH x AS (SELECT 1) SELECT * FROM x UNION SELECT 2",
        };
        for (int i = 0; i < 50; i++) {
            for (String s : samples) {
                analyzer.analyze(s);
            }
        }
        log.info("Analyzer warmed up in {} ms", (System.nanoTime() - t0) / 1_000_000);
    }

    public CompletableFuture<Verdict> decide(String user, String role, String database, String sql) {
        long start = System.nanoTime();
        ActivePolicy policy = policies.current();
        int timeoutMs = policy.config().settings().decisionTimeoutMs();

        // Resolving masked columns is a one-time cost per database (first connection, catalog
        // query). It gets its own, longer budget so it doesn't eat the per-query timeout.
        boolean roleIsMasked = policy.config().masking().stream().anyMatch(r -> r.appliesTo(role));
        CompletableFuture<Boolean> masking = !roleIsMasked || !maskTargets.peek(database).isEmpty()
                ? CompletableFuture.completedFuture(true)
                : CompletableFuture.supplyAsync(() -> loadMasking(database, policy), executor)
                        .orTimeout(MASKING_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .exceptionally(e -> false);

        return masking
                .thenCompose(ok -> !ok
                        ? CompletableFuture.completedFuture(maskingUnavailable(sql, role, start))
                        : CompletableFuture.supplyAsync(() -> evaluate(user, role, database, sql, policy, start), executor)
                                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS))
                .exceptionally(err -> onFailure(err, sql, role, policy, start))
                .whenComplete((v, e) -> {
                    if (v != null) {
                        metrics.decision(v.decision().verdict().name(), v.nanos());
                    }
                });
    }

    private static final int MASKING_LOAD_TIMEOUT_MS = 5000;

    private boolean loadMasking(String database, ActivePolicy policy) {
        try {
            maskTargets.load(database, policy.version(), policy.config().masking());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Verdict maskingUnavailable(String sql, String role, long start) {
        var tokens = dev.queryguard.common.analysis.SqlLexer.tokenize(sql);
        String normalized = dev.queryguard.common.analysis.SqlLexer.normalize(tokens);
        Decision d = Decision.block("QG-MASK", "58000",
                "QueryGuard: masking metadata is unavailable right now, so queries for role " + role
                        + " are paused. Try again in a moment.", null, null);
        return new Verdict(new QueryInfo(sql, normalized, dev.queryguard.common.analysis.Fingerprints.of(normalized), List.of()),
                d, System.nanoTime() - start);
    }

    private Verdict evaluate(String user, String role, String database, String sql, ActivePolicy policy, long start) {
        QueryInfo query = analyzer.analyze(sql);
        // Masked roles: make sure this policy version's columns are resolved before any rows
        // can come back. Usually a cache hit; after a policy change it's one catalog query.
        if (policy.config().masking().stream().anyMatch(r -> r.appliesTo(role)) && !loadMasking(database, policy)) {
            return maskingUnavailable(sql, role, start);
        }
        CostLookup costs = estimator == null ? CostLookup.NONE : st -> cost(database, st);
        Decision d = policy.engine().evaluate(query, new EvalContext(user, role, Instant.now(), costs));
        return new Verdict(query, d, System.nanoTime() - start);
    }

    private CostEstimate cost(String database, StatementInfo st) throws CostUnavailableException {
        String key = database + ":" + st.fingerprint();
        CostEstimate cached = costCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        // Failures aren't cached: a timeout under load shouldn't stick for five minutes.
        CostEstimate fresh = estimator.estimate(database, st.sql());
        costCache.put(key, fresh);
        return fresh;
    }

    /** The whole decision blew its time budget or threw. What happens next is the fail-open/closed setting. */
    private Verdict onFailure(Throwable err, String sql, String role, ActivePolicy policy, long start) {
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        boolean timeout = cause instanceof TimeoutException;
        FailMode mode = policy.config().settings().onParseError();
        // Masking is never fail-open. An unchecked query from a masked role could be
        // upper(email), and the result columns may not even be resolved yet.
        boolean roleIsMasked = policy.config().masking().stream().anyMatch(r -> r.appliesTo(role));
        // Lexer only here. If the parser is what hung, running it again on this thread
        // (the JDK's timeout scheduler) would hang that too.
        var tokens = dev.queryguard.common.analysis.SqlLexer.tokenize(sql);
        String normalized = dev.queryguard.common.analysis.SqlLexer.normalize(tokens);
        QueryInfo query = new QueryInfo(sql, normalized, dev.queryguard.common.analysis.Fingerprints.of(normalized), List.of());
        boolean readOnly = dev.queryguard.common.analysis.SqlLexer.splitStatements(tokens).stream()
                .allMatch(part -> part.get(0).type() == dev.queryguard.common.analysis.SqlLexer.Type.WORD
                        && dev.queryguard.common.analysis.StatementKind.fromKeyword(part.get(0).text()).isSafeWhenUnparsed());
        boolean block = roleIsMasked || mode == FailMode.BLOCK || (mode == FailMode.ALLOW_READS && !readOnly);
        log.warn("Decision {} for query {} ({}); {}", timeout ? "timed out" : "failed", query.fingerprint(),
                cause.toString(), block ? "blocking" : "letting it through");
        Decision d = block
                ? Decision.block("QG-TIMEOUT", "57014", "QueryGuard: couldn't check this query in time, and the policy is to block when unsure.", null, null)
                : Decision.allow(null, null);
        return new Verdict(query, d, System.nanoTime() - start);
    }

    /**
     * Called when a session starts: resolve masked columns in the background so the
     * session's first query doesn't pay for it (and doesn't risk the decision timeout).
     */
    public void prefetch(String database, String role) {
        ActivePolicy policy = policies.current();
        if (policy.config().masking().stream().noneMatch(r -> r.appliesTo(role))) {
            return;
        }
        executor.execute(() -> {
            try {
                maskTargets.load(database, policy.version(), policy.config().masking());
            } catch (RuntimeException e) {
                // evaluate() will try again and block the query if it still fails
            }
        });
    }

    public long cacheSize() {
        return costCache.estimatedSize();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
