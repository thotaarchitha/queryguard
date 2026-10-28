package dev.queryguard.common.analysis;

import dev.queryguard.common.analysis.SqlLexer.Token;
import dev.queryguard.common.analysis.SqlLexer.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Turns SQL text into {@link QueryInfo}. Thread-safe (the shape cache is a concurrent Caffeine cache).
 *
 * <p>Each statement is lexed first and parsed second. Parsing per statement (instead of
 * handing JSqlParser the whole multi-statement string) means one weird statement doesn't
 * take the analysis of the others down with it, and each statement keeps its exact
 * original text for EXPLAIN.
 */
public final class QueryAnalyzer {

    private static final Set<String> CLAUSE_WORDS = Set.of(
            "where", "group", "order", "limit", "offset", "having", "window", "union", "intersect",
            "except", "on", "using", "join", "inner", "left", "right", "full", "cross", "natural",
            "set", "returning", "values", "fetch", "for", "as", "lateral", "only");

    private final com.github.benmanes.caffeine.cache.Cache<String, AstFacts> shapes;

    /** Caches parse results for up to 20k query shapes. */
    public QueryAnalyzer() {
        this(20_000);
    }

    /** {@code shapeCacheSize} 0 turns the cache off (benchmarks use that to measure raw parsing). */
    public QueryAnalyzer(int shapeCacheSize) {
        this.shapes = shapeCacheSize <= 0 ? null
                : com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(shapeCacheSize).build();
    }

    public QueryInfo analyze(String sql) {
        List<Token> tokens = SqlLexer.tokenize(sql);
        String normalized = SqlLexer.normalize(tokens);
        List<StatementInfo> infos = new ArrayList<>();
        for (List<Token> part : SqlLexer.splitStatements(tokens)) {
            infos.add(analyzeStatement(sql, part));
        }
        return new QueryInfo(sql, normalized, Fingerprints.of(normalized), infos);
    }

    private StatementInfo analyzeStatement(String fullSql, List<Token> part) {
        String slice = fullSql.substring(part.get(0).start(), part.get(part.size() - 1).end());

        if (part.get(0).isWord("explain")) {
            return analyzeExplain(fullSql, slice, part);
        }

        StatementInfo info = new StatementInfo(slice, part);
        String normalized = SqlLexer.normalize(part);
        info.fingerprint = Fingerprints.of(normalized);
        fillFromTokens(info, part);

        // Everything the rules look at is the same for every query of the same shape, so
        // JSqlParser runs once per shape, not once per query. The AST itself is only needed
        // to rewrite, and then it's parsed from this query's own text, on demand.
        AstFacts cached = shapes == null ? null : shapes.getIfPresent(normalized);
        if (cached != null) {
            cached.applyTo(info);
            if (info.parsed) {
                info.astLoader = () -> CCJSqlParserUtil.parse(forParser(slice, part));
            }
            return info;
        }
        try {
            Statement st = CCJSqlParserUtil.parse(forParser(slice, part));
            fillFromAst(info, st);
            info.ast = st;
            info.parsed = true;
        } catch (Exception | StackOverflowError e) {
            // Postgres has a lot of syntax JSqlParser doesn't know (psql's \d queries use
            // OPERATOR(pg_catalog.~), for one). The lexer view stays; the engine decides
            // what an unparsed statement is allowed to do.
            info.parsed = false;
        }
        if (shapes != null) {
            shapes.put(normalized, AstFacts.of(info));
        }
        return info;
    }

    /** The literal-independent part of a parse, shared by every query with the same normalized text. */
    private record AstFacts(boolean parsed, StatementKind kind, Set<String> tables, Set<String> functions,
                            Map<String, String> aliases, List<StatementInfo.ColumnUse> columns, boolean hasWhere,
                            boolean hasLimit, boolean selectStar, boolean hasCrossJoin, boolean setOperation) {

        static AstFacts of(StatementInfo i) {
            return new AstFacts(i.parsed, i.kind, Set.copyOf(i.tables), Set.copyOf(i.functions), Map.copyOf(i.aliases),
                    List.copyOf(i.columns), i.hasWhere, i.hasLimit, i.selectStar, i.hasCrossJoin, i.setOperation);
        }

        void applyTo(StatementInfo i) {
            i.parsed = parsed;
            if (!parsed) {
                return; // lexer facts already filled in; nothing better to add
            }
            i.kind = kind;
            i.tables = new java.util.LinkedHashSet<>(tables);
            i.functions = new java.util.LinkedHashSet<>(functions);
            i.aliases = new HashMap<>(aliases);
            i.columns = new ArrayList<>(columns);
            i.hasWhere = hasWhere;
            i.hasLimit = hasLimit;
            i.selectStar = selectStar;
            i.hasCrossJoin = hasCrossJoin;
            i.setOperation = setOperation;
        }
    }

