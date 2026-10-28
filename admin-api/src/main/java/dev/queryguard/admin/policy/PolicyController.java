package dev.queryguard.admin.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.masking.MaskRule;
import dev.queryguard.common.masking.MaskStrategy;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Masking policies and role mappings are small, so they're replaced as a whole set. */
@RestController
@Validated
public class PolicyController {

    public record MaskingPolicy(
            @NotBlank @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_.]*$") String table,
            @NotBlank @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$") String column,
            @NotNull MaskStrategy strategy,
            List<String> roles,
            List<String> exceptRoles) {

        MaskRule toRule() {
            return new MaskRule(table, column, strategy, roles, exceptRoles);
        }

        static MaskingPolicy of(MaskRule r) {
            return new MaskingPolicy(r.table(), r.column(), r.strategy(), r.roles(), r.exceptRoles());
        }
    }

    public record Change(long configVersion) {
    }

    private final PolicyStore store;
    private final ConfigChanges changes;

    public PolicyController(PolicyStore store, ConfigChanges changes) {
        this.store = store;
        this.changes = changes;
    }

    @GetMapping("/masking-policies")
    public List<MaskingPolicy> masking() {
        return store.masking().stream().map(MaskingPolicy::of).toList();
    }

    @Operation(summary = "Replace all masking policies")
    @PutMapping("/masking-policies")
    @Transactional
    public Change replaceMasking(@RequestBody List<@Valid MaskingPolicy> policies, Authentication auth) {
        JsonNode before = ConfigMapper.json().valueToTree(store.masking());
        List<MaskRule> rules = policies.stream().map(MaskingPolicy::toRule).toList();
        store.replaceMasking(rules);
        return new Change(changes.record(auth.getName(), "replace", "masking", null, before, ConfigMapper.json().valueToTree(rules)));
    }

    @GetMapping("/role-mappings")
    public Map<String, String> roles() {
        return store.roleMappings();
    }

    @Operation(summary = "Replace all role mappings", description = "{\"alice\": \"analyst\", ...}")
    @PutMapping("/role-mappings")
    @Transactional
    public Change replaceRoles(@RequestBody Map<@NotBlank String, @NotBlank String> mappings, Authentication auth) {
        JsonNode before = ConfigMapper.json().valueToTree(store.roleMappings());
        store.replaceRoleMappings(mappings);
        return new Change(changes.record(auth.getName(), "replace", "role_mappings", null, before, ConfigMapper.json().valueToTree(mappings)));
    }
}
