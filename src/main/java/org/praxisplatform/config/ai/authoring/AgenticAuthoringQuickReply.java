package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringQuickReply(
        String id,
        String kind,
        String label,
        String prompt,
        String description,
        String icon,
        String tone,
        JsonNode contextHints
) {
    public AgenticAuthoringQuickReply(
            String id,
            String kind,
            String label,
            String prompt) {
        this(id, kind, label, prompt, null, null, null, null);
    }
}
