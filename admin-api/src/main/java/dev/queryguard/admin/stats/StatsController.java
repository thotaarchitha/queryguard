package dev.queryguard.admin.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.queryguard.admin.AdminProperties;
import dev.queryguard.admin.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Last-24h summary straight from ClickHouse over its HTTP interface. Plain HTTP + JSON
 * instead of a JDBC driver: three fixed queries don't justify another dependency.
 */
@RestController
public class StatsController {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final AdminProperties.ClickHouse ch;

    public StatsController(ObjectMapper mapper, AdminProperties props) {
        this.mapper = mapper;
        this.ch = props.clickhouse();
    }

    @Operation(summary = "Blocked counts and top rules, last 24 hours")
    @GetMapping("/stats/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", "24h");
        out.put("by_decision", query("""
                SELECT decision, count() AS queries
                FROM qg.query_events WHERE ts > now() - INTERVAL 1 DAY
                GROUP BY decision ORDER BY queries DESC"""));
        out.put("top_rules", query("""
                SELECT rule_id, count() AS fired, uniqExact(user) AS users
                FROM qg.query_events WHERE ts > now() - INTERVAL 1 DAY AND decision != 'ALLOW'
                GROUP BY rule_id ORDER BY fired DESC LIMIT 10"""));
        out.put("top_blocked_users", query("""
                SELECT user, role, countIf(decision = 'BLOCK') AS blocked, count() AS total
                FROM qg.query_events WHERE ts > now() - INTERVAL 1 DAY
                GROUP BY user, role HAVING blocked > 0 ORDER BY blocked DESC LIMIT 10"""));
        return out;
    }

    private JsonNode query(String sql) {
        try {
            String url = ch.url() + "/?default_format=JSON&query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8);
            String auth = Base64.getEncoder().encodeToString((ch.user() + ":" + ch.password()).getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + auth)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ClickHouse said " + resp.statusCode() + ": " + resp.body().lines().findFirst().orElse(""));
            }
            return mapper.readTree(resp.body()).path("data");
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ClickHouse is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted");
        }
    }
}