    /**
     * {@code EXPLAIN SELECT} is harmless; {@code EXPLAIN ANALYZE DELETE FROM orders} deletes
     * everything. So with ANALYZE we classify the statement as whatever it runs.
     */
    private StatementInfo analyzeExplain(String fullSql, String slice, List<Token> part) {
        int k = 1;
        boolean analyze = false;
        if (k < part.size() && part.get(k).isPunct("(")) {
            int depth = 0;
            for (; k < part.size(); k++) {
                Token t = part.get(k);
                if (t.isPunct("(")) {
                    depth++;
                } else if (t.isPunct(")")) {
                    depth--;
                    if (depth == 0) {
                        k++;
                        break;
                    }
                } else if (t.isWord("analyze")) {
                    boolean off = k + 1 < part.size()
                            && (part.get(k + 1).isWord("false") || part.get(k + 1).isWord("off"));
                    analyze = analyze || !off;
                }
            }
        } else {
            while (k < part.size() && (part.get(k).isWord("analyze") || part.get(k).isWord("analyse")
                    || part.get(k).isWord("verbose"))) {
                analyze = analyze || !part.get(k).isWord("verbose");
                k++;
            }
        }
        if (k >= part.size()) {
            StatementInfo bare = new StatementInfo(slice, part);
            bare.kind = StatementKind.EXPLAIN;
            bare.fingerprint = Fingerprints.of(SqlLexer.normalize(part));
            return bare;
        }
        StatementInfo inner = analyzeStatement(fullSql, part.subList(k, part.size()));
        if (analyze) {
            inner.explainAnalyze = true;
            return inner;
        }
        inner.kind = StatementKind.EXPLAIN;
        return inner;
    }

    /** JSqlParser reads {@code ?1} but not {@code $1}. Same length, so offsets stay valid. */
    static String forParser(String slice, List<Token> part) {
        int base = part.get(0).start();
        char[] chars = null;
        for (Token t : part) {
            if (t.type() == Type.PARAM) {
                if (chars == null) {
                    chars = slice.toCharArray();
                }
                chars[t.start() - base] = '?';
            }
        }
        return chars == null ? slice : new String(chars);
    }

    // ---- lexer pass ----------------------------------------------------------------------

    private static void fillFromTokens(StatementInfo info, List<Token> toks) {
        int[] depth = depths(toks);
        Token first = toks.get(0);
        info.kind = first.type() == Type.WORD ? StatementKind.fromKeyword(first.text()) : StatementKind.OTHER;

        if (first.isWord("with")) {
            info.kind = StatementKind.SELECT;
            for (int k = 1; k < toks.size(); k++) {
                if (depth[k] != 0 || toks.get(k).type() != Type.WORD) {
                    continue;
                }
                String w = toks.get(k).text().toLowerCase(Locale.ROOT);
                if (w.equals("select") || w.equals("insert") || w.equals("update")
                        || w.equals("delete") || w.equals("merge")) {
                    info.kind = StatementKind.fromKeyword(w);
                    break;
                }
            }
        }

        boolean inSelectList = false;
        for (int k = 0; k < toks.size(); k++) {
            Token t = toks.get(k);
            Token next = k + 1 < toks.size() ? toks.get(k + 1) : null;
            Token prev = k > 0 ? toks.get(k - 1) : null;

            if (t.type() == Type.WORD && next != null && next.isPunct("(")) {
                info.functions.add(t.text().toLowerCase(Locale.ROOT));
                if (prev != null && prev.isPunct(".") && k >= 2) {
                    info.functions.add(toks.get(k - 2).ident() + "." + t.text().toLowerCase(Locale.ROOT));
                }
            }
            if (depth[k] != 0) {
                continue;
            }
            if (t.isWord("where")) {
                info.hasWhere = true;
            } else if (t.isWord("limit") || t.isWord("fetch")) {
                info.hasLimit = true;
            } else if (t.isWord("cross") && next != null && next.isWord("join")) {
                info.hasCrossJoin = true;
            } else if (t.isWord("union") || t.isWord("intersect") || t.isWord("except")) {
                info.setOperation = true;
            } else if (t.isWord("for") && next != null && (next.isWord("update") || next.isWord("share")
                    || next.isWord("no") || next.isWord("key"))) {
                info.lockingClause = true;
            }

            if (t.isWord("select")) {
                inSelectList = true;
            } else if (t.isWord("from")) {
                inSelectList = false;
            } else if (inSelectList && t.isPunct("*") && prev != null
                    && (prev.isWord("select") || prev.isPunct(",") || prev.isPunct(".") || prev.isWord("distinct"))) {
                info.selectStar = true;
            }

            if (t.isWord("from") || t.isWord("join") || t.isWord("into") || t.isWord("table")
                    || t.isWord("truncate") || t.isWord("copy")
                    || (t.isWord("update") && (k == 0 || info.kind == StatementKind.UPDATE))) {
                collectTableNames(info, toks, k + 1);
            }
        }
    }

