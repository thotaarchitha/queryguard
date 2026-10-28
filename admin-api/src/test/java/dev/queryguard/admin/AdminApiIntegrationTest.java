package dev.queryguard.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16").withDatabaseName("queryguard_config");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("queryguard.seed-file", () -> "../infra/rules/default-rules.yml");
        r.add("queryguard.shop.host", pg::getHost);
        r.add("queryguard.shop.port", () -> pg.getMappedPort(5432));
        r.add("queryguard.shop.database", () -> "queryguard_config");
        r.add("queryguard.shop.explain-user", pg::getUsername);
        r.add("queryguard.shop.explain-password", pg::getPassword);
    }

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper mapper;

    final HttpClient http = HttpClient.newHttpClient();

    JsonNode call(String method, String path, String token, String body, String ifMatch, int expectStatus) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expectStatus);
        return r.body().isEmpty() ? null : mapper.readTree(r.body());
    }

    String token(String user, String password) throws Exception {
        return call("POST", "/auth/token", null, "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}", null, 200)
                .get("access_token").asText();
    }

    @Test
    void seededFromYamlOnFirstStart() throws Exception {
        JsonNode rules = call("GET", "/rules", token("viewer", "viewer-dev"), null, null, 200);
        assertThat(rules).hasSize(8);
        assertThat(call("GET", "/rules?role=analyst", token("viewer", "viewer-dev"), null, null, 200).findValuesAsText("id"))
                .contains("R3", "R4", "R1").doesNotContain("R7");
        assertThat(call("GET", "/role-mappings", token("viewer", "viewer-dev"), null, null, 200).get("alice").asText())
                .isEqualTo("analyst");
    }

    @Test
    void badPasswordIs401() throws Exception {
        call("POST", "/auth/token", null, "{\"username\":\"admin\",\"password\":\"nope\"}", null, 401);
    }

    @Test
    void updateCreatesAVersionNotifiesAndRejectsStaleEdits() throws Exception {
        String admin = token("admin", "admin-dev");
        try (Connection listen = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (Statement st = listen.createStatement()) {
                st.execute("LISTEN qg_rules_changed");
            }

            JsonNode r3 = call("GET", "/rules/R3", admin, null, null, 200);
            int version = r3.get("version").asInt();
            String body = r3.get("rule").toString().replace("\"estimated_cost_gt\":100000.0", "\"estimated_cost_gt\":50000.0")
                    .replace("\"estimated_cost_gt\":100000", "\"estimated_cost_gt\":50000");
            JsonNode changed = call("PUT", "/rules/R3", admin, body, "\"" + version + "\"", 200);
            assertThat(changed.at("/rule/version").asInt()).isEqualTo(version + 1);
            assertThat(changed.at("/rule/rule/when/estimated_cost_gt").asDouble()).isEqualTo(50000.0);

            PGNotification[] notes = listen.unwrap(PGConnection.class).getNotifications(5000);
            assertThat(notes).isNotEmpty();
            assertThat(notes[notes.length - 1].getParameter()).startsWith(changed.get("config_version").asText() + ":");

            // same stale ETag again: someone (us) already moved it on
            call("PUT", "/rules/R3", admin, body, "\"" + version + "\"", 409);

            JsonNode history = call("GET", "/rules/R3/history", admin, null, null, 200);
            assertThat(history.get("versions")).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void viewerCanDryRunButNotEdit() throws Exception {
        String viewer = token("viewer", "viewer-dev");
        JsonNode d = call("POST", "/rules/test", viewer, "{\"sql\":\"DELETE FROM rules\",\"user\":\"bob\"}", null, 200);
        assertThat(d.get("decision").asText()).isEqualTo("BLOCK");
        assertThat(d.get("rule_id").asText()).isEqualTo("R1");
        assertThat(d.get("role").asText()).isEqualTo("app");
        call("DELETE", "/rules/R1", viewer, null, null, 403);
    }

    @Test
    void createDisableAndAudit() throws Exception {
        String admin = token("admin", "admin-dev");
        String rule = """
                {"id":"R20","name":"no-audit-log-reads","priority":5,"action":"BLOCK",
                 "when":{"tables_any":["audit_log"],"roles_except":["admin"]},
                 "message":"QueryGuard R20: the audit log is admin-only."}""";
        call("POST", "/rules", admin, rule, null, 201);
        call("POST", "/rules", admin, rule, null, 409);
        JsonNode dry = call("POST", "/rules/test", admin, "{\"sql\":\"SELECT * FROM audit_log\",\"user\":\"bob\"}", null, 200);
        assertThat(dry.get("rule_id").asText()).isEqualTo("R20");
        call("DELETE", "/rules/R20", admin, null, null, 200);
        assertThat(call("GET", "/rules/R20", admin, null, null, 200).get("enabled").asBoolean()).isFalse();
        dry = call("POST", "/rules/test", admin, "{\"sql\":\"SELECT id FROM audit_log WHERE id = 1\",\"user\":\"bob\"}", null, 200);
        assertThat(dry.get("decision").asText()).isEqualTo("ALLOW");
    }

    @Test
    void maskingPolicyValidation() throws Exception {
        String admin = token("admin", "admin-dev");
        call("PUT", "/masking-policies", admin, "[{\"table\":\"customers\",\"column\":\"email; drop\",\"strategy\":\"redact\"}]", null, 400);
        call("PUT", "/masking-policies", admin,
                "[{\"table\":\"customers\",\"column\":\"email\",\"strategy\":\"hash\",\"roles\":[\"analyst\"]}]", null, 200);
        assertThat(call("GET", "/masking-policies", admin, null, null, 200).get(0).get("strategy").asText()).isEqualTo("HASH");
    }
}
