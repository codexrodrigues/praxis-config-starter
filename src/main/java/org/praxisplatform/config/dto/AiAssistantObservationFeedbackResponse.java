package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

public record AiAssistantObservationFeedbackResponse(
        UUID feedbackId,
        UUID observationId,
        String rating,
        String reasonCode,
        String commentPreview,
        Instant createdAt
) {
}
