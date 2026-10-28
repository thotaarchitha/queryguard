package dev.queryguard.proxy;

import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.cost.ExplainCostEstimator;
import dev.queryguard.common.cost.PgPools;
import dev.queryguard.proxy.config.ActivePolicy;
import dev.queryguard.proxy.config.ConfigDbWatcher;
import dev.queryguard.proxy.config.PolicyHolder;
import dev.queryguard.proxy.events.EventFactory;
import dev.queryguard.proxy.events.EventPublisher;
import dev.queryguard.proxy.events.KafkaEventPublisher;
import dev.queryguard.proxy.masking.MaskTargets;
import dev.queryguard.proxy.metrics.AdminHttpServer;
import dev.queryguard.proxy.metrics.ProxyMetrics;
import dev.queryguard.proxy.net.FrontendHandler;
import dev.queryguard.proxy.net.ProxyServices;
import dev.queryguard.proxy.protocol.PgFrontendDecoder;
import dev.queryguard.proxy.rules.Decider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires everything together and owns startup and graceful shutdown. */
public final class ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyServer.class);

    private final ProxyConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private ProxyMetrics metrics;
    private PolicyHolder policies;
    private PgPools pools;
    private Decider decider;
    private EventPublisher events;
    private ConfigDbWatcher watcher;
    private AdminHttpServer admin;
    private ChannelGroup clients;
    private volatile boolean draining;

    public ProxyServer(ProxyConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException, IOException {
        metrics = new ProxyMetrics();
        QueryGuardConfig base = loadPolicy();
        policies = new PolicyHolder(ActivePolicy.of(0, base, "yaml"));
        metrics.configLoaded(0, 0);

        if (config.explainUser() != null) {
            pools = new PgPools(config.pgHost(), config.pgPort(), config.explainUser(), config.explainPassword(),
                    base.settings().explainTimeoutMs());
        } else {
            log.warn("QG_EXPLAIN_USER not set: cost rules can't fire and masking can't resolve columns");
        }
        MaskTargets maskTargets = new MaskTargets(pools);
        decider = new Decider(policies, pools == null ? null : new ExplainCostEstimator(pools),
                pools == null ? null : new dev.queryguard.common.cost.TableStats(pools), maskTargets, metrics);
        events = config.kafkaBootstrap() == null || config.kafkaBootstrap().isBlank()
                ? EventPublisher.NOOP
                : new KafkaEventPublisher(config.kafkaBootstrap(), config.kafkaTopic(), 50_000, metrics.registry());

        // After 5 failed connects in a row, stop trying for 10 s. New clients get an immediate
        // "database unreachable" instead of each waiting out the connect timeout.
        CircuitBreaker breaker = CircuitBreaker.of("postgres", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        breaker.getEventPublisher().onStateTransition(e -> log.warn("Backend circuit breaker: {}", e.getStateTransition()));
        metrics.registry().gauge("qg_backend_breaker_open", breaker,
                b -> b.getState() == CircuitBreaker.State.OPEN ? 1 : b.getState() == CircuitBreaker.State.HALF_OPEN ? 0.5 : 0);

        clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        ProxyServices services = new ProxyServices(config, policies, decider, maskTargets, events,
                new EventFactory(config.instanceName()), metrics, breaker, clients);

        if (config.configDbUrl() != null && !config.configDbUrl().isBlank()) {
            watcher = new ConfigDbWatcher(config.configDbUrl(), config.configDbUser(), config.configDbPassword(),
                    base, policies, metrics);
            watcher.start();
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                // Don't read from a client until its backend connection exists.
                .childOption(ChannelOption.AUTO_READ, false)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new IdleStateHandler(0, 0, config.idleTimeoutSeconds(), TimeUnit.SECONDS),
                                new PgFrontendDecoder(),
                                new FrontendHandler(services));
                    }
                });
        serverChannel = bootstrap.bind(config.listenPort()).sync().channel();

        admin = new AdminHttpServer(config.adminPort(), metrics, () -> !draining, () -> policies.current().version());
        admin.start();
        log.info("QueryGuard starting on {} -> {}:{} (admin on {}, {} rules, events {})", port(), config.pgHost(),
                config.pgPort(), admin.port(), policies.current().engine().rules().size(),
                events == EventPublisher.NOOP ? "off" : "to " + config.kafkaBootstrap());
    }

    private QueryGuardConfig loadPolicy() throws IOException {
        Path path = config.rulesFile() != null ? Path.of(config.rulesFile()) : Path.of("infra/rules/default-rules.yml");
        if (!Files.exists(path)) {
            if (config.rulesFile() != null) {
                throw new IOException("QG_RULES_FILE " + path + " doesn't exist");
            }
            log.warn("No rules file found; starting with an empty policy (everything allowed)");
            return new QueryGuardConfig(null, null, null, null, null);
        }
        QueryGuardConfig c = ConfigMapper.load(path);
        log.info("Loaded {} rules and {} masked columns from {}", c.rules().size(), c.masking().size(), path);
        return c;
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public int adminPort() {
        return admin.port();
    }

    public PolicyHolder policies() {
        return policies;
    }

    public ProxyMetrics metrics() {
        return metrics;
    }

    /**
     * Stop accepting, let in-flight sessions finish for up to the grace period, then close
     * whatever is left. Readiness flips to 503 first so a load balancer stops sending traffic.
     */
    public void stop() {
        draining = true;
        log.info("Draining: {} open connections", metrics == null ? 0 : metrics.activeConnections().get());
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.shutdownGraceSeconds());
        while (metrics != null && metrics.activeConnections().get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (clients != null && !clients.isEmpty()) {
            log.info("Grace period over, closing {} remaining connections", clients.size());
            clients.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        if (watcher != null) {
            watcher.stop();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (decider != null) {
            decider.close();
        }
        if (events != null) {
            events.close();
        }
        if (pools != null) {
            pools.close();
        }
        if (admin != null) {
            admin.stop();
        }
        log.info("QueryGuard stopped");
    }

    public void awaitClose() throws InterruptedException {
        serverChannel.closeFuture().sync();
    }

    public static void main(String[] args) throws Exception {
        ProxyServer server = new ProxyServer(ProxyConfig.fromEnv());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "qg-shutdown"));
        server.awaitClose();
    }
}
