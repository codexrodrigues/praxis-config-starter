package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringResourceCandidatesResult(
        boolean valid,
        String tool,
        String retrievalQuery,
        String artifactKind,
        List<AgenticAuthoringCandidate> candidates,
        List<String> warnings
) {
}
