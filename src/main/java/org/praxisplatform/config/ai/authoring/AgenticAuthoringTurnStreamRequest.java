package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringTurnStreamRequest(
        String userPrompt,
        String targetApp,
        String targetComponentId,
        String currentRoute,
        JsonNode currentPage,
        String selectedWidgetKey,
        String provider,
        String model,
        String apiKey,
        String sessionId,
        String clientTurnId,
        List<AgenticAuthoringConversationMessage> conversationMessages,
        AgenticAuthoringPendingClarification pendingClarification,
        List<AgenticAuthoringAttachmentSummary> attachmentSummaries,
        JsonNode contextHints,
        AgenticAuthoringComponentCapabilitiesResult componentCapabilities
) {
}
