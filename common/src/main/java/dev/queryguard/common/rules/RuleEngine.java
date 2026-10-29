package dev.queryguard.common.rules;

import dev.queryguard.common.analysis.QueryInfo;
import dev.queryguard.common.analysis.StatementInfo;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.config.Settings;
import dev.queryguard.common.config.Settings.FailMode;
import dev.queryguard.common.cost.CostEstimate;
import dev.queryguard.common.cost.CostUnavailableException;
import dev.queryguard.common.masking.MaskingGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Evaluates one query against the rule set. Immutable once built; a config change builds
 * a new engine and swaps it in, so a query that started on the old rules finishes on them.
 *
 * <p>Order: masking guard first (a leak is worse than any other miss), then the parse-failure
 * policy, then rules by priority. The first BLOCK wins. REWRITEs stack.
 */
public final class RuleEngine {

    private final List<Rule> rules;
    private final Settings settings;
    private final MaskingGuard maskingGuard;

    public RuleEngine(QueryGuardConfig config) {
        this.rules = config.rules().stream()
                .filter(Rule::isEnabled)
                .sorted(Comparator.comparingInt(Rule::effectivePriority).thenComparing(Rule::id))
                .toList();
        this.settings = config.settings();
        this.maskingGuard = new MaskingGuard(config.masking());
    }

    public List<Rule> rules() {
        return rules;
    }

    public MaskingGuard maskingGuard() {
        return maskingGuard;
    }

    public Decision evaluate(QueryInfo query, EvalContext ctx) {
        Optional<Decision> leak = maskingGuard.check(query, ctx.role());
        if (leak.isPresent()) {
            return leak.get();
        }

        List<String> rewrites = new ArrayList<>();
        Double maxCost = null;
        Double maxRows = null;

        for (StatementInfo st : query.statements()) {
            if (!st.parsed()) {
                Decision d = unparsedPolicy(st);
                if (d != null) {
                    return d;
                }
            }
            Facts facts = new Facts(st, ctx);
            for (Rule rule : rules) {
                Match m = match(rule, facts);
                if (m == Match.NO) {
                    continue;
                }
                if (m == Match.COST_UNKNOWN) {
                    if (settings.onCostError() == FailMode.BLOCK) {
                        return Decision.block("QG-COST", "57014",
                                "QueryGuard: couldn't estimate this query's cost (" + facts.costError
                                        + ") and rule " + rule.id() + " needs it. Blocked by the fail-closed setting.",
                                null, null);
                    }
                    continue; // fail-open: a rule that can't be checked doesn't fire
                }
                if (rule.action() == Rule.Action.BLOCK) {
                    CostEstimate c = facts.cost;
                    String message = render(rule, facts, ctx);
                    if (c != null && c.fromTableStats()) {
                        message += " (EXPLAIN was unavailable, so this is a full-scan estimate from table statistics.)";
                    }
                    return Decision.block(rule.id(), rule.effectiveSqlstate(), message,
                            c == null ? null : c.totalCost(), c == null ? null : c.planRows());
                }
                if (rule.action() == Rule.Action.REWRITE && applyRewrite(rule, st)) {
                    rewrites.add(rule.id());
                }
            }
            if (facts.cost != null) {
                maxCost = maxCost == null ? facts.cost.totalCost() : Math.max(maxCost, facts.cost.totalCost());
                maxRows = maxRows == null ? facts.cost.planRows() : Math.max(maxRows, facts.cost.planRows());
            }
        }
        if (!rewrites.isEmpty()) {
            return Decision.rewrite(rewrites, renderSql(query), maxCost, maxRows);
        }
        return Decision.allow(maxCost, maxRows);
    }

    private Decision unparsedPolicy(StatementInfo st) {
        FailMode mode = settings.onParseError();
        boolean refuse = mode == FailMode.BLOCK
                || (mode == FailMode.ALLOW_READS && !st.kind().isSafeWhenUnparsed());
        if (!refuse) {
            return null;
        }
        return Decision.block("QG-PARSE", "42601",
                "QueryGuard: couldn't parse this " + st.kind() + " statement, and only reads are allowed through unparsed.",
                null, null);
    }

    // ---- matching ------------------------------------------------------------------------

    enum Match { YES, NO, COST_UNKNOWN }

    /** Per-statement facts. Cost is fetched lazily, once, and only if a rule actually needs it. */
    static final class Facts {
        final StatementInfo st;
        final EvalContext ctx;
        CostEstimate cost;
        String costError;
        boolean costTried;
        String matchedFunction;

        Facts(StatementInfo st, EvalContext ctx) {
            this.st = st;
            this.ctx = ctx;
        }

        CostEstimate cost() {
            if (!costTried) {
                costTried = true;
                try {
                    cost = ctx.costs().estimate(st);
                } catch (CostUnavailableException e) {
                    costError = e.getMessage();
                }
            }
            return cost;
        }
    }

