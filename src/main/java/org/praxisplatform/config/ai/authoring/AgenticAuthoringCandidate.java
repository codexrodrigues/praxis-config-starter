package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringCandidate(
        String resourcePath,
        String operation,
        String schemaUrl,
        String submitUrl,
        String submitMethod,
        double score,
        String reason,
        List<String> evidence
) {
}
