package dev.queryguard.common.analysis;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One client query (a Q message or a Parse), which can hold several statements. */
public record QueryInfo(String sql, String normalized, String fingerprint, List<StatementInfo> statements) {

    public boolean allParsed() {
        return statements.stream().allMatch(StatementInfo::parsed);
    }

    public Set<String> tables() {
        Set<String> all = new LinkedHashSet<>();
        statements.forEach(s -> all.addAll(s.tables()));
        return all;
    }

    /** The kind we report in events: the first statement's, or OTHER for an empty query. */
    public StatementKind primaryKind() {
        return statements.isEmpty() ? StatementKind.OTHER : statements.get(0).kind();
    }

    public boolean isEmpty() {
        return statements.isEmpty();
    }
}