    static Match match(Rule rule, Facts f) {
        Rule.When w = rule.when();
        if (w == null) {
            return Match.YES;
        }
        StatementInfo st = f.st;
        String role = f.ctx.role();
        if (w.roles() != null && !containsIgnoreCase(w.roles(), role)) {
            return Match.NO;
        }
        if (w.rolesExcept() != null && containsIgnoreCase(w.rolesExcept(), role)) {
            return Match.NO;
        }
        if (w.users() != null && !containsIgnoreCase(w.users(), f.ctx.user())) {
            return Match.NO;
        }
        if (w.statement() != null && w.statement().stream().noneMatch(st.kind()::matches)) {
            return Match.NO;
        }
        if (w.hasWhere() != null && w.hasWhere() != st.hasWhere()) {
            return Match.NO;
        }
        if (w.hasLimit() != null && w.hasLimit() != st.hasLimit()) {
            return Match.NO;
        }
        if (w.selectStar() != null && w.selectStar() != st.selectStar()) {
            return Match.NO;
        }
        if (w.hasCrossJoin() != null && w.hasCrossJoin() != st.hasCrossJoin()) {
            return Match.NO;
        }
        if (w.tablesAny() != null && w.tablesAny().stream().noneMatch(t -> st.tables().stream()
                .anyMatch(x -> x.equalsIgnoreCase(t) || x.toLowerCase(Locale.ROOT).endsWith("." + t.toLowerCase(Locale.ROOT))))) {
            return Match.NO;
        }
        if (w.functionsAny() != null) {
            String hit = w.functionsAny().stream()
                    .filter(fn -> st.functions().contains(fn.toLowerCase(Locale.ROOT)))
                    .findFirst().orElse(null);
            if (hit == null) {
                return Match.NO;
            }
            f.matchedFunction = hit;
        }
        if (w.outsideWindow() != null && w.outsideWindow().contains(f.ctx.now())) {
            return Match.NO;
        }
        if (w.estimatedCostGt() != null || w.estimatedRowsGt() != null) {
            if (!st.kind().isCostable()) {
                return Match.NO;
            }
            CostEstimate c = f.cost();
            if (c == null) {
                return Match.COST_UNKNOWN;
            }
            if (w.estimatedCostGt() != null && c.totalCost() <= w.estimatedCostGt()) {
                return Match.NO;
            }
            if (w.estimatedRowsGt() != null && c.planRows() <= w.estimatedRowsGt()) {
                return Match.NO;
            }
        }
        return Match.YES;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        return value != null && list.stream().anyMatch(v -> v.equalsIgnoreCase(value));
    }

    // ---- rewriting -----------------------------------------------------------------------

    /**
     * The only rewrite is "add a LIMIT", and it doesn't need the AST: if the statement has no
     * LIMIT/FETCH and no locking clause, appending {@code LIMIT n} to its own text is valid
     * Postgres after anything a SELECT can end with (ORDER BY, OFFSET, a UNION, where it limits
     * the whole result). It also leaves the client's SQL byte-for-byte intact, which a
     * re-serialized AST doesn't. Re-parsing for this was 2.4 ms per analyst query (JMH).
     *
     * A locking SELECT with a LIMIT changes which rows get locked, so those are left alone.
     */
    private static boolean applyRewrite(Rule rule, StatementInfo st) {
        if (rule.rewrite() == null || rule.rewrite().addLimit() == null) {
            return false;
        }
        if (!st.parsed() || st.kind() != dev.queryguard.common.analysis.StatementKind.SELECT
                || st.lockingClause() || st.hasLimit()) {
            return false;
        }
        st.addLimit(rule.rewrite().addLimit());
        return true;
    }

    /** Untouched statements keep their exact original text. */
    private static String renderSql(QueryInfo query) {
        StringBuilder sb = new StringBuilder();
        for (StatementInfo st : query.statements()) {
            if (!sb.isEmpty()) {
                sb.append(";\n");
            }
            sb.append(st.sql());
            if (st.addedLimit() != null) {
                sb.append(" LIMIT ").append(st.addedLimit());
            }
        }
        return sb.toString();
    }

    private static String render(Rule rule, Facts f, EvalContext ctx) {
        String msg = rule.message() == null ? "QueryGuard " + rule.id() + ": blocked." : rule.message();
        CostEstimate c = f.cost;
        return msg
                .replace("{cost}", c == null ? "?" : String.format(Locale.ROOT, "%.0f", c.totalCost()))
                .replace("{rows}", c == null ? "?" : String.format(Locale.ROOT, "%.0f", c.planRows()))
                .replace("{statement}", f.st.kind().name())
                .replace("{function}", f.matchedFunction == null ? "" : f.matchedFunction)
                .replace("{table}", f.st.tables().isEmpty() ? "" : String.join(", ", f.st.tables()))
                .replace("{role}", String.valueOf(ctx.role()))
                .replace("{user}", String.valueOf(ctx.user()));
    }
}
