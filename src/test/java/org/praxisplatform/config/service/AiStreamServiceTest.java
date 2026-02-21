package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AiStreamServiceTest {

    @Mock
    private AiThreadService threadService;
    @Mock
    private AiTurnService turnService;
    @Mock
    private AiOrchestratorService orchestratorService;
    @Mock
    private AiTurnEventService turnEventService;
    @Mock
    private AiStreamAccessTokenService streamAccessTokenService;

    private AiStreamService streamService;

    @BeforeEach
    void setUp() {
        streamService = new AiStreamService(
                threadService,
                turnService,
                orchestratorService,
                turnEventService,
                new ObjectMapper(),
                new AiSensitiveDataRedactor(),
                streamAccessTokenService);
        ReflectionTestUtils.setField(streamService, "eventSchemaVersion", "v1");
        ReflectionTestUtils.setField(streamService, "streamExpiresSeconds", 900L);
        ReflectionTestUtils.setField(streamService, "emitterTimeoutMs", 60_000L);
        ReflectionTestUtils.setField(streamService, "heartbeatSeconds", 15L);
        ReflectionTestUtils.setField(streamService, "processingPollSeconds", 1L);
        ReflectionTestUtils.setField(streamService, "processingMaxPolls", 3);
        ReflectionTestUtils.setField(streamService, "maxActiveGlobal", 200);
        ReflectionTestUtils.setField(streamService, "maxActivePerTenant", 50);
        ReflectionTestUtils.setField(streamService, "maxActivePerUser", 10);
        lenient().when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");
        lenient().when(streamAccessTokenService.resolvePrincipalContext(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2, AiPrincipalContext.class));
    }

    @AfterEach
    void tearDown() {
        if (streamService != null) {
            streamService.shutdown();
        }
    }

    @Test
    void shouldRequireClientTurnIdForStreamStart() {
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        UUID threadId = UUID.randomUUID();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);

        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        assertThatThrownBy(() -> streamService.startStream(request, "http://localhost:8088", principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException status = (ResponseStatusException) ex;
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepIdempotencyAllowlistAlignedWithRequestContractFields() {
        Set<String> internalOnlyFields = Set.of("streamTransport", "streamTurnPreclaimed");
        Set<String> requestContractFields = Arrays.stream(AiOrchestratorRequest.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .filter(fieldName -> !internalOnlyFields.contains(fieldName))
                .collect(Collectors.toSet());
        Object rawAllowlist = ReflectionTestUtils.getField(AiStreamService.class, "IDEMPOTENCY_HASH_FIELDS");
        assertThat(rawAllowlist).isInstanceOf(List.class);
        Set<String> hashAllowlist = new HashSet<>((List<String>) rawAllowlist);

        assertThat(hashAllowlist).containsExactlyInAnyOrderElementsOf(requestContractFields);
    }

    @Test
    void shouldReserveTurnForStreamingWithoutCallingBeginTurnOnStart() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        AiTurnEventEnvelope startEnvelope = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .threadId(threadId)
                .turnId(turnId)
                .seq(1L)
                .eventSchemaVersion("v1")
                .timestamp(Instant.now())
                .type("status")
                .payload(new ObjectMapper().valueToTree(Map.of("state", "started")))
                .build();

        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);
        when(turnEventService.findStartMetadata(threadId, turnId)).thenReturn(Optional.empty());
        when(turnEventService.appendEvent(any(), any(), any(), any(), anyString(), any())).thenReturn(startEnvelope);
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);

        streamService.startStream(request, "http://localhost:8088", principal);

        verify(turnService).reserveTurnForStreaming(threadId, turnId);
        verify(turnService, never()).beginTurn(any(), any());
    }

    @Test
    void shouldReturnConflictWhenStartIdempotencyHashDiverges() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        AiTurnEventService.StreamStartMetadata metadata = new AiTurnEventService.StreamStartMetadata(
                streamId,
                threadId,
                turnId,
                Instant.now(),
                Instant.now().plusSeconds(900),
                "different-request-hash",
                "status");
        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);
        when(turnEventService.findStartMetadata(threadId, turnId)).thenReturn(Optional.of(metadata));

        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);

        assertThatThrownBy(() -> streamService.startStream(request, "http://localhost:8088", principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void shouldAcceptLegacyStartHashThatIgnoresInternalStreamFlags() throws Exception {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        request.setSessionId(threadId);
        request.setStreamTransport(Boolean.TRUE);
        request.setStreamTurnPreclaimed(Boolean.TRUE);
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        AiTurnEventService.StreamStartMetadata metadata = new AiTurnEventService.StreamStartMetadata(
                streamId,
                threadId,
                turnId,
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(900),
                legacyRequestHash(request),
                "status");
        AiTurnEventEnvelope terminal = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(3L)
                .type("result")
                .timestamp(Instant.now())
                .payload(new ObjectMapper().createObjectNode())
                .build();
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);
        when(turnEventService.findStartMetadata(threadId, turnId)).thenReturn(Optional.of(metadata));
        when(turnEventService.requireOwnership(streamId, principal))
                .thenReturn(new AiTurnEventService.StreamOwnership(
                        streamId,
                        threadId,
                        turnId,
                        "tenant-a",
                        "user-a",
                        "prod",
                        Instant.now().plusSeconds(900)));
        when(turnEventService.findLastEvent(streamId)).thenReturn(Optional.of(terminal));
        when(turnEventService.isTerminalType("result")).thenReturn(true);

        AiStreamService.StreamStartResult result = streamService.startStream(request, "http://localhost:8088", principal);

        assertThat(result.created()).isFalse();
        assertThat(result.response().getStreamId()).isEqualTo(streamId);
    }

    @Test
    void shouldResolveCancelAsCompletedWhenTerminalAlreadyPersisted() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        AiTurnEventService.StreamOwnership ownership = new AiTurnEventService.StreamOwnership(
                streamId,
                threadId,
                turnId,
                "tenant-a",
                "user-a",
                "prod",
                Instant.now().plusSeconds(900));
        AiTurnEventEnvelope completed = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(3L)
                .type("result")
                .timestamp(Instant.now())
                .payload(new ObjectMapper().createObjectNode())
                .build();
        when(turnEventService.requireOwnership(streamId, new AiPrincipalContext("tenant-a", "user-a", "prod", true)))
                .thenReturn(ownership);
        when(turnEventService.findLastEvent(streamId))
                .thenReturn(Optional.empty(), Optional.of(completed));
        when(turnEventService.isTerminalType("result")).thenReturn(true);
        when(turnEventService.appendEvent(any(), eq(streamId), eq(threadId), eq(turnId), eq("cancelled"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "terminal already reached"));

        AiPatchStreamCancelResponse response = streamService.cancelStream(
                streamId,
                null,
                new AiPrincipalContext("tenant-a", "user-a", "prod", true));

        assertThat(response.getTerminalState()).isEqualTo("completed");
        verify(turnService, never()).cancelTurn(threadId, turnId);
        verify(turnService).completeTurn(threadId, turnId);
    }

    @Test
    void shouldRejectStartWhenTenantCapacityIsExceeded() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);
        when(turnEventService.findStartMetadata(threadId, turnId)).thenReturn(Optional.empty());

        ReflectionTestUtils.setField(streamService, "maxActivePerTenant", 1);
        var tenantCounters = (Map<String, java.util.concurrent.atomic.AtomicInteger>) ReflectionTestUtils.getField(
                streamService,
                "tenantActiveCounts");
        tenantCounters.put("tenant-a", new java.util.concurrent.atomic.AtomicInteger(1));

        assertThatThrownBy(() -> streamService.startStream(
                request,
                "http://localhost:8088",
                new AiPrincipalContext("tenant-a", "user-a", "prod", true)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectResumePathWhenTenantCapacityIsExceeded() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        AiTurnEventService.StreamStartMetadata metadata = new AiTurnEventService.StreamStartMetadata(
                streamId,
                threadId,
                turnId,
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(900),
                null,
                "status");
        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);
        when(turnEventService.findStartMetadata(threadId, turnId)).thenReturn(Optional.of(metadata));
        when(turnEventService.requireOwnership(streamId, new AiPrincipalContext("tenant-a", "user-a", "prod", true)))
                .thenReturn(new AiTurnEventService.StreamOwnership(
                        streamId,
                        threadId,
                        turnId,
                        "tenant-a",
                        "user-a",
                        "prod",
                        Instant.now().plusSeconds(900)));
        when(turnEventService.findLastEvent(streamId)).thenReturn(Optional.empty());

        ReflectionTestUtils.setField(streamService, "maxActivePerTenant", 1);
        var tenantCounters = (Map<String, java.util.concurrent.atomic.AtomicInteger>) ReflectionTestUtils.getField(
                streamService,
                "tenantActiveCounts");
        tenantCounters.put("tenant-a", new java.util.concurrent.atomic.AtomicInteger(1));

        assertThatThrownBy(() -> streamService.startStream(
                request,
                "http://localhost:8088",
                new AiPrincipalContext("tenant-a", "user-a", "prod", true)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(turnService, never()).beginTurn(threadId, turnId);
    }

    @Test
    void shouldMarkResumedRequestAsPreclaimedForSingleTurnAcquisition() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        AiTurnEventService.StreamStartMetadata metadata = new AiTurnEventService.StreamStartMetadata(
                streamId,
                threadId,
                turnId,
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(900),
                null,
                "status");
        AiTurnEventEnvelope envelope = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(2L)
                .type("status")
                .timestamp(Instant.now())
                .payload(new ObjectMapper().createObjectNode())
                .build();
        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);
        when(turnEventService.findStartMetadata(threadId, turnId)).thenReturn(Optional.of(metadata));
        when(turnEventService.requireOwnership(streamId, new AiPrincipalContext("tenant-a", "user-a", "prod", true)))
                .thenReturn(new AiTurnEventService.StreamOwnership(
                        streamId,
                        threadId,
                        turnId,
                        "tenant-a",
                        "user-a",
                        "prod",
                        Instant.now().plusSeconds(900)));
        when(turnEventService.findLastEvent(streamId)).thenReturn(Optional.empty());
        when(turnService.beginTurn(threadId, turnId)).thenReturn(AiTurnService.TurnDecision.PROCESS);
        when(turnEventService.appendEvent(any(), eq(streamId), eq(threadId), eq(turnId), anyString(), any()))
                .thenReturn(envelope);
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(org.praxisplatform.config.dto.AiOrchestratorResponse.builder()
                        .type("patch")
                        .build());

        streamService.startStream(request, "http://localhost:8088", new AiPrincipalContext("tenant-a", "user-a", "prod", true));

        ArgumentCaptor<AiOrchestratorRequest> captor = ArgumentCaptor.forClass(AiOrchestratorRequest.class);
        verify(orchestratorService, timeout(1000)).generatePatch(
                captor.capture(),
                eq("http://localhost:8088"),
                eq("tenant-a"),
                eq("user-a"),
                eq("prod"));
        AiOrchestratorRequest resumed = captor.getValue();
        assertThat(resumed.getStreamTransport()).isEqualTo(Boolean.TRUE);
        assertThat(resumed.getStreamTurnPreclaimed()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void shouldReconcileLegacyOrphanTailWhenResumeDecisionIsDone() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        AiTurnEventService.StreamStartMetadata metadata = new AiTurnEventService.StreamStartMetadata(
                streamId,
                threadId,
                turnId,
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(900),
                null,
                "status");
        AiTurnEventEnvelope nonTerminalTail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(2L)
                .type("status")
                .timestamp(Instant.now())
                .payload(new ObjectMapper().createObjectNode())
                .build();
        AiTurnEventEnvelope reconciledTerminal = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(3L)
                .type("error")
                .timestamp(Instant.now())
                .payload(new ObjectMapper().valueToTree(Map.of("message", "reconciled")))
                .build();
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);
        when(turnEventService.findStartMetadata(threadId, turnId)).thenReturn(Optional.of(metadata));
        when(turnEventService.requireOwnership(streamId, principal))
                .thenReturn(new AiTurnEventService.StreamOwnership(
                        streamId,
                        threadId,
                        turnId,
                        "tenant-a",
                        "user-a",
                        "prod",
                        Instant.now().plusSeconds(900)));
        when(turnEventService.findLastEvent(streamId)).thenReturn(Optional.of(nonTerminalTail));
        when(turnService.beginTurn(threadId, turnId)).thenReturn(AiTurnService.TurnDecision.DONE);
        when(turnService.findTurnStatus(threadId, turnId)).thenReturn(AiTurnStatus.DONE);
        when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> {
                    String eventType = invocation.getArgument(0, String.class);
                    return "result".equalsIgnoreCase(eventType)
                            || "error".equalsIgnoreCase(eventType)
                            || "cancelled".equalsIgnoreCase(eventType);
                });
        when(turnEventService.appendEvent(any(), eq(streamId), eq(threadId), eq(turnId), eq("error"), any()))
                .thenReturn(reconciledTerminal);

        streamService.startStream(request, "http://localhost:8088", principal);

        verify(turnEventService).appendEvent(any(), eq(streamId), eq(threadId), eq(turnId), eq("error"), any());
        verify(turnService).completeTurn(threadId, turnId);
        verify(orchestratorService, never()).generatePatch(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldReconcileLegacyCancelledTailAsCancelledTerminal() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(new ObjectMapper().createObjectNode())
                .build();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .build();
        AiTurnEventService.StreamStartMetadata metadata = new AiTurnEventService.StreamStartMetadata(
                streamId,
                threadId,
                turnId,
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(900),
                null,
                "status");
        AiTurnEventEnvelope nonTerminalTail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(2L)
                .type("status")
                .timestamp(Instant.now())
                .payload(new ObjectMapper().createObjectNode())
                .build();
        AiTurnEventEnvelope reconciledTerminal = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(3L)
                .type("cancelled")
                .timestamp(Instant.now())
                .payload(new ObjectMapper().valueToTree(Map.of("state", "cancelled")))
                .build();
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        when(threadService.resolveThread(request, "tenant-a", "user-a", "prod", "Atualizar tabela"))
                .thenReturn(thread);
        when(turnEventService.findStartMetadata(threadId, turnId)).thenReturn(Optional.of(metadata));
        when(turnEventService.requireOwnership(streamId, principal))
                .thenReturn(new AiTurnEventService.StreamOwnership(
                        streamId,
                        threadId,
                        turnId,
                        "tenant-a",
                        "user-a",
                        "prod",
                        Instant.now().plusSeconds(900)));
        when(turnEventService.findLastEvent(streamId)).thenReturn(Optional.of(nonTerminalTail));
        when(turnService.beginTurn(threadId, turnId)).thenReturn(AiTurnService.TurnDecision.DONE);
        when(turnService.findTurnStatus(threadId, turnId)).thenReturn(AiTurnStatus.CANCELLED);
        when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> {
                    String eventType = invocation.getArgument(0, String.class);
                    return "result".equalsIgnoreCase(eventType)
                            || "error".equalsIgnoreCase(eventType)
                            || "cancelled".equalsIgnoreCase(eventType);
                });
        when(turnEventService.appendEvent(any(), eq(streamId), eq(threadId), eq(turnId), eq("cancelled"), any()))
                .thenReturn(reconciledTerminal);

        streamService.startStream(request, "http://localhost:8088", principal);

        verify(turnEventService).appendEvent(any(), eq(streamId), eq(threadId), eq(turnId), eq("cancelled"), any());
        verify(turnService).cancelTurn(threadId, turnId);
        verify(turnService, never()).completeTurn(threadId, turnId);
        verify(orchestratorService, never()).generatePatch(any(), anyString(), anyString(), anyString(), anyString());
    }

    private String legacyRequestHash(AiOrchestratorRequest request) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.valueToTree(request);
        node.remove("streamTransport");
        node.remove("streamTurnPreclaimed");
        byte[] raw = mapper.writeValueAsBytes(node);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    }
}
