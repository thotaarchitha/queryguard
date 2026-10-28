package dev.queryguard.admin.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.queryguard.admin.policy.ConfigChanges;
import dev.queryguard.admin.rules.RuleRepository.RuleRow;
import dev.queryguard.admin.web.ApiException;
import dev.queryguard.common.config.RuleValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rules CRUD. Updates use HTTP's own optimistic locking: GET gives you an ETag with the
 * rule's version, PUT must send it back in If-Match. If someone else changed the rule in
 * between, you get 409 instead of silently overwriting their edit.
 */
@RestController
@RequestMapping("/rules")
public class RuleController {

    public record RuleView(String id, int version, boolean enabled, JsonNode rule, String createdBy,
                           OffsetDateTime createdAt, String updatedBy, OffsetDateTime updatedAt) {
        static RuleView of(RuleRow r) {
            return new RuleView(r.id(), r.version(), r.enabled(), r.body(), r.createdBy(), r.createdAt(), r.updatedBy(), r.updatedAt());
        }
    }

    public record Change(RuleView rule, long configVersion) {
    }

    private final RuleRepository repo;
    private final ConfigChanges changes;

    public RuleController(RuleRepository repo, ConfigChanges changes) {
        this.repo = repo;
        this.changes = changes;
    }

    @Operation(summary = "List rules", description = "Optional filters: role (rules that can apply to that role) and enabled.")
    @GetMapping
    public List<RuleView> list(@RequestParam(required = false) String role, @RequestParam(required = false) Boolean enabled) {
        return repo.findAll().stream()
                .filter(r -> enabled == null || r.enabled() == enabled)
                .filter(r -> role == null || appliesToRole(r.body(), role))
                .map(RuleView::of)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RuleView> get(@PathVariable String id) {
        RuleRow r = repo.find(id).orElseThrow(() -> ApiException.notFound("rule " + id));
        return ResponseEntity.ok().eTag(etag(r.version())).body(RuleView.of(r));
    }

    @Operation(summary = "Create a rule", description = "Body is a rule in the same shape as default-rules.yml, as JSON.")
    @PostMapping
    @Transactional
    public ResponseEntity<Change> create(@RequestBody JsonNode body, Authentication auth) {
        validate(body);
        String id = body.get("id").asText();
        repo.insert(id, body, enabledFlag(body), auth.getName());
        long v = changes.record(auth.getName(), "create", "rule", id, null, body);
        RuleRow saved = repo.find(id).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(saved.version())).body(new Change(RuleView.of(saved), v));
    }

    @Operation(summary = "Update a rule (new version)", description = "Send the ETag from GET /rules/{id} as If-Match.")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Change> update(@PathVariable String id, @RequestBody JsonNode body,
                                         @Parameter(description = "ETag from GET, e.g. \"3\"")
                                         @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                         Authentication auth) {
        if (ifMatch == null) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "send If-Match with the rule's current ETag");
        }
        if (body instanceof ObjectNode o) {
            o.put("id", id); // the path wins; renaming a rule is create + delete
        }
        validate(body);
        RuleRow before = repo.find(id).orElseThrow(() -> ApiException.notFound("rule " + id));
        int expected = parseEtag(ifMatch);
        if (!repo.update(id, body, enabledFlag(body), expected, auth.getName())) {
            throw ApiException.conflict("rule " + id + " is at version " + before.version()
                    + ", not " + expected + ". Someone else changed it; GET it again and reapply your edit.");
        }
        long v = changes.record(auth.getName(), "update", "rule", id, before.body(), body);
        RuleRow saved = repo.find(id).orElseThrow();
        return ResponseEntity.ok().eTag(etag(saved.version())).body(new Change(RuleView.of(saved), v));
    }

    @Operation(summary = "Disable a rule", description = "Soft delete: the rule and its history stay, it just stops applying.")
    @DeleteMapping("/{id}")
    @Transactional
    public Change disable(@PathVariable String id, Authentication auth) {
        RuleRow before = repo.find(id).orElseThrow(() -> ApiException.notFound("rule " + id));
        ObjectNode body = before.body().deepCopy();
        body.put("enabled", false);
        repo.update(id, body, false, before.version(), auth.getName());
        long v = changes.record(auth.getName(), "disable", "rule", id, before.body(), body);
        return new Change(RuleView.of(repo.find(id).orElseThrow()), v);
    }

    @GetMapping("/{id}/history")
    public Map<String, Object> history(@PathVariable String id) {
        repo.find(id).orElseThrow(() -> ApiException.notFound("rule " + id));
        return Map.of("rule_id", id, "versions", repo.history(id));
    }

    private static void validate(JsonNode body) {
        List<String> errors = RuleValidator.validate(body);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
    }

    private static boolean enabledFlag(JsonNode body) {
        return !body.has("enabled") || body.get("enabled").asBoolean(true);
    }

    private static boolean appliesToRole(JsonNode body, String role) {
        JsonNode when = body.path("when");
        JsonNode roles = when.path("roles");
        JsonNode except = when.path("roles_except");
        if (roles.isArray()) {
            for (JsonNode r : roles) {
                if (r.asText().equalsIgnoreCase(role)) {
                    return true;
                }
            }
            return false;
        }
        if (except.isArray()) {
            for (JsonNode r : except) {
                if (r.asText().equalsIgnoreCase(role)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String etag(int version) {
        return "\"" + version + "\"";
    }

    private static int parseEtag(String ifMatch) {
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "If-Match must be the ETag from GET /rules/{id}");
        }
    }
}
