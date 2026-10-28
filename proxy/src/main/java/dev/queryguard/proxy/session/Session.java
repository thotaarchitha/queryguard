package dev.queryguard.proxy.session;

import dev.queryguard.common.analysis.QueryInfo;
import dev.queryguard.common.masking.MaskStrategy;
import dev.queryguard.common.rules.Decision;
import dev.queryguard.proxy.events.QueryEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-connection state. Only ever touched from the connection's event loop thread
 * (decisions run elsewhere but hand their result back to the loop), so no locking.
 */
public final class Session {

    /** What we decided when a statement was Parsed, reused for every Execute of it. */
    public static final class Prepared {
        private final QueryInfo query;
        private final Decision decision;
        private final QueryEvent template;
        private boolean executed;

        public Prepared(QueryInfo query, Decision decision, QueryEvent template) {
            this.query = query;
            this.decision = decision;
            this.template = template;
        }

        public QueryInfo query() {
            return query;
        }

        public Decision decision() {
            return decision;
        }

        /** The first Execute gets the event that carries the decision latency; later ones get copies with 0. */
        public QueryEvent eventForExecute(String id, String ts) {
            if (!executed) {
                executed = true;
                return template;
            }
            return template.copyForExecute(id, ts);
        }
    }

    private final String clientIp;
    private String user;
    private String database;
    private String application;
    private String role;
    private char txStatus = 'I';

    private final Map<String, Prepared> statements = new HashMap<>();
    private final Map<String, String> portals = new HashMap<>();
    /** Masks from the RowDescription of each prepared statement, for Executes that skip Describe. */
    private final Map<String, MaskStrategy[]> statementMasks = new HashMap<>();
    private final Map<String, java.util.List<String>> statementMaskedColumns = new HashMap<>();

    public Session(String clientIp) {
        this.clientIp = clientIp;
    }

    public void start(Map<String, String> params, String role) {
        this.user = params.get("user");
        this.database = params.getOrDefault("database", user);
        this.application = params.getOrDefault("application_name", "");
        this.role = role;
    }

    public String clientIp() {
        return clientIp;
    }

    public String user() {
        return user;
    }

    public String database() {
        return database;
    }

    public String application() {
        return application;
    }

    public String role() {
        return role;
    }

    /** Role can change on a config reload (new role_mapping). */
    public void role(String newRole) {
        this.role = newRole;
    }

    public char txStatus() {
        return txStatus;
    }

    public void txStatus(char status) {
        this.txStatus = status;
    }

    public void prepared(String name, Prepared p) {
        statements.put(name, p);
        statementMasks.remove(name);
        statementMaskedColumns.remove(name);
    }

    public Prepared prepared(String name) {
        return statements.get(name);
    }

    public void closeStatement(String name) {
        statements.remove(name);
        statementMasks.remove(name);
        statementMaskedColumns.remove(name);
    }

    public void bind(String portal, String statement) {
        portals.put(portal, statement);
    }

    public String statementForPortal(String portal) {
        return portals.get(portal);
    }

    public void closePortal(String portal) {
        portals.remove(portal);
    }

    public void masks(String statement, MaskStrategy[] masks, java.util.List<String> columns) {
        if (masks == null) {
            statementMasks.remove(statement);
            statementMaskedColumns.remove(statement);
        } else {
            statementMasks.put(statement, masks);
            statementMaskedColumns.put(statement, columns);
        }
    }

    public MaskStrategy[] masks(String statement) {
        return statementMasks.get(statement);
    }

    public java.util.List<String> maskedColumns(String statement) {
        return statementMaskedColumns.getOrDefault(statement, java.util.List.of());
    }

    @Override
    public String toString() {
        return "user=" + user + " db=" + database + " role=" + role + " ip=" + clientIp;
    }
}
