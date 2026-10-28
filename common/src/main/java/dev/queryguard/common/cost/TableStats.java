package dev.queryguard.common.cost;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.queryguard.common.analysis.StatementInfo;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plan B for cost rules: Postgres' own size statistics from pg_class, refreshed every
 * five minutes per database. Used only when EXPLAIN fails or times out.
 *
 * <p>The estimate is a deliberate upper bound: what a sequential scan of every table in the
 * statement would cost, using the planner's default seq_page_cost (1.0) and cpu_tuple_cost
 * (0.01). It ignores WHERE clauses and indexes, so it overestimates selective queries. That's
 * the right direction to be wrong in: EXPLAIN only fails like this when the database is
 * already struggling, which is exactly when an expensive query shouldn't slip through.
 */
public final class TableStats {

    record Size(double rows, double pages) {
    }

    private final PgPools pools;
    private final Cache<String, Map<String, Size>> byDatabase = Caffeine.newBuilder()
            .maximumSize(200)
            .refreshAfterWrite(Duration.ofMinutes(5))
            .expireAfterWrite(Duration.ofMinutes(30))
            .build(this::load);

    public TableStats(PgPools pools) {
        this.pools = pools;
    }

    /** Blocking (first call per database runs a catalog query). Call off the event loop. */
    public CostEstimate upperBound(String database, StatementInfo st) throws CostUnavailableException {
        Map<String, Size> sizes;
        try {
            sizes = byDatabase.get(database, this::load);
        } catch (RuntimeException e) {
            throw new CostUnavailableException("EXPLAIN and table statistics both unavailable", e);
        }
        double cost = 0;
        double rows = 0;
        int known = 0;
        for (String table : st.tables()) {
            Size s = sizes.get(lastSegment(table));
            if (s == null) {
                continue;
            }
            known++;
            cost += s.pages() + 0.01 * s.rows();
            rows = Math.max(rows, s.rows());
        }
        if (known == 0) {
            throw new CostUnavailableException("EXPLAIN unavailable and no statistics for " + st.tables());
        }
        return new CostEstimate(cost, rows, "table-stats");
    }

    public void prefetch(String database) {
        try {
            byDatabase.get(database, this::load);
        } catch (RuntimeException ignored) {
            // the next upperBound() call will try again
        }
    }

    private Map<String, Size> load(String database) {
        Map<String, Size> out = new HashMap<>();
        try (Connection c = pools.forDatabase(database).getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT c.relname, greatest(c.reltuples, 0), c.relpages
                     FROM pg_catalog.pg_class c
                     JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                     WHERE c.relkind IN ('r', 'p', 'm') AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                     """)) {
            while (rs.next()) {
                // same-named tables in two schemas: keep the bigger one (upper bound, again)
                out.merge(rs.getString(1).toLowerCase(Locale.ROOT), new Size(rs.getDouble(2), rs.getDouble(3)),
                        (a, b) -> a.rows() >= b.rows() ? a : b);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("couldn't read pg_class for " + database, e);
        }
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
