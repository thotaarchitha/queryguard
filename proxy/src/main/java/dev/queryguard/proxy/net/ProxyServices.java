package dev.queryguard.proxy.net;

import dev.queryguard.proxy.ProxyConfig;
import dev.queryguard.proxy.config.PolicyHolder;
import dev.queryguard.proxy.events.EventFactory;
import dev.queryguard.proxy.events.EventPublisher;
import dev.queryguard.proxy.masking.MaskTargets;
import dev.queryguard.proxy.metrics.ProxyMetrics;
import dev.queryguard.proxy.rules.Decider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.netty.channel.group.ChannelGroup;

/** Shared, thread-safe services every connection uses. */
public record ProxyServices(
        ProxyConfig config,
        PolicyHolder policies,
        Decider decider,
        MaskTargets maskTargets,
        EventPublisher events,
        EventFactory eventFactory,
        ProxyMetrics metrics,
        CircuitBreaker backendBreaker,
        ChannelGroup clients) {
}
