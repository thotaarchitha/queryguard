package dev.queryguard.common.masking;

import dev.queryguard.common.analysis.QueryInfo;
import dev.queryguard.common.analysis.SqlLexer;
import dev.queryguard.common.analysis.StatementInfo;
import dev.queryguard.common.analysis.StatementInfo.ColumnUse;
import dev.queryguard.common.analysis.StatementKind;
import dev.queryguard.common.rules.Decision;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Masking itself happens on the way back (DataRow rewriting by table OID + column number).
 * That covers {@code SELECT email AS e}, but it can't see through anything that makes
 * Postgres report the column as "computed": {@code upper(email)}, {@code email || ''},
 * {@code row_to_json(c)}, a UNION. So this runs before the query and refuses those shapes
 * for roles that have the column masked.
 *
 * <p>The rule is deliberately blunt: a masked column may appear only as a bare item in the
 * top-level select list. Not in WHERE (otherwise {@code WHERE email LIKE 'a%'} leaks it one
 * character at a time), not in a subquery, not in an expression.
 */
public final class MaskingGuard {

    public static final String RULE_ID = "M1";
    private static final String SQLSTATE = "42501";

    private final List<MaskRule> rules;

    public MaskingGuard(List<MaskRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public List<MaskRule> rules() {
        return rules;
    }

    public Optional<Decision> check(QueryInfo query, String role) {
        List<MaskRule> active = rules.stream().filter(r -> r.appliesTo(role)).toList();
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Set<String> maskedTables = new HashSet<>();
        active.forEach(r -> maskedTables.add(r.tableName()));

        for (StatementInfo st : query.statements()) {
            Set<String> touched = new HashSet<>();
            for (String t : st.tables()) {
                String bare = lastSegment(t);
                if (maskedTables.contains(bare)) {
                    touched.add(bare);
                }
            }
            if (st.kind() == StatementKind.COPY && (!touched.isEmpty() || mentionsMaskedTable(st, maskedTables))) {
                return block("COPY is not allowed on tables with masked columns (" + String.join(", ", touched.isEmpty() ? maskedTables : touched) + ")");
            }
            if (touched.isEmpty()) {
                continue;
            }
            if (!st.parsed()) {
                // Can't tell how the column is used, so if it's mentioned at all, say no.
                for (MaskRule r : active) {
                    if (touched.contains(r.tableName()) && mentionsWord(st, r.columnName())) {
                        return block("couldn't verify how masked column " + r.tableName() + "." + r.columnName()
                                + " is used; simplify the query");
                    }
                }
                continue;
            }
            for (ColumnUse c : st.columns()) {
                if (c.qualifier() == null && st.aliases().containsKey(c.name())
                        && touched.contains(lastSegment(st.aliases().get(c.name())))) {
                    return block("whole-row reference to " + st.aliases().get(c.name())
                            + " would expose masked columns; list the columns you need");
                }
                MaskRule hit = maskedRuleFor(c, st, active, touched);
                if (hit == null) {
                    continue;
                }
                String col = hit.tableName() + "." + hit.columnName();
                if (!c.direct()) {
                    return block(col + " is masked for role " + role
                            + "; it can be selected as-is but not used in expressions, filters, joins or subqueries");
                }
                if (st.setOperation()) {
                    return block(col + " is masked for role " + role + "; UNION/INTERSECT/EXCEPT would hide where it came from");
                }
            }
        }
        return Optional.empty();
    }

    private static MaskRule maskedRuleFor(ColumnUse c, StatementInfo st, List<MaskRule> active, Set<String> touched) {
        for (MaskRule r : active) {
            if (!r.columnName().equals(c.name()) || !touched.contains(r.tableName())) {
                continue;
            }
            if (c.qualifier() == null) {
                return r; // unqualified; any masked table in scope with this column name counts
            }
            String table = st.aliases().getOrDefault(c.qualifier(), c.qualifier());
            if (lastSegment(table).equals(r.tableName())) {
                return r;
            }
        }
        return null;
    }

    private static boolean mentionsWord(StatementInfo st, String word) {
        for (SqlLexer.Token t : st.tokens()) {
            if ((t.type() == SqlLexer.Type.WORD || t.type() == SqlLexer.Type.QUOTED_IDENT)
                    && t.ident().equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsMaskedTable(StatementInfo st, Set<String> maskedTables) {
        return maskedTables.stream().anyMatch(t -> mentionsWord(st, t));
    }

    private static Optional<Decision> block(String why) {
        return Optional.of(Decision.block(RULE_ID, SQLSTATE, "QueryGuard M1: " + why + ".", null, null));
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
