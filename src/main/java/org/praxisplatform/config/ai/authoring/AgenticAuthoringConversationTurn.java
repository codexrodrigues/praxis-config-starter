package org.praxisplatform.config.ai.authoring;

import java.util.List;

record AgenticAuthoringConversationTurn(
        String userPrompt,
        String effectivePrompt,
        String sourcePrompt,
        String assistantMessage,
        List<String> questions,
        boolean answeredPendingClarification
) {
}
