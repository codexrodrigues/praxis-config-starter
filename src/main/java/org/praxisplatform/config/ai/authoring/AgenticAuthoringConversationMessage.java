package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringConversationMessage(
        String id,
        String role,
        String text,
        String createdAt
) {
}
