package dev.queryguard.common.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs {@code EXPLAIN (FORMAT JSON, GENERIC_PLAN)} and reads the top plan node.
 *
 * <p>EXPLAIN without ANALYZE never executes the statement, and the connection is
 * read-only on top of that. GENERIC_PLAN (Postgres 16+) lets it plan queries that
 * still have {@code $1} placeholders in them, which is every query JDBC sends.
 */
public final class ExplainCostEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PgPools pools;

    public ExplainCostEstimator(PgPools pools) {
        this.pools = pools;
    }

    public CostEstimate estimate(String database, String sql) throws CostUnavailableException {
        try (Connection conn = pools.forDatabase(database).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN (FORMAT JSON, GENERIC_PLAN) " + sql)) {
            if (!rs.next()) {
                throw new CostUnavailableException("EXPLAIN returned nothing");
            }
            JsonNode plan = JSON.readTree(rs.getString(1)).path(0).path("Plan");
            if (plan.isMissingNode()) {
                throw new CostUnavailableException("EXPLAIN output had no Plan");
            }
            return new CostEstimate(plan.path("Total Cost").asDouble(), plan.path("Plan Rows").asDouble());
        } catch (SQLException e) {
            String state = e.getSQLState();
            String why = "57014".equals(state) ? "EXPLAIN timed out" : "EXPLAIN failed: " + e.getMessage();
            throw new CostUnavailableException(why, e);
        } catch (java.io.IOException e) {
            throw new CostUnavailableException("couldn't read EXPLAIN output", e);
        }
    }
}
