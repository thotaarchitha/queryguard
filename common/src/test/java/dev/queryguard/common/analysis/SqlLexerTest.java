package dev.queryguard.common.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlLexerTest {

    private static String norm(String sql) {
        return SqlLexer.normalize(SqlLexer.tokenize(sql));
    }

    @Test
    void literalsBecomePlaceholders() {
        assertThat(norm("SELECT * FROM orders WHERE id = 42 AND status = 'paid'"))
                .isEqualTo("select * from orders where id = ? and status = ?");
    }

    @Test
    void sameShapeSameFingerprint() {
        String a = Fingerprints.of(norm("select email from customers where id = 5"));
        String b = Fingerprints.of(norm("SELECT email\n  FROM customers WHERE id=9;"));
        String c = Fingerprints.of(norm("SELECT phone FROM customers WHERE id = 9"));
        assertThat(a).isEqualTo(b).hasSize(16);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void inListsOfAnyLengthCollapse() {
        assertThat(norm("select 1 from t where id in (1, 2, 3)"))
                .isEqualTo(norm("select 1 from t where id in (7,8)"));
    }

    @Test
    void commentsAreDroppedIncludingNestedOnes() {
        assertThat(norm("DELETE /* where id = 1 /* nested */ still comment */ FROM orders -- where x\n"))
                .isEqualTo("delete from orders");
    }

    @Test
    void semicolonsInsideStringsDontSplit() {
        var parts = SqlLexer.splitStatements(SqlLexer.tokenize("select 'a;b'; select $$x;y$$ ;; select E'it\\'s;'"));
        assertThat(parts).hasSize(3);
    }

    @Test
    void dollarParamsAreParamsNotDollarQuotes() {
        var tokens = SqlLexer.tokenize("select * from t where a = $1 and b = $tag$ $1 $tag$");
        assertThat(tokens).filteredOn(t -> t.type() == SqlLexer.Type.PARAM).hasSize(1);
        assertThat(tokens).filteredOn(t -> t.type() == SqlLexer.Type.STRING).hasSize(1);
    }

    @Test
    void quotedIdentifiersKeepCase() {
        var tokens = SqlLexer.tokenize("select \"Email\" from \"My\"\"Table\"");
        assertThat(tokens.get(1).ident()).isEqualTo("Email");
        assertThat(tokens.get(3).ident()).isEqualTo("My\"Table");
    }

    @Test
    void unterminatedStringDoesNotLoopForever() {
        assertThat(SqlLexer.tokenize("select 'oops")).hasSize(2);
        assertThat(SqlLexer.tokenize("select $a$ never closed")).hasSize(2);
    }
}
