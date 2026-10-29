package dev.queryguard.common.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.statement.Statement;

/**
 * What we know about one statement. Filled in two passes: the lexer always runs,
 * then the parser overwrites whatever it can do better. If the parser failed,
 * {@link #parsed()} is false and the lexer's guesses are all we have.
 */
public final class StatementInfo {

    /** One column reference; {@code direct} means it's a bare item in the top-level select list. */
    public record ColumnUse(String qualifier, String name, boolean direct) {
    }

    StatementKind kind = StatementKind.OTHER;
    final String sql;
    final List<SqlLexer.Token> tokens;
    String fingerprint;
    boolean parsed;
    boolean explainAnalyze;
    Set<String> tables = new LinkedHashSet<>();
    Set<String> functions = new LinkedHashSet<>();
    boolean hasWhere;
    boolean hasLimit;
    boolean selectStar;
    boolean hasCrossJoin;
    boolean setOperation;
    boolean lockingClause;
    List<ColumnUse> columns = new ArrayList<>();
    Map<String, String> aliases = new HashMap<>();
    Statement ast;
    /** Set when the facts came from the shape cache: parse this query's own text only if someone needs the AST. */
    java.util.concurrent.Callable<Statement> astLoader;
    Integer addedLimit;

    StatementInfo(String sql, List<SqlLexer.Token> tokens) {
        this.sql = sql;
        this.tokens = tokens;
    }

    public StatementKind kind() {
        return kind;
    }

    /** The statement's own text, sliced from the original query. This is what EXPLAIN sees. */
    public String sql() {
        return sql;
    }

    public List<SqlLexer.Token> tokens() {
        return tokens;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public boolean parsed() {
        return parsed;
    }

    public boolean explainAnalyze() {
        return explainAnalyze;
    }

    public Set<String> tables() {
        return tables;
    }

    public Set<String> functions() {
        return functions;
    }

    public boolean hasWhere() {
        return hasWhere;
    }

    public boolean hasLimit() {
        return hasLimit;
    }

    public boolean selectStar() {
        return selectStar;
    }

    public boolean hasCrossJoin() {
        return hasCrossJoin;
    }

    public boolean setOperation() {
        return setOperation;
    }

    public boolean lockingClause() {
        return lockingClause;
    }

    public List<ColumnUse> columns() {
        return columns;
    }

    /** alias (or bare table name) to table name, lowercased. */
    public Map<String, String> aliases() {
        return aliases;
    }

    public Statement ast() {
        if (ast == null && astLoader != null) {
            try {
                ast = astLoader.call();
            } catch (Exception e) {
                // Same shape parsed fine before, so this is unexpected. No AST means no rewrite,
                // which is the safe direction.
                astLoader = null;
            }
        }
        return ast;
    }

    /** Set by a REWRITE rule: the LIMIT to append. Several rules can stack; the smallest wins. */
    public Integer addedLimit() {
        return addedLimit;
    }

    public void addLimit(int limit) {
        addedLimit = addedLimit == null ? limit : Math.min(addedLimit, limit);
    }

    @Override
    public String toString() {
        return kind + (parsed ? "" : "(unparsed)") + " tables=" + tables;
    }
}
