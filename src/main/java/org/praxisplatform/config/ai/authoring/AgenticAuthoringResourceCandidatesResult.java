package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringResourceCandidatesResult(
        boolean valid,
        String tool,
        String retrievalQuery,
        String artifactKind,
        String assistantMessage,
        List<AgenticAuthoringCandidate> candidates,
        List<AgenticAuthoringQuickReply> quickReplies,
        List<String> warnings
) {
}
