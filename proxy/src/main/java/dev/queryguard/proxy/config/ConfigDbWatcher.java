package dev.queryguard.proxy.config;

import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.masking.MaskRule;
import dev.queryguard.common.rules.Rule;
import dev.queryguard.proxy.metrics.ProxyMetrics;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live reload without polling the tables: one connection sits on {@code LISTEN qg_rules_changed}.
 * The admin API bumps config_version and runs {@code pg_notify} in the same transaction, so the
 * notification only arrives once the change is committed and visible.
 *
 * <p>The payload is {@code version:epochMillis}. The millis are when the admin API committed,
 * which lets us report how long the change took to go live ({@code qg_config_reload_lag_ms}).
 *
 * <p>On any error: back off, reconnect, and do a full reload, because notifications sent while
 * we were disconnected are gone.
 */
public final class ConfigDbWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigDbWatcher.class);
    public static final String CHANNEL = "qg_rules_changed";

    private final String url;
    private final String user;
    private final String password;
    private final QueryGuardConfig base;
    private final PolicyHolder holder;
    private final ProxyMetrics metrics;
    private volatile boolean running = true;
    private Thread thread;

    public ConfigDbWatcher(String url, String user, String password, QueryGuardConfig base,
                           PolicyHolder holder, ProxyMetrics metrics) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.base = base;
        this.holder = holder;
        this.metrics = metrics;
    }

    public void start() {
        thread = Thread.ofVirtual().name("qg-config-watcher").start(this::run);
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void run() {
        long backoffMs = 500;
        while (running) {
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                try (Statement st = conn.createStatement()) {
                    st.execute("LISTEN " + CHANNEL);
                }
                reload(conn, -1);
                backoffMs = 500;
                PGConnection pg = conn.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] notes = pg.getNotifications(10_000);
                    if (notes == null || notes.length == 0) {
                        continue;
                    }
                    // Several changes in a burst only need one reload.
                    long committedAt = parseMillis(notes[notes.length - 1].getParameter());
                    reload(conn, committedAt);
                }
            } catch (SQLException e) {
                if (!running) {
                    return;
                }
                log.warn("Config DB unavailable ({}); keeping policy v{} and retrying in {} ms",
                        e.getMessage(), holder.current().version(), backoffMs);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 10_000);
            }
        }
    }

    void reload(Connection conn, long committedAtMillis) throws SQLException {
        long t0 = System.nanoTime();
        long version;
        List<Rule> rules = new ArrayList<>();
        List<MaskRule> masking = new ArrayList<>();
        Map<String, String> roles = new HashMap<>();

        // One read-only REPEATABLE READ transaction so rules, masking and roles are from the same moment.
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT version FROM config_version")) {
                version = rs.next() ? rs.getLong(1) : 0;
            }
            try (ResultSet rs = st.executeQuery("SELECT body::text FROM rules WHERE enabled")) {
                while (rs.next()) {
                    rules.add(ConfigMapper.json().readValue(rs.getString(1), Rule.class));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT table_name, column_name, strategy, roles::text, except_roles::text FROM masking_policies")) {
                while (rs.next()) {
                    masking.add(new MaskRule(rs.getString(1), rs.getString(2),
                            dev.queryguard.common.masking.MaskStrategy.valueOf(rs.getString(3).toUpperCase(java.util.Locale.ROOT)),
                            readList(rs.getString(4)), readList(rs.getString(5))));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT db_user, role FROM role_mappings")) {
                while (rs.next()) {
                    roles.put(rs.getString(1), rs.getString(2));
                }
            }
            conn.commit();
        } catch (java.io.IOException e) {
            conn.rollback();
            log.error("Config DB has a rule I can't read; keeping v{}: {}", holder.current().version(), e.getMessage());
            return;
        } finally {
            conn.setAutoCommit(auto);
        }

        if (version == 0) {
            // Admin API hasn't seeded anything yet. The YAML policy stays in charge.
            return;
        }
        if (version == holder.current().version() && committedAtMillis < 0) {
            return;
        }
        holder.swap(ActivePolicy.of(version, base.withPolicy(roles, rules, masking), "config-db"));
        long lag = committedAtMillis > 0 ? System.currentTimeMillis() - committedAtMillis : 0;
        metrics.configLoaded(version, lag);
        log.info("Policy v{} live: {} rules, {} masked columns, {} role mappings (load {} ms{})",
                version, rules.size(), masking.size(), roles.size(), (System.nanoTime() - t0) / 1_000_000,
                committedAtMillis > 0 ? ", " + lag + " ms after commit" : "");
    }

    private static List<String> readList(String json) throws java.io.IOException {
        if (json == null) {
            return null;
        }
        return ConfigMapper.json().readValue(json,
                ConfigMapper.json().getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private static long parseMillis(String payload) {
        if (payload == null) {
            return -1;
        }
        int colon = payload.indexOf(':');
        try {
            return colon < 0 ? -1 : Long.parseLong(payload.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
