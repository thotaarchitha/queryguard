package dev.queryguard.admin.rules;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.queryguard.admin.policy.ConfigChanges;
import dev.queryguard.admin.security.SecurityConfig;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Contract: validation, auth and error shapes. Repository is mocked; the integration test covers SQL. */
@WebMvcTest(RuleController.class)
@Import(SecurityConfig.class)
class RuleControllerWebTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @MockBean
    RuleRepository repo;
    @MockBean
    ConfigChanges changes;

    static final String VALID = """
            {"id": "R9", "name": "no-orders-for-interns", "priority": 15, "action": "BLOCK",
             "when": {"roles": ["intern"], "tables_any": ["orders"]},
             "message": "QueryGuard R9: interns can't read orders."}""";

    static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.with(jwt().jwt(j -> j.subject("tester")).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/rules")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        when(repo.findAll()).thenReturn(List.of());
        mvc.perform(as(get("/rules"), "QG_VIEWER")).andExpect(status().isOk());
        mvc.perform(as(post("/rules"), "QG_VIEWER").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
    }

    @Test
    void createValidatesAgainstTheSchema() throws Exception {
        String bad = """
                {"id": "R9", "action": "EXPLODE", "when": {"estimated_cost_gt": -5, "no_such_field": true}}""";
        mvc.perform(as(post("/rules"), "QG_ADMIN").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        verify(repo, never()).insert(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void rewriteRuleNeedsAddLimit() throws Exception {
        String bad = """
                {"id": "R10", "action": "REWRITE", "when": {"roles": ["analyst"]}}""";
        mvc.perform(as(post("/rules"), "QG_ADMIN").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("add_limit")));
    }

    @Test
    void createStoresAndBumpsVersion() throws Exception {
        var row = new RuleRepository.RuleRow("R9", 1, true, mapper.readTree(VALID), "tester", OffsetDateTime.now(), "tester", OffsetDateTime.now());
        when(repo.find("R9")).thenReturn(Optional.of(row));
        when(changes.record(eq("tester"), eq("create"), eq("rule"), eq("R9"), any(), any())).thenReturn(42L);
        mvc.perform(as(post("/rules"), "QG_ADMIN").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.config_version").value(42))
                .andExpect(jsonPath("$.rule.id").value("R9"));
    }

    @Test
    void updateWithoutIfMatchIs428() throws Exception {
        mvc.perform(as(put("/rules/R9"), "QG_ADMIN").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void staleIfMatchIs409() throws Exception {
        var row = new RuleRepository.RuleRow("R9", 3, true, mapper.readTree(VALID), "a", OffsetDateTime.now(), "b", OffsetDateTime.now());
        when(repo.find("R9")).thenReturn(Optional.of(row));
        when(repo.update(eq("R9"), any(), anyBoolean(), anyInt(), anyString())).thenReturn(false);
        mvc.perform(as(put("/rules/R9"), "QG_ADMIN").header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("version 3")));
    }

    @Test
    void missingRuleIs404Problem() throws Exception {
        when(repo.find("NOPE")).thenReturn(Optional.empty());
        mvc.perform(as(get("/rules/NOPE"), "QG_VIEWER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
