package org.praxisplatform.config.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.web.multipart.MultipartFile;
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
                .thenThrow(new UnsupportedOperationException(
                        "Configured provider does not support governed audio transcription: openai"));
        AiAudioTranscriptionController controller = new AiAudioTranscriptionController(management, principals);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.transcribe(
                new MockMultipartFile("audio", "voice.m4a", "audio/mp4", new byte[] {1}),
                "pt-BR", null, null, null, servletRequest));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        assertEquals(
                "Configured provider does not support governed audio transcription: openai",
                error.getReason());
    }

    @Test
    void rejectsEmptyAudioBeforeResolvingPrincipalOrInvokingManagement() {
        AiProviderManagementService management = mock(AiProviderManagementService.class);
        AiPrincipalContextResolver principals = mock(AiPrincipalContextResolver.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        AiAudioTranscriptionController controller = new AiAudioTranscriptionController(management, principals);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.transcribe(
                new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[0]),
                null, null, null, null, servletRequest));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("Audio payload is required.", error.getReason());
        verifyNoInteractions(principals, management);
    }

    @Test
    void rejectsAudioAboveTheTwelveMebibyteLimitBeforeReadingOrDispatchingIt() throws Exception {
        AiProviderManagementService management = mock(AiProviderManagementService.class);
        AiPrincipalContextResolver principals = mock(AiPrincipalContextResolver.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        MultipartFile audio = mock(MultipartFile.class);
        when(audio.isEmpty()).thenReturn(false);
        when(audio.getSize()).thenReturn(12L * 1024L * 1024L + 1L);
        AiAudioTranscriptionController controller = new AiAudioTranscriptionController(management, principals);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.transcribe(
                audio, null, null, null, null, servletRequest));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("Audio payload exceeds the 12 MiB limit.", error.getReason());
        verify(audio, never()).getBytes();
        verifyNoInteractions(principals, management);
    }
}
