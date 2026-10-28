package dev.queryguard.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Pass-through tests against a tiny echo server standing in for PostgreSQL. */
class ProxyServerTest {

    private final List<Socket> echoConnections = new CopyOnWriteArrayList<>();
    private ServerSocket echoServer;
    private ProxyServer proxy;

    @AfterEach
    void tearDown() throws IOException {
        if (proxy != null) {
            proxy.stop();
        }
        if (echoServer != null) {
            echoServer.close();
        }
    }

    @Test
    void forwardsBytesBothWays() throws Exception {
        startEcho();
        proxy = startProxy(echoServer.getLocalPort());

        try (Socket client = new Socket("localhost", proxy.port())) {
            client.setSoTimeout(5000);
            client.getOutputStream().write("hello queryguard".getBytes(StandardCharsets.UTF_8));
            byte[] reply = client.getInputStream().readNBytes(16);
            assertThat(new String(reply, StandardCharsets.UTF_8)).isEqualTo("hello queryguard");
        }
    }

    @Test
    void largePayloadArrivesIntact() throws Exception {
        startEcho();
        proxy = startProxy(echoServer.getLocalPort());

        // Bigger than Netty's default write buffer high-water mark, so the
        // backpressure path actually gets used.
        byte[] payload = new byte[8 * 1024 * 1024];
        new Random(42).nextBytes(payload);

        try (Socket client = new Socket("localhost", proxy.port())) {
            client.setSoTimeout(10_000);
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    client.getOutputStream().write(payload);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            byte[] reply = client.getInputStream().readNBytes(payload.length);
            writer.join();
            assertThat(Arrays.equals(reply, payload)).isTrue();
        }
    }

    @Test
    void clientIsClosedWhenBackendIsDown() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        proxy = startProxy(deadPort);

        try (Socket client = new Socket("localhost", proxy.port())) {
            client.setSoTimeout(5000);
            assertThat(client.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test
    void clientIsClosedWhenBackendDiesMidSession() throws Exception {
        startEcho();
        proxy = startProxy(echoServer.getLocalPort());

        try (Socket client = new Socket("localhost", proxy.port())) {
            client.setSoTimeout(5000);
            client.getOutputStream().write('x');
            assertThat(client.getInputStream().read()).isEqualTo('x');

            // Simulate PostgreSQL dying: stop accepting and drop the live connection.
            echoServer.close();
            closeEchoConnections();
            assertThat(client.getInputStream().read()).isEqualTo(-1);
        }
    }

    private ProxyServer startProxy(int backendPort) throws InterruptedException {
        ProxyConfig config = ProxyConfig.fromMap(Map.of(
                "QG_LISTEN_PORT", "0",
                "QG_PG_HOST", "localhost",
                "QG_PG_PORT", String.valueOf(backendPort),
                "QG_CONNECT_TIMEOUT_MS", "1000"));
        ProxyServer server = new ProxyServer(config);
        server.start();
        return server;
    }

    private void startEcho() throws IOException {
        echoServer = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!echoServer.isClosed()) {
                try {
                    Socket conn = echoServer.accept();
                    echoConnections.add(conn);
                    Thread.ofVirtual().start(() -> echo(conn));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private void closeEchoConnections() throws IOException {
        for (Socket s : echoConnections) {
            s.close();
        }
    }

    private static void echo(Socket conn) {
        try (conn; InputStream in = conn.getInputStream(); OutputStream out = conn.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // connection closed
        }
    }
}
