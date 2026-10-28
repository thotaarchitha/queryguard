package dev.queryguard.admin.seed;

import com.fasterxml.jackson.databind.JsonNode;
import dev.queryguard.admin.AdminProperties;
import dev.queryguard.admin.policy.ConfigChanges;
import dev.queryguard.admin.policy.PolicyStore;
import dev.queryguard.admin.rules.RuleRepository;
import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.rules.Rule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The YAML file is the starting point and nothing more. On first start (no rules in the
 * config DB yet) it's copied in; after that the database is the source of truth and the
 * YAML only supplies settings (fail modes, timeouts) and the default role.
 */
@Component
public class SeedPolicy implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedPolicy.class);

    private final QueryGuardConfig config;
    private final RuleRepository rules;
    private final PolicyStore store;
    private final ConfigChanges changes;
    private final TransactionTemplate tx;

    public SeedPolicy(AdminProperties props, RuleRepository rules, PolicyStore store, ConfigChanges changes,
                      TransactionTemplate tx) throws IOException {
        Path path = Path.of(props.seedFile());
        this.config = Files.exists(path) ? ConfigMapper.load(path) : new QueryGuardConfig(null, null, null, null, null);
        if (!Files.exists(path)) {
            log.warn("Seed file {} not found; starting with an empty policy", path);
        }
        this.rules = rules;
        this.store = store;
        this.changes = changes;
        this.tx = tx;
    }

    public QueryGuardConfig config() {
        return config;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status -> {
            if (rules.count() > 0) {
                return;
            }
            for (Rule r : config.rules()) {
                JsonNode body = ConfigMapper.json().valueToTree(r);
                rules.insert(r.id(), body, r.isEnabled(), "seed");
            }
            store.replaceMasking(config.masking());
            store.replaceRoleMappings(config.roleMapping());
            long v = changes.record("seed", "seed", "policy", null, null, ConfigMapper.json().valueToTree(config));
            log.info("Seeded config DB from YAML: {} rules, {} masking policies, {} role mappings (version {})",
                    config.rules().size(), config.masking().size(), config.roleMapping().size(), v);
        });
    }
}
