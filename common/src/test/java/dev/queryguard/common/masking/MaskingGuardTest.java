package dev.queryguard.common.masking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.queryguard.common.analysis.QueryAnalyzer;
import dev.queryguard.common.rules.Decision;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Every way I could think of to read customers.email as an analyst without it being masked. */
class MaskingGuardTest {

    private final MaskingGuard guard = new MaskingGuard(List.of(
            new MaskRule("customers", "email", MaskStrategy.PARTIAL_EMAIL, List.of("analyst"), null),
            new MaskRule("public.customers", "card_last4", MaskStrategy.REDACT, null, List.of("admin"))));
    private final QueryAnalyzer analyzer = new QueryAnalyzer();

    private Optional<Decision> check(String role, String sql) {
        return guard.check(analyzer.analyze(sql), role);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT email FROM customers",
            "SELECT email AS e FROM customers",
            "SELECT c.email, c.id FROM customers c WHERE c.country = 'IN'",
            "SELECT * FROM customers WHERE id < 10",
            "SELECT c.* FROM customers c",
    })
    void plainSelectsAreFineBecauseDataRowMaskingHandlesThem(String sql) {
        assertThat(check("analyst", sql)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT upper(email) FROM customers",
            "SELECT email || '' FROM customers",
            "SELECT email::text FROM customers",
            "SELECT id FROM customers WHERE email LIKE 'a%'",
            "SELECT id FROM customers ORDER BY email",
            "SELECT e FROM (SELECT email AS e FROM customers) s",
            "WITH x AS (SELECT email FROM customers) SELECT * FROM x",
            "SELECT row_to_json(c) FROM customers c",
            "SELECT c FROM customers c",
            "SELECT email FROM customers UNION SELECT 'x'",
            "SELECT count(DISTINCT email) FROM customers",
            "SELECT upper(email) FROM customers LIMIT 1",
            "SELECT id FROM customers WHERE email LIKE 'user1%' LIMIT 1",
            "SELECT row_to_json(c) FROM customers c LIMIT 1",
            "SELECT email FROM customers UNION SELECT 'x' LIMIT 1",
    })
    void shapesThatHideWhereTheValueCameFromAreBlocked(String sql) {
        assertThat(check("analyst", sql)).isPresent().get()
                .satisfies(d -> assertThat(d.ruleId()).isEqualTo("M1"));
    }

    @Test
    void copyOutOfAMaskedTableIsBlocked() {
        assertThat(check("analyst", "COPY customers TO STDOUT")).isPresent();
        assertThat(check("analyst", "COPY (SELECT email FROM customers) TO STDOUT")).isPresent();
    }

    @Test
    void rolesWithoutMaskingAreUntouched() {
        assertThat(check("admin", "SELECT upper(email), card_last4 || '' FROM customers")).isEmpty();
    }

    @Test
    void exceptRolesWork() {
        // card_last4 is masked for everyone but admin, so the app can't filter on it either
        assertThat(check("app", "SELECT id FROM customers WHERE card_last4 = '1234'")).isPresent();
        assertThat(check("app", "SELECT upper(email) FROM customers")).isEmpty();
    }

    @Test
    void otherTablesWithSameColumnNameAreNotAffected() {
        assertThat(check("analyst", "SELECT upper(email) FROM newsletter_signups")).isEmpty();
    }

    @Test
    void strategies() {
        assertThat(MaskStrategy.PARTIAL_EMAIL.apply("user42@example.com")).isEqualTo("u***@***.com");
        assertThat(MaskStrategy.PARTIAL_EMAIL.apply("not-an-email")).isEqualTo("****");
        assertThat(MaskStrategy.LAST4.apply("+1-555-0142")).isEqualTo("*******0142");
        assertThat(MaskStrategy.REDACT.apply("1234")).isEqualTo("****");
        assertThat(MaskStrategy.HASH.apply("a@b.com")).isEqualTo(MaskStrategy.HASH.apply("a@b.com")).startsWith("h_");
        assertThat(MaskStrategy.NULLIFY.apply("x")).isNull();
    }
}
