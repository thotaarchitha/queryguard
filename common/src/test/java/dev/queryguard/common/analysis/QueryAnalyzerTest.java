package dev.queryguard.common.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryAnalyzerTest {

    private final QueryAnalyzer analyzer = new QueryAnalyzer();

    private StatementInfo one(String sql) {
        QueryInfo q = analyzer.analyze(sql);
        assertThat(q.statements()).hasSize(1);
        return q.statements().get(0);
    }

    @Test
    void deleteWithoutWhere() {
        StatementInfo st = one("DELETE FROM orders");
        assertThat(st.parsed()).isTrue();
        assertThat(st.kind()).isEqualTo(StatementKind.DELETE);
        assertThat(st.hasWhere()).isFalse();
        assertThat(st.tables()).containsExactly("orders");
    }

    @Test
    void commentCannotFakeAWhereClause() {
        assertThat(one("DELETE /* WHERE id = 1 */ FROM orders").hasWhere()).isFalse();
        assertThat(one("DELETE FROM orders -- WHERE id = 1").hasWhere()).isFalse();
    }

    @Test
    void updateWithWhere() {
        StatementInfo st = one("UPDATE orders SET status = 'paid' WHERE id = 7");
        assertThat(st.kind()).isEqualTo(StatementKind.UPDATE);
        assertThat(st.hasWhere()).isTrue();
    }

    @Test
    void multiStatementQueryKeepsEachStatement() {
        QueryInfo q = analyzer.analyze("SELECT 1; DROP TABLE orders");
        assertThat(q.statements()).extracting(StatementInfo::kind)
                .containsExactly(StatementKind.SELECT, StatementKind.DROP);
        assertThat(q.statements().get(1).sql()).isEqualTo("DROP TABLE orders");
    }

    @Test
    void explainAnalyzeIsWhatItRuns() {
        assertThat(one("EXPLAIN ANALYZE DELETE FROM orders").kind()).isEqualTo(StatementKind.DELETE);
        assertThat(one("EXPLAIN (ANALYZE, BUFFERS) DELETE FROM orders").kind()).isEqualTo(StatementKind.DELETE);
        assertThat(one("EXPLAIN DELETE FROM orders").kind()).isEqualTo(StatementKind.EXPLAIN);
        assertThat(one("EXPLAIN (ANALYZE false) DELETE FROM orders").kind()).isEqualTo(StatementKind.EXPLAIN);
    }

    @Test
    void cteThatDeletes() {
        StatementInfo st = one("WITH gone AS (SELECT id FROM orders WHERE status = 'cancelled') DELETE FROM orders");
        assertThat(st.kind()).isEqualTo(StatementKind.DELETE);
        assertThat(st.hasWhere()).isFalse();
    }

    @Test
    void functionsAreFoundEvenWhenNested() {
        StatementInfo st = one("SELECT coalesce(pg_catalog.pg_sleep(5)::text, 'x')");
        assertThat(st.functions()).contains("pg_sleep", "coalesce");
    }

    @Test
    void crossJoinsAndMissingJoinConditions() {
        assertThat(one("SELECT * FROM orders CROSS JOIN products").hasCrossJoin()).isTrue();
        assertThat(one("SELECT * FROM orders, products").hasCrossJoin()).isTrue();
        assertThat(one("SELECT * FROM orders o, products p WHERE o.product_id = p.id").hasCrossJoin()).isFalse();
        assertThat(one("SELECT * FROM orders o JOIN products p ON o.product_id = p.id").hasCrossJoin()).isFalse();
    }

    @Test
    void selectStarAndLimit() {
        StatementInfo st = one("SELECT * FROM orders LIMIT 10");
        assertThat(st.selectStar()).isTrue();
        assertThat(st.hasLimit()).isTrue();
        assertThat(one("SELECT o.* FROM orders o").selectStar()).isTrue();
        assertThat(one("SELECT count(*) FROM orders").selectStar()).isFalse();
    }

    @Test
    void jdbcStyleParametersStillParse() {
        StatementInfo st = one("SELECT email FROM customers WHERE id = $1 AND country = $2::text");
        assertThat(st.parsed()).isTrue();
        assertThat(st.hasWhere()).isTrue();
    }

    @Test
    void columnsKnowIfTheyAreDirect() {
        StatementInfo st = one("SELECT c.email AS e, upper(c.phone) FROM customers c");
        assertThat(st.columns()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("email");
            assertThat(c.direct()).isTrue();
        });
        assertThat(st.columns()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("phone");
            assertThat(c.direct()).isFalse();
        });
        assertThat(st.aliases()).containsEntry("c", "customers");
    }

    @Test
    void unparseableSqlFallsBackToTheLexer() {
        // psql's \d uses this OPERATOR() syntax
        StatementInfo st = one("SELECT c.relname FROM pg_catalog.pg_class c WHERE c.relname OPERATOR(pg_catalog.~) '^(orders)$'");
        assertThat(st.kind()).isEqualTo(StatementKind.SELECT);
        assertThat(st.hasWhere()).isTrue();
    }

    @Test
    void emptyQueryHasNoStatements() {
        assertThat(analyzer.analyze("  ;  -- nothing\n").statements()).isEmpty();
    }
}
