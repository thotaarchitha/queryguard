package dev.queryguard.proxy;

import java.util.Map;

/**
 * Process-level settings from environment variables. Policy (rules, masking, roles) is not
 * here; that lives in the rules YAML or the config database and can change at runtime.
 */
public record ProxyConfig(
        int listenPort,
        String pgHost,
        int pgPort,
        int connectTimeoutMillis,
        int maxConnections,
        int idleTimeoutSeconds,
        int adminPort,
        String rulesFile,
        String explainUser,
        String explainPassword,
        String configDbUrl,
        String configDbUser,
        String configDbPassword,
        String kafkaBootstrap,
        String kafkaTopic,
        String instanceName,
        int shutdownGraceSeconds) {

    public static ProxyConfig fromEnv() {
        return fromMap(System.getenv());
    }

    public static ProxyConfig fromMap(Map<String, String> env) {
        return new ProxyConfig(
                intOr(env, "QG_LISTEN_PORT", 6432),
                env.getOrDefault("QG_PG_HOST", "localhost"),
                intOr(env, "QG_PG_PORT", 5432),
                intOr(env, "QG_CONNECT_TIMEOUT_MS", 3000),
                intOr(env, "QG_MAX_CONNECTIONS", 2000),
                intOr(env, "QG_IDLE_TIMEOUT_S", 600),
                intOr(env, "QG_ADMIN_PORT", 8081),
                env.get("QG_RULES_FILE"),
                env.get("QG_EXPLAIN_USER"),
                env.get("QG_EXPLAIN_PASSWORD"),
                env.get("QG_CONFIG_DB_URL"),
                env.get("QG_CONFIG_DB_USER"),
                env.get("QG_CONFIG_DB_PASSWORD"),
                env.get("QG_KAFKA_BOOTSTRAP"),
                env.getOrDefault("QG_KAFKA_TOPIC", "qg.query-events"),
                env.getOrDefault("QG_INSTANCE", env.getOrDefault("HOSTNAME", "qg-local")),
                intOr(env, "QG_SHUTDOWN_GRACE_S", 30));
    }

    private static int intOr(Map<String, String> env, String key, int fallback) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + value + "'", e);
        }
    }
}
