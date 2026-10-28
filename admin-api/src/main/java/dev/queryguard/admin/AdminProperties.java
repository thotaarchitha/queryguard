package dev.queryguard.admin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "queryguard")
public record AdminProperties(
        String seedFile,
        String jwtSecret,
        Duration tokenTtl,
        Map<String, User> users,
        Shop shop,
        ClickHouse clickhouse) {

    public record User(String password, List<String> roles) {
    }

    /** Where dry runs send EXPLAIN, so /rules/test gives the same answer the proxy would. */
    public record Shop(String host, int port, String database, String explainUser, String explainPassword) {
    }

    public record ClickHouse(String url, String user, String password) {
    }
}
