package dev.queryguard.admin.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.queryguard.common.config.ConfigMapper;
import dev.queryguard.common.config.QueryGuardConfig;
import dev.queryguard.common.masking.MaskRule;
import dev.queryguard.common.masking.MaskStrategy;
import dev.queryguard.common.rules.Rule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads and replaces masking policies and role mappings, and assembles the full policy for dry runs. */
@Component
public class PolicyStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    public PolicyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MaskRule> masking() {
        return jdbc.sql("SELECT table_name, column_name, strategy, roles::text, except_roles::text FROM masking_policies ORDER BY table_name, column_name")
                .query((rs, i) -> new MaskRule(rs.getString(1), rs.getString(2),
                        MaskStrategy.valueOf(rs.getString(3).toUpperCase(Locale.ROOT)),
                        list(rs.getString(4)), list(rs.getString(5))))
                .list();
    }

    public void replaceMasking(List<MaskRule> rules) {
        jdbc.sql("DELETE FROM masking_policies").update();
        for (MaskRule r : rules) {
            jdbc.sql("""
                            INSERT INTO masking_policies (table_name, column_name, strategy, roles, except_roles)
                            VALUES (:t, :c, :s, CAST(:r AS jsonb), CAST(:e AS jsonb))
                            """)
                    .param("t", r.table()).param("c", r.column()).param("s", r.strategy().name())
                    .param("r", json(r.roles())).param("e", json(r.exceptRoles()))
                    .update();
        }
    }

    public Map<String, String> roleMappings() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT db_user, role FROM role_mappings ORDER BY db_user")
                .query(rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                });
        return out;
    }

    public void replaceRoleMappings(Map<String, String> mappings) {
        jdbc.sql("DELETE FROM role_mappings").update();
        mappings.forEach((user, role) -> jdbc.sql("INSERT INTO role_mappings (db_user, role) VALUES (:u, :r)")
                .param("u", user).param("r", role).update());
    }

    public List<Rule> enabledRules() {
        List<Rule> rules = new ArrayList<>();
        for (String body : jdbc.sql("SELECT body::text FROM rules WHERE enabled").query(String.class).list()) {
            try {
                rules.add(ConfigMapper.json().readValue(body, Rule.class));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return rules;
    }

    /** Same assembly the proxy's ConfigDbWatcher does, so a dry run matches what the proxy would decide. */
    public QueryGuardConfig current(QueryGuardConfig seed) {
        return seed.withPolicy(roleMappings(), enabledRules(), masking());
    }

    private static List<String> list(String json) {
        if (json == null) {
            return null;
        }
        try {
            return ConfigMapper.json().readValue(json, STRINGS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String json(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return ConfigMapper.json().writeValueAsString(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
