package dev.queryguard.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Real Postgres 16, real pgjdbc, the proxy in-process. Every rule and the masking tricks,
 * end to end, through both the simple and the extended protocol.
 */
@Tag("integration")
class ProxyIntegrationTest {

    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("shop")
            .withUsername("postgres")
            .withPassword("dev")
            .withCopyFileToContainer(MountableFile.forClasspathResource("it-seed.sql"), "/docker-entrypoint-initdb.d/01-seed.sql");

    static ProxyServer proxy;

    @BeforeAll
    static void start() throws Exception {
        pg.start();
        // Same policy as production, with the cost thresholds scaled down to the tiny test tables.
        String rules = Files.readString(Path.of("../infra/rules/default-rules.yml"))
                .replace("estimated_cost_gt: 100000", "estimated_cost_gt: 200")
                .replace("estimated_rows_gt: 1000000", "estimated_rows_gt: 2000");
        Path tmp = Files.createTempFile("qg-rules", ".yml");
        Files.writeString(tmp, rules);

        proxy = new ProxyServer(ProxyConfig.fromMap(Map.of(
                "QG_LISTEN_PORT", "0",
                "QG_ADMIN_PORT", "0",
                "QG_PG_HOST", pg.getHost(),
                "QG_PG_PORT", String.valueOf(pg.getMappedPort(5432)),
                "QG_RULES_FILE", tmp.toString(),
                "QG_EXPLAIN_USER", "qg_explain",
                "QG_EXPLAIN_PASSWORD", "qg_explain",
                "QG_SHUTDOWN_GRACE_S", "1")));
        proxy.start();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
        }
        pg.stop();
    }

    static Connection connect(String user, String mode) throws SQLException {
        String url = "jdbc:postgresql://localhost:" + proxy.port() + "/shop?sslmode=disable&preferQueryMode=" + mode;
        return DriverManager.getConnection(url, user, user.equals("postgres") ? "dev" : user);
    }

    static Connection connect(String user) throws SQLException {
        return connect(user, "extended");
    }

    static List<String> column(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    // ---- basics -------------------------------------------------------------------------

    @Test
    void plainQueriesWorkInBothProtocols() throws Exception {
        for (String mode : List.of("simple", "extended")) {
            try (Connection c = connect("bob", mode)) {
                assertThat(column(c, "SELECT count(*) FROM orders")).containsExactly("3000");
            }
        }
    }

    @Test
    void r1BlocksAndTheConnectionStaysUsable() throws Exception {
        for (String mode : List.of("simple", "extended")) {
            try (Connection c = connect("bob", mode); Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeUpdate("DELETE FROM orders"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("QueryGuard R1")
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
                assertThat(column(c, "SELECT count(*) FROM orders")).containsExactly("3000");
            }
        }
    }

    @Test
    void blockInsideATransactionAbortsItLikeARealError() throws Exception {
        try (Connection c = connect("bob")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO products VALUES (9001, 'temp', 'x', 1)");
                assertThatThrownBy(() -> st.executeUpdate("UPDATE products SET price_cents = 0")).hasMessageContaining("R1");
                assertThatThrownBy(() -> st.executeQuery("SELECT 1")).hasMessageContaining("current transaction is aborted");
            }
            c.rollback();
        }
        try (Connection c = connect("bob")) {
            assertThat(column(c, "SELECT count(*) FROM products WHERE id = 9001")).containsExactly("0");
        }
    }

    @Test
    void multiStatementSimpleQueryIsBlockedAsAWhole() throws Exception {
        try (Connection c = connect("bob", "simple"); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute("SELECT 1; DROP TABLE orders")).hasMessageContaining("QueryGuard R2");
        }
        try (Connection c = connect("postgres")) {
            assertThat(column(c, "SELECT to_regclass('orders') IS NOT NULL")).containsExactly("t");
        }
    }

    @Test
    void r2AdminsCanStillDoDdl() throws Exception {
        try (Connection c = connect("postgres"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE scratch (id int)");
            st.execute("DROP TABLE scratch");
        }
    }

    @Test
    void r3ExpensiveAnalystQueriesUseRealExplain() throws Exception {
        try (Connection c = connect("alice"); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery(
                    "SELECT count(*) FROM orders o JOIN orders o2 ON o2.customer_id = o.customer_id"))
                    .hasMessageContaining("QueryGuard R3")
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("54000"));
            assertThat(column(c, "SELECT id FROM orders WHERE id = 1")).containsExactly("1");
        }
    }

    @Test
    void r4AddsALimitForAnalysts() throws Exception {
        // orders has 3000 rows; analysts get 1000, everyone else gets all of them
        for (String mode : List.of("simple", "extended")) {
            try (Connection c = connect("alice", mode)) {
                assertThat(column(c, "SELECT id FROM orders WHERE id > 0")).hasSize(1000);
            }
        }
        try (Connection c = connect("etl_user")) {
            assertThat(column(c, "SELECT id FROM orders WHERE id > 0")).hasSize(3000);
        }
    }

    @Test
    void r6AndR8() throws Exception {
        try (Connection c = connect("bob"); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("SELECT o.id FROM orders o, products p")).hasMessageContaining("R6");
            assertThatThrownBy(() -> st.executeQuery("SELECT pg_sleep(5)")).hasMessageContaining("R8");
            assertThatThrownBy(() -> st.executeQuery("SELECT * FROM orders")).hasMessageContaining("R5");
        }
    }

    // ---- masking ------------------------------------------------------------------------

    @Test
    void analystsSeeMaskedEmailsAdminsDont() throws Exception {
        try (Connection c = connect("alice")) {
            assertThat(column(c, "SELECT email FROM customers WHERE id = 42")).containsExactly("u***@***.com");
            assertThat(column(c, "SELECT email AS e FROM customers WHERE id = 42")).containsExactly("u***@***.com");
            assertThat(column(c, "SELECT card_last4 FROM customers WHERE id = 42")).containsExactly("****");
        }
        try (Connection c = connect("postgres")) {
            assertThat(column(c, "SELECT email FROM customers WHERE id = 42")).containsExactly("user42@example.com");
        }
    }

    @Test
    void maskingSurvivesPreparedStatementReuse() throws Exception {
        // pgjdbc switches to a named server-side statement after 5 executions and then
        // stops sending Describe. Masks have to come from what we remembered at Describe time.
        try (Connection c = connect("alice");
             PreparedStatement ps = c.prepareStatement("SELECT email, phone FROM customers WHERE id = ?")) {
            for (int i = 1; i <= 12; i++) {
                ps.setLong(1, i);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).as("execution %d", i).isEqualTo("u***@***.com");
                    assertThat(rs.getString(2)).as("execution %d", i).startsWith("*******").hasSize(11);
                }
            }
        }
    }

    @Test
    void maskingBypassesAreRefused() throws Exception {
        try (Connection c = connect("alice"); Statement st = c.createStatement()) {
            for (String sql : List.of(
                    "SELECT upper(email) FROM customers LIMIT 1",
                    "SELECT id FROM customers WHERE email LIKE 'user1%' LIMIT 1",
                    "SELECT row_to_json(c) FROM customers c LIMIT 1",
                    "SELECT email FROM customers UNION SELECT 'x' LIMIT 1")) {
                List<String> got = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        got.add(rs.getString(1));
                    }
                } catch (SQLException e) {
                    assertThat(e.getMessage()).as(sql).contains("QueryGuard M1");
                    continue;
                }
                throw new AssertionError("not blocked: " + sql + " -> " + got);
            }
        }
    }

    // ---- ops ----------------------------------------------------------------------------

    @Test
    void metricsEndpointCountsDecisions() throws Exception {
        try (Connection c = connect("bob"); Statement st = c.createStatement()) {
            st.executeQuery("SELECT 1").close();
        }
        var client = java.net.http.HttpClient.newHttpClient();
        String body = client.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + proxy.adminPort() + "/metrics")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        assertThat(body).contains("qg_queries_total{").contains("decision=\"ALLOW\"").contains("qg_connections_active");
        int ready = client.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + proxy.adminPort() + "/health/ready")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
        assertThat(ready).isEqualTo(200);
    }
}
