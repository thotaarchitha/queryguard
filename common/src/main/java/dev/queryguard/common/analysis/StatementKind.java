package dev.queryguard.common.analysis;

import java.util.Locale;

public enum StatementKind {
    SELECT, INSERT, UPDATE, DELETE, MERGE,
    CREATE, ALTER, DROP, TRUNCATE, GRANT, REVOKE,
    COPY, CALL, DO,
    EXPLAIN,       // plain EXPLAIN only; EXPLAIN ANALYZE is classified as what it runs
    TRANSACTION,   // BEGIN, COMMIT, ROLLBACK, SAVEPOINT...
    SESSION,       // SET, SHOW, RESET, DISCARD, LISTEN...
    OTHER;

    public boolean isWrite() {
        return this == INSERT || this == UPDATE || this == DELETE || this == MERGE;
    }

    public boolean isDdl() {
        return this == CREATE || this == ALTER || this == DROP || this == TRUNCATE
                || this == GRANT || this == REVOKE;
    }

    /** Things EXPLAIN can give us a cost for. */
    public boolean isCostable() {
        return this == SELECT || isWrite();
    }

    /**
     * Kinds we're willing to let through even when the parser gave up on them.
     * A psql {@code \d} query that JSqlParser can't read is still just a SELECT.
     */
    public boolean isSafeWhenUnparsed() {
        return this == SELECT || this == EXPLAIN || this == TRANSACTION || this == SESSION;
    }

    /** Rule files can say {@code DDL} or {@code WRITE} instead of listing every kind. */
    public boolean matches(String token) {
        String t = token.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "DDL" -> isDdl();
            case "WRITE" -> isWrite();
            case "ANY", "*" -> true;
            default -> name().equals(t);
        };
    }

    public static StatementKind fromKeyword(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "select", "values", "table" -> SELECT;
            case "insert" -> INSERT;
            case "update" -> UPDATE;
            case "delete" -> DELETE;
            case "merge" -> MERGE;
            case "create" -> CREATE;
            case "alter", "comment", "vacuum", "reindex", "cluster", "refresh" -> ALTER;
            case "drop" -> DROP;
            case "truncate" -> TRUNCATE;
            case "grant" -> GRANT;
            case "revoke" -> REVOKE;
            case "copy" -> COPY;
            case "call" -> CALL;
            case "do" -> DO;
            case "explain" -> EXPLAIN;
            case "begin", "start", "commit", "end", "rollback", "abort", "savepoint", "release" -> TRANSACTION;
            case "set", "show", "reset", "discard", "listen", "unlisten", "deallocate" -> SESSION;
            default -> OTHER;
        };
    }
}
