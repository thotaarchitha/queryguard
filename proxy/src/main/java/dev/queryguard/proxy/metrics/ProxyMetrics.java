package dev.queryguard.proxy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Everything Prometheus scrapes from /metrics. Names are prefixed qg_ so they group together in Grafana. */
public final class ProxyMetrics {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong configVersion = new AtomicLong();
    private final AtomicLong reloadLagMs = new AtomicLong(-1);
    private final Map<String, Counter> decisions = new ConcurrentHashMap<>();
    private final Timer decisionLatency;
    private final Counter backendErrors;
    private final Counter rejectedConnections;
    private final Counter maskedValues;

    public ProxyMetrics() {
        registry.config().commonTags("app", "queryguard-proxy");
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        registry.gauge("qg_connections_active", activeConnections);
        registry.gauge("qg_config_version", configVersion);
        registry.gauge("qg_config_reload_lag_ms", reloadLagMs);
        decisionLatency = Timer.builder("qg_decision_latency")
                .description("Parse + rules + EXPLAIN (if needed) for one query")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofNanos(20_000))
                .maximumExpectedValue(Duration.ofSeconds(2))
                .register(registry);
        backendErrors = Counter.builder("qg_backend_errors").description("Failed backend connects and dropped backend connections").register(registry);
        rejectedConnections = Counter.builder("qg_connections_rejected").description("Client connections refused (cap reached or breaker open)").register(registry);
        maskedValues = Counter.builder("qg_masked_values").description("Individual values rewritten by masking").register(registry);
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    public AtomicInteger activeConnections() {
        return activeConnections;
    }

    public void decision(String verdict, long nanos) {
        decisions.computeIfAbsent(verdict, v -> Counter.builder("qg_queries")
                .description("Queries by decision")
                .tag("decision", v)
                .register(registry)).increment();
        decisionLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void backendError() {
        backendErrors.increment();
    }

    public void rejected() {
        rejectedConnections.increment();
    }

    public void masked(int values) {
        maskedValues.increment(values);
    }

    public void configLoaded(long version, long lagMs) {
        configVersion.set(version);
        reloadLagMs.set(lagMs);
    }

    public String scrape() {
        return registry.scrape();
    }
}
