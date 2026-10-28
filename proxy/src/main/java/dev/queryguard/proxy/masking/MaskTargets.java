package dev.queryguard.proxy.masking;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.queryguard.common.masking.MaskRule;
import dev.queryguard.common.masking.MaskStrategy;
import dev.queryguard.common.cost.PgPools;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps {@code (table OID, column number)} to the mask rule for that column, per database.
 *
 * <p>This is the "real identity" part: a RowDescription tells us which table column each
 * result column came from, independent of what the query called it. So {@code SELECT email
 * AS e} is still {@code customers.email} here. Renaming can't get around it.
 *
 * <p>Loaded lazily the first time a database is used, always off the event loop (from the
 * decision step, which runs on a virtual thread). Keyed by config version too, so a masking
 * policy change takes effect on the next query.
 */
public final class MaskTargets {

    private static final Logger log = LoggerFactory.getLogger(MaskTargets.class);

    public static final class Resolved {
        private final Map<Long, MaskRule> byColumn;

        Resolved(Map<Long, MaskRule> byColumn) {
            this.byColumn = byColumn;
        }

        public MaskRule ruleFor(long tableOid, int attnum) {
            return byColumn.get(key(tableOid, attnum));
        }

        public boolean isEmpty() {
            return byColumn.isEmpty();
        }

        public int size() {
            return byColumn.size();
        }
    }

    private static final Resolved NONE = new Resolved(Map.of());

    private final PgPools pools;
    private final Cache<String, Resolved> cache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(10)) // picks up dropped/recreated tables eventually
            .build();

    public MaskTargets(PgPools pools) {
        this.pools = pools;
    }

    /** Latest resolved set per database, whatever config version it was loaded for. */
    private final Map<String, Resolved> latest = new java.util.concurrent.ConcurrentHashMap<>();

    /** Blocking. Call from a virtual thread, never from the event loop. */
    public Resolved load(String database, long configVersion, List<MaskRule> rules) {
        if (rules.isEmpty()) {
            return NONE;
        }
        if (pools == null) {
            throw new IllegalStateException("masking is configured but there's no metadata connection (QG_EXPLAIN_USER unset)");
        }
        Resolved r = cache.get(database + "@" + configVersion, k -> query(database, rules));
        latest.put(database, r);
        return r;
    }

    /**
     * Non-blocking, for the event loop. Deliberately not keyed by config version: if the
     * policy is reloaded between a query's decision and its RowDescription, masking with
     * the previous version's columns is right; masking with nothing would be a leak.
     */
    public Resolved peek(String database) {
        Resolved r = latest.get(database);
        return r == null ? NONE : r;
    }

    private Resolved query(String database, List<MaskRule> rules) {
        String sql = """
                SELECT c.oid, a.attnum, n.nspname, c.relname, a.attname
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relname = ANY (?)
                """;
        Map<Long, MaskRule> map = new HashMap<>();
        try (Connection conn = pools.forDatabase(database).getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Array tables = conn.createArrayOf("text", rules.stream().map(MaskRule::tableName).distinct().toArray());
            ps.setArray(1, tables);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String schema = rs.getString(3);
                    String table = rs.getString(4);
                    String column = rs.getString(5);
                    for (MaskRule r : rules) {
                        if (r.tableName().equals(table) && r.columnName().equals(column) && r.schemaName().equals(schema)) {
                            map.put(key(rs.getLong(1), rs.getInt(2)), r);
                        }
                    }
                }
            }
            log.info("Masking: resolved {} column(s) in database {}", map.size(), database);
            return new Resolved(Map.copyOf(map));
        } catch (SQLException e) {
            // Don't cache the failure; the next query tries again. Meanwhile the masking
            // guard still blocks the expression tricks, but plain SELECTs would go out
            // unmasked, so say so loudly.
            log.error("Masking: couldn't resolve masked columns in {}: {}", database, e.getMessage());
            throw new IllegalStateException("masking metadata unavailable for " + database, e);
        }
    }

    public static MaskStrategy[] plan(List<RowDescription.Field> fields, Resolved resolved, String role) {
        MaskStrategy[] masks = null;
        for (int i = 0; i < fields.size(); i++) {
            RowDescription.Field f = fields.get(i);
            if (f.tableOid() == 0) {
                continue;
            }
            MaskRule rule = resolved.ruleFor(f.tableOid(), f.attnum());
            if (rule == null || !rule.appliesTo(role)) {
                continue;
            }
            if (masks == null) {
                masks = new MaskStrategy[fields.size()];
            }
            // Binary-format values (extended protocol, some drivers) can't be rewritten as
            // text without breaking the client's decoder, so they become NULL.
            masks[i] = f.format() == 1 ? MaskStrategy.NULLIFY : rule.strategy();
        }
        return masks;
    }

    private static long key(long tableOid, int attnum) {
        return (tableOid << 16) | (attnum & 0xFFFF);
    }
}
