package org.praxisplatform.config.dto;

public record AiAssistantObservationFeedbackRequest(
        String rating,
        String reasonCode,
        String comment
) {
}
