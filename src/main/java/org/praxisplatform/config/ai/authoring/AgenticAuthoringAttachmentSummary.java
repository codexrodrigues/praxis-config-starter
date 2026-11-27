package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgenticAuthoringAttachmentSummary(
        String id,
        String name,
        String kind,
        String mimeType,
        Long sizeBytes,
        String source,
        Boolean hasPreview
) {
}
