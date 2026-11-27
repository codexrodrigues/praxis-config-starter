package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgenticAuthoringConversationMessage(
        String id,
        String role,
        String text,
        String createdAt
) {
    public AgenticAuthoringConversationMessage(String role, String text) {
        this(null, role, text, null);
    }
}
