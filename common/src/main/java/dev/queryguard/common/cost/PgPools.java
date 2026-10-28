package dev.queryguard.common.cost;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * One small read-only pool per database, for the QueryGuard service account.
 * Pools are created lazily, the first time a client connects to that database.
 */
public final class PgPools implements AutoCloseable {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final int statementTimeoutMs;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public PgPools(String host, int port, String user, String password, int statementTimeoutMs) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.statementTimeoutMs = statementTimeoutMs;
    }

    public DataSource forDatabase(String database) {
        return pools.computeIfAbsent(database, this::create);
    }

    private HikariDataSource create(String database) {
        HikariConfig c = new HikariConfig();
        // Simple query mode: EXPLAIN (GENERIC_PLAN) with $1 in it must not go through
        // Parse/Bind, or the driver would try to bind parameters we don't have.
        c.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?preferQueryMode=simple&ApplicationName=queryguard&connectTimeout=2");
        c.setUsername(user);
        c.setPassword(password);
        c.setPoolName("qg-" + database);
        c.setMaximumPoolSize(4);
        c.setMinimumIdle(0);
        c.setConnectionTimeout(1000);
        c.setInitializationFailTimeout(-1);
        c.setReadOnly(true);
        c.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs);
        return new HikariDataSource(c);
    }

    @Override
    public void close() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
