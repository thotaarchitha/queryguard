package dev.queryguard.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleValidatorTest {

    private static JsonNode ruleWithWindow(String window) throws Exception {
        return ConfigMapper.json().readTree("""
                {"id": "R9", "action": "BLOCK",
                 "when": {"statement": ["DELETE"], "outside_window": %s}}
                """.formatted(window));
    }

    @Test
    void acceptsAValidWindow() throws Exception {
        JsonNode rule = ruleWithWindow("""
                {"days": ["SAT"], "from": "22:00", "to": "02:00", "tz": "Asia/Kolkata"}""");
        assertThat(RuleValidator.validate(rule)).isEmpty();
    }

    @Test
    void rejectsHoursPastTwentyThree() throws Exception {
        // "25:00" gets past the schema's pattern but LocalTime can't parse it
        JsonNode rule = ruleWithWindow("""
                {"from": "02:00", "to": "25:00"}""");
        assertThat(RuleValidator.validate(rule))
                .containsExactly("$.when.outside_window.to: '25:00' isn't a valid time (00:00 to 23:59)");
    }

    @Test
    void rejectsUnknownTimeZone() throws Exception {
        JsonNode rule = ruleWithWindow("""
                {"from": "02:00", "to": "04:00", "tz": "America/NewYork"}""");
        List<String> errors = RuleValidator.validate(rule);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).startsWith("$.when.outside_window.tz: unknown time zone 'America/NewYork'");
    }

    @Test
    void reportsBothBadTimesAtOnce() throws Exception {
        JsonNode rule = ruleWithWindow("""
                {"from": "24:00", "to": "29:59"}""");
        assertThat(RuleValidator.validate(rule)).hasSize(2);
    }
}
