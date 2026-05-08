package org.praxisplatform.config.ai.authoring;

import java.util.List;
import java.util.Map;

public record AgenticAuthoringSemanticRefinement(
        String schemaVersion,
        String refinementKind,
        List<String> preserve,
        Map<String, String> replace,
        Map<String, List<String>> add,
        List<String> remove,
        String rationale,
        Double confidence
) {

    static final String SCHEMA_VERSION = "praxis-agentic-authoring-semantic-refinement.v1";

    public AgenticAuthoringSemanticRefinement {
        schemaVersion = safe(schemaVersion).isBlank() ? SCHEMA_VERSION : safe(schemaVersion);
        refinementKind = safe(refinementKind);
        preserve = preserve == null ? List.of() : List.copyOf(preserve);
        replace = replace == null ? Map.of() : Map.copyOf(replace);
        add = add == null ? Map.of() : Map.copyOf(add);
        remove = remove == null ? List.of() : List.copyOf(remove);
        rationale = safe(rationale);
        confidence = confidence == null ? null : Math.max(0d, Math.min(1d, confidence));
    }

    static AgenticAuthoringSemanticRefinement none() {
        return new AgenticAuthoringSemanticRefinement(
                SCHEMA_VERSION,
                "",
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                "",
                null);
    }

    boolean active() {
        return !refinementKind.isBlank();
    }

    boolean preservesResource() {
        return preserve.contains("resource") || preserve.contains("source") || preserve.contains("data_source");
    }

    String replacement(String key) {
        return replace.getOrDefault(key, "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
