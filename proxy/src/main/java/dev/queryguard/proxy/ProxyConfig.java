package dev.queryguard.proxy;

import java.util.Map;

/** Proxy settings, read from environment variables with local-dev defaults. */
public record ProxyConfig(int listenPort, String pgHost, int pgPort, int connectTimeoutMillis) {

    public static ProxyConfig fromEnv() {
        return fromMap(System.getenv());
    }

    static ProxyConfig fromMap(Map<String, String> env) {
        return new ProxyConfig(
                intOr(env, "QG_LISTEN_PORT", 6432),
                env.getOrDefault("QG_PG_HOST", "localhost"),
                intOr(env, "QG_PG_PORT", 5432),
                intOr(env, "QG_CONNECT_TIMEOUT_MS", 3000));
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
