package org.praxisplatform.config.dto;

/** Local configuration readiness; does not probe provider connectivity or quota. */
public record AiAudioTranscriptionCapabilitiesResponse(boolean available) {
}
