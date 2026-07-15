package org.praxisplatform.config.ai.authoring;

import java.util.List;

record AgenticAuthoringResourceSearchFocus(
        String primaryBusinessEntity,
        List<String> supportingConcepts,
        String desiredSurface,
        String uncertainty,
        String rationale
) {

    AgenticAuthoringResourceSearchFocus {
        primaryBusinessEntity = normalize(primaryBusinessEntity);
        supportingConcepts = supportingConcepts == null
                ? List.of()
                : supportingConcepts.stream()
                .map(AgenticAuthoringResourceSearchFocus::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(8)
                .toList();
        desiredSurface = normalize(desiredSurface);
        uncertainty = normalize(uncertainty);
        rationale = normalize(rationale);
    }

    boolean isEmpty() {
        return primaryBusinessEntity.isBlank()
                && supportingConcepts.isEmpty()
                && desiredSurface.isBlank()
                && uncertainty.isBlank()
                && rationale.isBlank();
    }

    String toRetrievalQueryPrefix() {
        StringBuilder builder = new StringBuilder();
        if (!primaryBusinessEntity.isBlank()) {
            builder.append("primary business entity: ").append(primaryBusinessEntity).append(". ");
        }
        builder.append("supporting concepts: ")
                .append(supportingConcepts.isEmpty() ? "none" : String.join(", ", supportingConcepts))
                .append(". ");
        builder.append("desired surface: ")
                .append(desiredSurface.isBlank() ? "unspecified" : desiredSurface)
                .append(". ");
        return builder.toString().trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
