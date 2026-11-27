package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgenticAuthoringPendingClarification(
        String sourcePrompt,
        List<String> questions,
        String assistantMessage,
        String clientTurnId,
        JsonNode diagnostics
) {
}
