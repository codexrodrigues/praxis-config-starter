package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiPatchStreamStartResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiStreamService;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class AiPatchStreamControllerTest {

    @Mock
    private AiStreamService streamService;
    @Mock
    private AiPrincipalContextResolver principalContextResolver;
    @Mock
    private AiStreamAccessTokenService streamAccessTokenService;

    private AiPatchStreamController controller;
    private MockHttpServletRequest servletRequest;
    private AiPrincipalContext principalContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new AiPatchStreamController(streamService, principalContextResolver, streamAccessTokenService);
        servletRequest = new MockHttpServletRequest("POST", "/api/praxis/config/ai/patch/stream/start");
        servletRequest.setScheme("http");
        servletRequest.setServerName("localhost");
        servletRequest.setServerPort(8088);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        principalContext = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        lenient().when(streamAccessTokenService.isSignedUrlTokenMode()).thenReturn(false);
        when(principalContextResolver.resolve(servletRequest, "tenant-a", "user-a", "prod"))
                .thenReturn(principalContext);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldReturnCreatedOnStreamStart() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(objectMapper.createObjectNode())
                .build();
        AiPatchStreamStartResponse startResponse = AiPatchStreamStartResponse.builder()
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .eventSchemaVersion("v1")
                .streamAuthMode("cookie")
                .expiresAt(Instant.now().plusSeconds(900))
                .fallbackPatchUrl("/api/praxis/config/ai/patch")
                .build();
        when(streamService.startStream(request, "http://localhost:8088", principalContext))
                .thenReturn(new AiStreamService.StreamStartResult(startResponse, true));

        ResponseEntity<?> response = controller.start(
                request,
                servletRequest,
                null,
                "tenant-a",
                "user-a",
                "prod",
                null,
                null,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isInstanceOf(AiPatchStreamStartResponse.class);
        AiPatchStreamStartResponse body = (AiPatchStreamStartResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStreamId()).isEqualTo(streamId);
    }

    @Test
    void shouldReturnConflictWhenStreamStartSchemaHashDiffersFromExpected() {
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(UUID.randomUUID())
                .schemaHash("deadbeef")
                .currentState(objectMapper.createObjectNode())
                .build();

        ResponseEntity<?> response = controller.start(
                request,
                servletRequest,
                null,
                "tenant-a",
                "user-a",
                "prod",
                null,
                null,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(AiOrchestratorController.CONTRACT_VERSION_HEADER))
                .isEqualTo(AiOrchestratorController.DEFAULT_CONTRACT_VERSION);
        assertThat(response.getHeaders().getFirst(AiOrchestratorController.CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(AiOrchestratorController.DEFAULT_CONTRACT_SCHEMA_HASH);
        assertThat(response.getBody()).isInstanceOf(AiOrchestratorResponse.class);
        AiOrchestratorResponse body = (AiOrchestratorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(AiOrchestratorController.CODE_SCHEMA_HASH_MISMATCH);
        verifyNoInteractions(streamService);
    }

    @Test
    void shouldReturnConflictWhenStreamStartContractVersionIsUnsupported() {
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(UUID.randomUUID())
                .contractVersion("v9.9")
                .schemaHash(AiOrchestratorController.DEFAULT_CONTRACT_SCHEMA_HASH)
                .currentState(objectMapper.createObjectNode())
                .build();

        ResponseEntity<?> response = controller.start(
                request,
                servletRequest,
                null,
                "tenant-a",
                "user-a",
                "prod",
                null,
                null,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(AiOrchestratorResponse.class);
        AiOrchestratorResponse body = (AiOrchestratorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(AiOrchestratorController.CODE_UNSUPPORTED_CONTRACT);
        verifyNoInteractions(streamService);
    }

    @Test
    void shouldForwardLastEventIdAndAccessTokenToStreamConnect() {
        UUID streamId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        when(streamService.connect(streamId, "evt-header", "signed-token", principalContext)).thenReturn(emitter);

        SseEmitter responseEmitter = controller.stream(
                streamId,
                servletRequest,
                null,
                "evt-param",
                "signed-token",
                "evt-header",
                "tenant-a",
                "user-a",
                "prod");

        assertThat(responseEmitter).isSameAs(emitter);
    }

    @Test
    void shouldIgnoreNullLiteralLastEventIdHeaderAndUseQueryParam() {
        UUID streamId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        when(streamService.connect(streamId, "evt-param", "signed-token", principalContext)).thenReturn(emitter);

        SseEmitter responseEmitter = controller.stream(
                streamId,
                servletRequest,
                null,
                "evt-param",
                "signed-token",
                "null",
                "tenant-a",
                "user-a",
                "prod");

        assertThat(responseEmitter).isSameAs(emitter);
    }

    @Test
    void shouldAllowTokenOnlyStreamConnectWhenPrincipalCannotBeResolved() {
        UUID streamId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        when(streamAccessTokenService.isSignedUrlTokenMode()).thenReturn(true);
        when(principalContextResolver.resolve(servletRequest, "tenant-a", "user-a", "prod"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "identity missing"));
        when(streamService.connect(streamId, "evt-header", "signed-token", null)).thenReturn(emitter);

        SseEmitter responseEmitter = controller.stream(
                streamId,
                servletRequest,
                null,
                "evt-param",
                "signed-token",
                "evt-header",
                "tenant-a",
                "user-a",
                "prod");

        assertThat(responseEmitter).isSameAs(emitter);
    }

    @Test
    void shouldPropagateUnauthorizedWhenCookieModeAndPrincipalCannotBeResolved() {
        UUID streamId = UUID.randomUUID();
        when(streamAccessTokenService.isSignedUrlTokenMode()).thenReturn(false);
        when(principalContextResolver.resolve(servletRequest, "tenant-a", "user-a", "prod"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required"));

        assertThatThrownBy(() -> controller.stream(
                streamId,
                servletRequest,
                null,
                "evt-param",
                "signed-token",
                "evt-header",
                "tenant-a",
                "user-a",
                "prod"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void shouldPropagateGoneFromProbe() {
        UUID streamId = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.GONE, "Stream expired."))
                .when(streamService)
                .probeStream(streamId, "signed-token", principalContext);

        assertThatThrownBy(() -> controller.probe(
                streamId,
                servletRequest,
                null,
                "signed-token",
                "tenant-a",
                "user-a",
                "prod"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    @Test
    void shouldMapCancelNotFoundToDeterministicBody() {
        UUID streamId = UUID.randomUUID();
        when(streamService.cancelStream(streamId, "signed-token", principalContext))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));

        ResponseEntity<AiPatchStreamCancelResponse> response = controller.cancel(
                streamId,
                servletRequest,
                null,
                "signed-token",
                "tenant-a",
                "user-a",
                "prod");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTerminalState()).isEqualTo("not_found");
        verify(streamService).cancelStream(streamId, "signed-token", principalContext);
    }

    @Test
    void shouldPropagateRequestIdIntoMdcDuringStart() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(objectMapper.createObjectNode())
                .build();
        AiPatchStreamStartResponse startResponse = AiPatchStreamStartResponse.builder()
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .eventSchemaVersion("v1")
                .streamAuthMode("cookie")
                .expiresAt(Instant.now().plusSeconds(900))
                .fallbackPatchUrl("/api/praxis/config/ai/patch")
                .build();
        when(streamService.startStream(request, "http://localhost:8088", principalContext))
                .thenAnswer(invocation -> {
                    assertThat(MDC.get("requestId")).isEqualTo("req-correlation-1");
                    return new AiStreamService.StreamStartResult(startResponse, true);
                });

        controller.start(
                request,
                servletRequest,
                "req-correlation-1",
                "tenant-a",
                "user-a",
                "prod",
                null,
                null,
                null);

        assertThat(MDC.get("requestId")).isNull();
    }
}