    /** Reads {@code [only] [if exists] a.b [alias] [, c]} after FROM/JOIN/TABLE etc. */
    private static void collectTableNames(StatementInfo info, List<Token> toks, int k) {
        while (k < toks.size()) {
            while (k < toks.size() && (toks.get(k).isWord("only") || toks.get(k).isWord("if")
                    || toks.get(k).isWord("exists") || toks.get(k).isWord("lateral"))) {
                k++;
            }
            if (k >= toks.size()) {
                return;
            }
            Token t = toks.get(k);
            if (t.type() != Type.WORD && t.type() != Type.QUOTED_IDENT) {
                return;
            }
            if (CLAUSE_WORDS.contains(t.text().toLowerCase(Locale.ROOT)) && t.type() == Type.WORD) {
                return;
            }
            String name = t.ident();
            k++;
            while (k + 1 < toks.size() && toks.get(k).isPunct(".")) {
                name = name + "." + toks.get(k + 1).ident();
                k += 2;
            }
            info.tables.add(name);
            // optional alias
            if (k < toks.size() && toks.get(k).isWord("as")) {
                k++;
            }
            if (k < toks.size() && toks.get(k).type() == Type.WORD
                    && !CLAUSE_WORDS.contains(toks.get(k).text().toLowerCase(Locale.ROOT))) {
                info.aliases.put(toks.get(k).ident(), name);
                k++;
            }
            info.aliases.putIfAbsent(lastSegment(name), name);
            if (k < toks.size() && toks.get(k).isPunct(",")) {
                k++;
                continue;
            }
            return;
        }
    }

    private static int[] depths(List<Token> toks) {
        int[] d = new int[toks.size()];
        int depth = 0;
        for (int k = 0; k < toks.size(); k++) {
            Token t = toks.get(k);
            if (t.isPunct(")")) {
                depth = Math.max(0, depth - 1);
            }
            d[k] = depth;
            if (t.isPunct("(")) {
                depth++;
            }
        }
        return d;
    }

    // ---- parser pass ---------------------------------------------------------------------

    private static void fillFromAst(StatementInfo info, Statement st) {
        info.kind = kindOf(st, info.kind);

        Select rootSelect = st instanceof Select s ? unwrap(s) : null;
        Inspector inspector = new Inspector(rootSelect instanceof PlainSelect ps ? ps : null);
        try {
            Set<String> found = inspector.getTables(st);
            info.tables.clear();
            found.forEach(t -> info.tables.add(t.toLowerCase(Locale.ROOT).replace("\"", "")));
        } catch (RuntimeException e) {
            // TablesNamesFinder throws for statement types it doesn't support (some DDL).
            // The lexer's table list is still there.
        }
        info.functions.addAll(inspector.functions);
        info.aliases.putAll(inspector.aliases);
        info.hasCrossJoin = inspector.crossJoin;
        info.setOperation = inspector.setOperation;
        info.columns.clear();
        for (Column c : inspector.columns) {
            String qualifier = c.getTable() != null && c.getTable().getName() != null
                    ? c.getTable().getName().toLowerCase(Locale.ROOT).replace("\"", "")
                    : null;
            String name = c.getColumnName().toLowerCase(Locale.ROOT).replace("\"", "");
            info.columns.add(new StatementInfo.ColumnUse(qualifier, name, inspector.direct.contains(c)));
        }

        if (st instanceof Select s) {
            info.hasLimit = s.getLimit() != null || s.getFetch() != null;
            info.hasWhere = rootSelect instanceof PlainSelect ps && ps.getWhere() != null;
            info.selectStar = rootSelect instanceof PlainSelect ps && ps.getSelectItems() != null
                    && ps.getSelectItems().stream().anyMatch(item -> item.getExpression() instanceof AllColumns);
        } else if (st instanceof Update u) {
            info.hasWhere = u.getWhere() != null;
        } else if (st instanceof Delete d) {
            info.hasWhere = d.getWhere() != null;
        }
    }

