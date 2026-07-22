package org.praxisplatform.config.dto;

public record AiAudioTranscriptionResponse(
        String schemaVersion,
        String text,
        String provider,
        String model,
        String language) {
}
