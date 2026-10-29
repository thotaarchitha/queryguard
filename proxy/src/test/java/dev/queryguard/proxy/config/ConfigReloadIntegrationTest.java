package dev.queryguard.proxy.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.proxy.metrics.ProxyMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The proxy's half of live reload, against a real Postgres with the admin API's own schema:
 * write a change the way the admin API does (row + version bump + pg_notify in one transaction)
 * and watch the proxy swap policies.
 */
@Tag("integration")
class ConfigReloadIntegrationTest {

    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16").withDatabaseName("queryguard_config");
    static PolicyHolder holder;
    static ConfigDbWatcher watcher;
    static ProxyMetrics metrics = new ProxyMetrics();

    @BeforeAll
    static void start() throws Exception {
        pg.start();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(Files.readString(Path.of("../admin-api/src/main/resources/db/migration/V1__config_schema.sql")));
        }
        QueryGuardConfig yaml = ConfigMapper.load(Path.of("../infra/rules/default-rules.yml"));
        holder = new PolicyHolder(ActivePolicy.of(0, yaml, "yaml"));
        watcher = new ConfigDbWatcher(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), yaml, holder, metrics);
        watcher.start();
    }

    @AfterAll
    static void stop() {
        watcher.stop();
        pg.stop();
    }

    static Connection connect() throws Exception {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    /** What ConfigChanges in the admin API does, in one transaction. */
    static void publish(String sql) throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sql);
                st.execute("UPDATE config_version SET version = version + 1");
                st.execute("SELECT pg_notify('qg_rules_changed', (SELECT version FROM config_version) || ':' || "
                        + "(extract(epoch FROM clock_timestamp()) * 1000)::bigint)");
            }
            c.commit();
        }
    }

    static boolean eventually(BooleanSupplier condition, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @Test
    void emptyConfigDbLeavesTheYamlPolicyInCharge() throws Exception {
        Thread.sleep(500); // give the watcher time to connect and do its first load
        if (holder.current().version() == 0) {
            assertThat(holder.current().source()).isEqualTo("yaml");
            assertThat(holder.current().engine().rules()).hasSize(8);
        }
    }

    @Test
    void aCommittedChangeIsLiveWithinASecondAndRolledBackOnesNeverAre() throws Exception {
        publish("""
                INSERT INTO rules (id, body, created_by, updated_by) VALUES
                ('R90', '{"id":"R90","action":"BLOCK","priority":1,"when":{"tables_any":["payroll"]},"message":"no"}', 't', 't');
                INSERT INTO role_mappings VALUES ('carol', 'analyst');
                INSERT INTO masking_policies (table_name, column_name, strategy, roles) VALUES ('customers', 'email', 'hash', '["analyst"]');
                """);
        assertThat(eventually(() -> holder.current().engine().rules().stream().anyMatch(r -> r.id().equals("R90")),
                Duration.ofSeconds(1))).as("R90 live within 1 s").isTrue();
        ActivePolicy live = holder.current();
        assertThat(live.source()).isEqualTo("config-db");
        assertThat(live.config().roleFor("carol")).isEqualTo("analyst");
        assertThat(live.config().masking()).singleElement().satisfies(m -> assertThat(m.strategy().name()).isEqualTo("HASH"));
        long version = live.version();

        // A change that's rolled back must never reach the proxy (pg_notify is held until commit).
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("UPDATE rules SET enabled = false WHERE id = 'R90'");
                st.execute("UPDATE config_version SET version = version + 1");
                st.execute("SELECT pg_notify('qg_rules_changed', 'x:0')");
            }
            c.rollback();
        }
        Thread.sleep(500);
        assertThat(holder.current().version()).isEqualTo(version);

        publish("UPDATE rules SET enabled = false WHERE id = 'R90'");
        assertThat(eventually(() -> holder.current().engine().rules().stream().noneMatch(r -> r.id().equals("R90")),
                Duration.ofSeconds(1))).as("disable live within 1 s").isTrue();
        assertThat(holder.current().version()).isEqualTo(version + 1);
    }
}
