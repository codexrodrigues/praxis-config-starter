package org.praxisplatform.config.service;

public record AiAudioTranscriptionRequest(
        byte[] audio,
        String fileName,
        String contentType,
        String language) {
}
