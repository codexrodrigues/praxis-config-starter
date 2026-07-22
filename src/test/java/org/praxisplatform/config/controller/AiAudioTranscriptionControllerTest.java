package org.praxisplatform.config.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiAudioTranscriptionResponse;
import org.praxisplatform.config.service.AiAudioTranscriptionRequest;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class AiAudioTranscriptionControllerTest {

    @Test
    void transcribesWithinServerResolvedPrincipalScope() throws Exception {
        AiProviderManagementService management = mock(AiProviderManagementService.class);
        AiPrincipalContextResolver principals = mock(AiPrincipalContextResolver.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(principals.resolve(servletRequest, "header-tenant", "header-user", "header-env"))
                .thenReturn(new AiPrincipalContext("principal-tenant", "principal-user", "prod", true));
        when(management.transcribeAudio(any(AiAudioTranscriptionRequest.class),
                eq("principal-tenant"), eq("principal-user"), eq("prod")))
                .thenReturn(new AiAudioTranscriptionResponse(
                        "praxis-ai-audio-transcription.v1",
                        "Criar uma página de funcionários",
                        "openai",
                        "gpt-4o-mini-transcribe",
                        "pt-BR"));
        AiAudioTranscriptionController controller = new AiAudioTranscriptionController(management, principals);

        var response = controller.transcribe(
                new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1, 2, 3}),
                "pt-BR",
                "header-tenant",
                "header-user",
                "header-env",
                servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Criar uma página de funcionários", response.getBody().text());
        verify(management).transcribeAudio(any(AiAudioTranscriptionRequest.class),
                eq("principal-tenant"), eq("principal-user"), eq("prod"));
    }

    @Test
    void mapsMissingProviderCapabilityToUnprocessableEntity() throws Exception {
        AiProviderManagementService management = mock(AiProviderManagementService.class);
        AiPrincipalContextResolver principals = mock(AiPrincipalContextResolver.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(principals.resolve(any(), any(), any(), any()))
                .thenReturn(new AiPrincipalContext("tenant", "user", "prod", true));
        when(management.transcribeAudio(any(), any(), any(), any()))
                .thenThrow(new UnsupportedOperationException("capability unavailable"));
        AiAudioTranscriptionController controller = new AiAudioTranscriptionController(management, principals);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.transcribe(
                new MockMultipartFile("audio", "voice.m4a", "audio/mp4", new byte[] {1}),
                "pt-BR", null, null, null, servletRequest));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }
}
