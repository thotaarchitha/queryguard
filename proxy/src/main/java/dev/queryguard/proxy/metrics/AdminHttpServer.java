package dev.queryguard.proxy.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Health and metrics on their own port, so a flood of database clients can't starve the
 * probes Kubernetes uses to decide whether we're alive. The JDK's built-in server is
 * plenty for three GET endpoints; no need to pull in a web framework.
 *
 * <ul>
 *   <li>/health/live: the process is up</li>
 *   <li>/health/ready: we have a policy loaded and aren't draining (503 during shutdown)</li>
 *   <li>/metrics: Prometheus text format</li>
 * </ul>
 */
public final class AdminHttpServer {

    private final HttpServer server;

    public AdminHttpServer(int port, ProxyMetrics metrics, BooleanSupplier ready, LongSupplier configVersion) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health/live", ex -> send(ex, 200, "text/plain", "ok\n"));
        server.createContext("/health/ready", ex -> {
            boolean ok = ready.getAsBoolean();
            String body = "{\"status\":\"" + (ok ? "ready" : "draining") + "\",\"config_version\":" + configVersion.getAsLong() + "}\n";
            send(ex, ok ? 200 : 503, "application/json", body);
        });
        server.createContext("/metrics", ex -> send(ex, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.scrape()));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