    private static Select unwrap(Select s) {
        Select cur = s;
        while (cur instanceof ParenthesedSelect p && p.getSelect() != null) {
            cur = p.getSelect();
        }
        return cur;
    }

    private static StatementKind kindOf(Statement st, StatementKind fallback) {
        if (st instanceof Select) {
            return StatementKind.SELECT;
        } else if (st instanceof Insert) {
            return StatementKind.INSERT;
        } else if (st instanceof Update) {
            return StatementKind.UPDATE;
        } else if (st instanceof Delete) {
            return StatementKind.DELETE;
        } else if (st instanceof Drop) {
            return StatementKind.DROP;
        } else if (st instanceof Truncate) {
            return StatementKind.TRUNCATE;
        } else if (st instanceof Alter) {
            return StatementKind.ALTER;
        } else if (st instanceof CreateTable || st instanceof CreateIndex || st instanceof CreateView) {
            return StatementKind.CREATE;
        }
        return fallback;
    }

    /** Rides on TablesNamesFinder's traversal, which already walks every expression. */
    private static final class Inspector extends TablesNamesFinder {
        final PlainSelect root;
        final List<Column> columns = new ArrayList<>();
        final Set<Column> direct = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> functions = new java.util.LinkedHashSet<>();
        final Map<String, String> aliases = new HashMap<>();
        boolean crossJoin;
        boolean setOperation;

        Inspector(PlainSelect root) {
            this.root = root;
        }

        @Override
        public void visit(Column column) {
            columns.add(column);
            super.visit(column);
        }

        @Override
        public void visit(Function function) {
            if (function.getName() != null) {
                String name = function.getName().toLowerCase(Locale.ROOT).replace("\"", "");
                functions.add(name);
                functions.add(lastSegment(name));
            }
            super.visit(function);
        }

        @Override
        public void visit(Table table) {
            if (table.getName() != null) {
                String name = table.getFullyQualifiedName().toLowerCase(Locale.ROOT).replace("\"", "");
                if (table.getAlias() != null && table.getAlias().getName() != null) {
                    aliases.put(table.getAlias().getName().toLowerCase(Locale.ROOT), name);
                }
                aliases.putIfAbsent(lastSegment(name), name);
            }
            super.visit(table);
        }

        @Override
        public void visit(SetOperationList list) {
            setOperation = true;
            super.visit(list);
        }

        @Override
        public void visit(PlainSelect ps) {
            // Only the outermost select list counts as "direct". A masked column selected
            // inside a subquery can be transformed by the outer query, so it isn't safe.
            if (ps == root && ps.getSelectItems() != null) {
                for (SelectItem<?> item : ps.getSelectItems()) {
                    if (item.getExpression() instanceof Column c) {
                        direct.add(c);
                    }
                }
            }
            if (ps.getJoins() != null) {
                for (Join j : ps.getJoins()) {
                    boolean noCondition = (j.getOnExpressions() == null || j.getOnExpressions().isEmpty())
                            && (j.getUsingColumns() == null || j.getUsingColumns().isEmpty());
                    if (j.isCross()) {
                        crossJoin = true;
                    } else if (j.isSimple()) {
                        // FROM a, b with no WHERE at all is a cartesian product
                        if (ps.getWhere() == null) {
                            crossJoin = true;
                        }
                    } else if (!j.isNatural() && noCondition) {
                        crossJoin = true;
                    }
                }
            }
            super.visit(ps);
            // TablesNamesFinder skips ORDER BY and GROUP BY (no tables in there), but a
            // masked column in ORDER BY leaks its sort order, so walk them ourselves.
            if (ps.getOrderByElements() != null) {
                ps.getOrderByElements().forEach(o -> o.getExpression().accept(this));
            }
            if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
                ps.getGroupBy().getGroupByExpressionList().accept(this);
            }
        }
    }

    static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
