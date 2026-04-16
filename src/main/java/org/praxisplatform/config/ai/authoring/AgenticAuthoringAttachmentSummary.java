package org.praxisplatform.config.ai.authoring;

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
