package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.domain.AiTurnEvent;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.repository.AiTurnRepository;
import org.praxisplatform.config.repository.AiTurnEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiTurnEventServiceTest {

    @Mock
    private AiTurnEventRepository repository;
    @Mock
    private AiTurnRepository turnRepository;

    @Mock
    private org.praxisplatform.config.repository.AiThreadRepository threadRepository;

    private AiTurnEventService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AiTurnEventService(
                repository,
                turnRepository,
                threadRepository,
                objectMapper,
                new AiSensitiveDataRedactor());
        org.mockito.Mockito.lenient().when(threadRepository.findByThreadIdForUpdate(any())).thenAnswer(invocation ->
                Optional.of(org.praxisplatform.config.domain.AiThread.builder().threadId(invocation.getArgument(0))
                        .tenantId("tenant-a").userId("user-a").environment("prod").build()));
        ReflectionTestUtils.setField(service, "eventSchemaVersion", "v1");
        ReflectionTestUtils.setField(service, "streamExpirySeconds", 900L);
    }

    @Test
    void shouldReturnForbiddenWhenLastEventIdBelongsToDifferentStreamScope() {
        UUID streamId = UUID.randomUUID();
        UUID foreignStreamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID lastEventId = UUID.randomUUID();

        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        AiTurnEvent firstEvent = event(streamId, threadId, turnId, 1L, UUID.randomUUID(), "tenant-a", "user-a", "prod");
        AiTurnEvent foreignEvent = event(foreignStreamId, threadId, turnId, 10L, lastEventId, "tenant-a", "user-a", "prod");

        when(repository.findFirstByStreamIdOrderBySeqAsc(streamId)).thenReturn(Optional.of(firstEvent));
        when(repository.findByStreamIdAndEventId(streamId, lastEventId)).thenReturn(Optional.empty());
        when(repository.findByEventId(lastEventId)).thenReturn(Optional.of(foreignEvent));

        assertThatThrownBy(() -> service.replay(streamId, lastEventId.toString(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void shouldReturnBadRequestForUnknownLastEventId() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID unknownEventId = UUID.randomUUID();

        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        AiTurnEvent firstEvent = event(streamId, threadId, turnId, 1L, UUID.randomUUID(), "tenant-a", "user-a", "prod");

        when(repository.findFirstByStreamIdOrderBySeqAsc(streamId)).thenReturn(Optional.of(firstEvent));
        when(repository.findByStreamIdAndEventId(streamId, unknownEventId)).thenReturn(Optional.empty());
        when(repository.findByEventId(unknownEventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay(streamId, unknownEventId.toString(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void shouldReturnGoneWhenStreamIsExpired() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        AiTurnEvent firstEvent = event(streamId, threadId, turnId, 1L, UUID.randomUUID(), "tenant-a", "user-a", "prod");
        firstEvent.setCreatedAt(Instant.now().minusSeconds(1200));

        when(repository.findFirstByStreamIdOrderBySeqAsc(streamId)).thenReturn(Optional.of(firstEvent));

        assertThatThrownBy(() -> service.replay(streamId, null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    @Test
    void shouldTreatNullLiteralLastEventIdAsAbsent() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        AiTurnEvent firstEvent = event(streamId, threadId, turnId, 1L, UUID.randomUUID(), "tenant-a", "user-a", "prod");

        when(repository.findFirstByStreamIdOrderBySeqAsc(streamId)).thenReturn(Optional.of(firstEvent));
        when(repository.findByStreamIdOrderBySeqAsc(streamId)).thenReturn(List.of(firstEvent));

        AiTurnEventService.ReplayResult replay = service.replay(streamId, "null", principal);

        assertThat(replay.afterSeq()).isZero();
        assertThat(replay.events()).hasSize(1);
        verify(repository, never()).findByEventId(any(UUID.class));
    }

    @Test
    void shouldRejectAppendingAfterTerminalEvent() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiTurnEvent terminal = event(streamId, threadId, turnId, 3L, UUID.randomUUID(), "tenant-a", "user-a", "prod");
        terminal.setEventType("cancelled");
        AiTurn reservedTurn = turn(threadId, turnId);
        reservedTurn.setNextEventSeq(4L);
        reservedTurn.setTerminalEventType("cancelled");

        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId))
                .thenReturn(Optional.of(reservedTurn));
        when(repository.findFirstByThreadIdAndTurnIdOrderBySeqDesc(threadId, turnId)).thenReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service.appendEvent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                "result",
                Map.of("response", Map.of("ok", true))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void shouldRejectAppendWhenTurnReservationIsMissing() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();

        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.appendEvent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                "status",
                Map.of("state", "started")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReleaseTurnLockAfterNonTerminalAppend() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        AiTurn reservedTurn = turn(threadId, turnId);
        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId))
                .thenReturn(Optional.of(reservedTurn));
        when(repository.saveAndFlush(any(AiTurnEvent.class))).thenAnswer(invocation -> {
            AiTurnEvent event = invocation.getArgument(0, AiTurnEvent.class);
            event.setEventId(eventId);
            if (event.getCreatedAt() == null) {
                event.setCreatedAt(Instant.now());
            }
            return event;
        });

        var envelope = service.appendEvent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                "status",
                Map.of("state", "in_progress"));

        assertThat(envelope.getSeq()).isEqualTo(1L);
        assertThat(reservedTurn.getNextEventSeq()).isEqualTo(2L);

        var locks = (java.util.Map<String, ?>) ReflectionTestUtils.getField(service, "turnLocks");
        assertThat(locks).isNotNull();
        assertThat(locks).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAppendPostgresEventWithSingleAtomicStatement() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        ReflectionTestUtils.setField(service, "configJdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "postgresAtomicAppendAvailable", true);
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)))
                .thenReturn(List.of(7L));

        AiTurnEventEnvelope envelope = service.appendEvent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                "status",
                Map.of("phase", "intent.resolve"));

        assertThat(envelope.getSeq()).isEqualTo(7L);
        assertThat(envelope.getType()).isEqualTo("status");
        assertThat(envelope.getPayload().path("phase").asText()).isEqualTo("intent.resolve");
        verify(turnRepository, never()).findByThreadIdAndTurnIdForUpdate(threadId, turnId);
        verify(repository, never()).saveAndFlush(any(AiTurnEvent.class));
    }

    @Test
    void shouldPersistOnlySafeQuickReplyContextHintProjection() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ObjectNode payload = contextualQuickReplyPayload();

        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId))
                .thenReturn(Optional.of(turn(threadId, turnId)));
        when(repository.saveAndFlush(any(AiTurnEvent.class))).thenAnswer(invocation -> {
            AiTurnEvent event = invocation.getArgument(0, AiTurnEvent.class);
            event.setEventId(eventId);
            return event;
        });

        var envelope = service.appendEvent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                "result",
                payload);

        ArgumentCaptor<AiTurnEvent> persistedEvent = ArgumentCaptor.forClass(AiTurnEvent.class);
        verify(repository).saveAndFlush(persistedEvent.capture());
        JsonNode storedPayload = objectMapper.readTree(persistedEvent.getValue().getPayload());
        assertSafeContextualQuickReplyProjection(storedPayload);
        assertThat(envelope.getPayload()).isEqualTo(storedPayload);
    }

    @Test
    void shouldPersistOperationalTokenCountersWithoutExposingCredentials() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode telemetry = payload.putObject("decisionDiagnostics").putObject("providerTelemetry");
        telemetry.put("inputTokens", 120);
        telemetry.put("outputTokens", 18);
        telemetry.putNull("cacheWriteInputTokens");
        telemetry.put("totalTokens", 138);
        telemetry.put("accessToken", "secret-access-token");

        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId))
                .thenReturn(Optional.of(turn(threadId, turnId)));
        when(repository.saveAndFlush(any(AiTurnEvent.class))).thenAnswer(invocation -> {
            AiTurnEvent event = invocation.getArgument(0, AiTurnEvent.class);
            event.setEventId(eventId);
            return event;
        });

        var envelope = service.appendEvent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                "result",
                payload);

        ArgumentCaptor<AiTurnEvent> persistedEvent = ArgumentCaptor.forClass(AiTurnEvent.class);
        verify(repository).saveAndFlush(persistedEvent.capture());
        JsonNode storedTelemetry = objectMapper.readTree(persistedEvent.getValue().getPayload())
                .path("decisionDiagnostics")
                .path("providerTelemetry");
        assertThat(storedTelemetry.path("inputTokens").asInt()).isEqualTo(120);
        assertThat(storedTelemetry.path("outputTokens").asInt()).isEqualTo(18);
        assertThat(storedTelemetry.path("cacheWriteInputTokens").isNull()).isTrue();
        assertThat(storedTelemetry.path("totalTokens").asInt()).isEqualTo(138);
        assertThat(storedTelemetry.path("accessToken").asText()).isEqualTo("[REDACTED]");
        assertThat(envelope.getPayload().path("decisionDiagnostics").path("providerTelemetry"))
                .isEqualTo(storedTelemetry);
    }

    @Test
    void shouldSanitizeLegacyQuickReplyPayloadAgainDuringReplay() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiTurnEvent legacyEvent = event(
                streamId,
                threadId,
                turnId,
                1L,
                UUID.randomUUID(),
                "tenant-a",
                "user-a",
                "prod");
        legacyEvent.setEventType("result");
        legacyEvent.setPayload(objectMapper.writeValueAsString(contextualQuickReplyPayload()));

        when(repository.findFirstByStreamIdOrderBySeqAsc(streamId)).thenReturn(Optional.of(legacyEvent));
        when(repository.findByStreamIdOrderBySeqAsc(streamId)).thenReturn(List.of(legacyEvent));

        AiTurnEventService.ReplayResult replay = service.replay(
                streamId,
                null,
                new AiPrincipalContext("tenant-a", "user-a", "prod", true));

        assertThat(replay.events()).hasSize(1);
        assertSafeContextualQuickReplyProjection(replay.events().get(0).getPayload());
    }

    @Test
    void intentResolvedIsNotTerminal() {
        assertThat(service.isTerminalType("intent.resolved")).isFalse();
    }

    @Test
    void shouldResolveSemanticDecisionIssuedByQuickReplyInOwnedThread() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiTurnEvent result = event(
                streamId,
                threadId,
                turnId,
                8L,
                UUID.randomUUID(),
                "tenant-a",
                "user-a",
                "prod");
        result.setEventType("result");
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode decision = payload.putArray("quickReplies")
                .addObject()
                .putObject("semanticDecision");
        decision.put("schemaVersion", "praxis-agentic-authoring-semantic-decision.v1");
        decision.put("decisionId", "governed-choice-1");
        decision.put("operationKind", "explore");
        decision.put("artifactKind", "api_catalog");
        decision.put("changeKind", "answer_api_catalog_question");
        decision.putObject("constraints").put("quickReplyId", "governed-domain:explore-data");
        payload.putObject("intentResolution")
                .putObject("apiCatalogAnswer")
                .putArray("candidateApis")
                .addObject()
                .put("resourcePath", "/api/human-resources/funcionarios");
        payload.putObject("evidenceBundle")
                .putArray("entries")
                .addObject()
                .putArray("evidence")
                .add("source-release:praxis-service:human-resources.departamentos:release-hash");
        result.setPayload(objectMapper.writeValueAsString(payload));

        when(repository.findResultEventsByThreadIdOrderByNewest(threadId)).thenReturn(List.of(result));

        Optional<org.praxisplatform.config.ai.authoring.AgenticAuthoringSemanticDecision> resolved =
                service.findPersistedSemanticDecision(
                        threadId,
                        "governed-choice-1",
                        new AiPrincipalContext("tenant-a", "user-a", "prod", true));

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().artifactKind()).isEqualTo("api_catalog");
        assertThat(resolved.orElseThrow().constraints().path("quickReplyId").asText())
                .isEqualTo("governed-domain:explore-data");
        AiTurnEventService.PersistedSemanticDecisionContext context =
                service.findPersistedSemanticDecisionContext(
                                threadId,
                                "governed-choice-1",
                                new AiPrincipalContext("tenant-a", "user-a", "prod", true))
                        .orElseThrow();
        assertThat(context.issuedCandidateApis()).hasSize(2);
        assertThat(context.issuedCandidateApis().get(0).path("resourcePath").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(context.issuedCandidateApis().get(1).path("resourcePath").asText())
                .isEqualTo("/api/human-resources/departamentos");
    }

    @Test
    void shouldNotResolveSemanticDecisionFromAnotherPrincipal() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiTurnEvent result = event(
                UUID.randomUUID(),
                threadId,
                UUID.randomUUID(),
                8L,
                UUID.randomUUID(),
                "tenant-a",
                "another-user",
                "prod");
        result.setEventType("result");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("intentResolution")
                .putObject("semanticDecision")
                .put("decisionId", "foreign-decision");
        result.setPayload(objectMapper.writeValueAsString(payload));
        when(repository.findResultEventsByThreadIdOrderByNewest(threadId)).thenReturn(List.of(result));

        assertThat(service.findPersistedSemanticDecision(
                        threadId,
                        "foreign-decision",
                        new AiPrincipalContext("tenant-a", "user-a", "prod", true)))
                .isEmpty();
    }

    @Test
    void shouldAppendStartEventOnlyWhenTurnHasNoExistingStart() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId))
                .thenReturn(Optional.of(turn(threadId, turnId)));
        when(repository.saveAndFlush(any(AiTurnEvent.class))).thenAnswer(invocation -> {
            AiTurnEvent event = invocation.getArgument(0, AiTurnEvent.class);
            event.setEventId(eventId);
            if (event.getCreatedAt() == null) {
                event.setCreatedAt(Instant.now());
            }
            return event;
        });

        AiTurnEventService.StreamStartAppendResult result = service.appendStartEventIfAbsent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                Map.of("state", "started"));

        assertThat(result.appended()).isTrue();
        assertThat(result.event().getEventId()).isEqualTo(eventId);
        assertThat(result.event().getStreamId()).isEqualTo(streamId);
        assertThat(result.event().getThreadId()).isEqualTo(threadId);
        assertThat(result.event().getTurnId()).isEqualTo(turnId);
        assertThat(result.event().getSeq()).isEqualTo(1L);
        assertThat(result.event().getType()).isEqualTo("status");
    }

    @Test
    void shouldReturnExistingStartWhenAppendStartAlreadyExists() {
        UUID streamId = UUID.randomUUID();
        UUID existingStreamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AiTurnEvent existingStart = event(existingStreamId, threadId, turnId, 1L, eventId, "tenant-a", "user-a", "prod");
        AiTurn reservedTurn = turn(threadId, turnId);
        reservedTurn.setNextEventSeq(2L);

        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId))
                .thenReturn(Optional.of(reservedTurn));
        when(repository.findFirstByThreadIdAndTurnIdOrderBySeqAsc(threadId, turnId))
                .thenReturn(Optional.of(existingStart));

        AiTurnEventService.StreamStartAppendResult result = service.appendStartEventIfAbsent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                Map.of("state", "started"));

        assertThat(result.appended()).isFalse();
        assertThat(result.event().getEventId()).isEqualTo(eventId);
        assertThat(result.event().getStreamId()).isEqualTo(existingStreamId);
        assertThat(result.event().getThreadId()).isEqualTo(threadId);
        assertThat(result.event().getTurnId()).isEqualTo(turnId);
        assertThat(result.event().getSeq()).isEqualTo(1L);
        verify(repository, never()).saveAndFlush(any(AiTurnEvent.class));
    }

    private AiTurn turn(UUID threadId, UUID turnId) {
        return AiTurn.builder()
                .threadId(threadId)
                .turnId(turnId)
                .status(AiTurnStatus.PROCESSING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .nextEventSeq(1L)
                .build();
    }

    private ObjectNode contextualQuickReplyPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode hints = payload.putArray("quickReplies").addObject().putObject("contextHints");
        hints.put("source", "component-capability-catalog");
        hints.put("kind", "contextual-preview-action");
        hints.put("operationKind", "modify");
        hints.put("changeKind", "enable_chart_drilldown");
        hints.put("capabilityId", "praxis-chart.drilldown.enable@0.1.0");
        hints.put("targetComponentId", "praxis-chart");
        hints.put("selectedWidgetKey", "department-chart");
        hints.put("surfacePresentation", "modal");
        hints.put("surfaceActionId", "surface.open");
        hints.put("surfaceWidgetId", "praxis-table");
        hints.put("submitUrl", "/api/people?token=do-not-persist");
        hints.putObject("previewPage").putArray("widgets").addObject().put("key", "private-widget");
        hints.putObject("targetWidgetSnapshot").put("key", "department-chart");
        return payload;
    }

    private void assertSafeContextualQuickReplyProjection(JsonNode payload) {
        JsonNode hints = payload.path("quickReplies").path(0).path("contextHints");
        assertThat(hints.path("source").asText()).isEqualTo("component-capability-catalog");
        assertThat(hints.path("kind").asText()).isEqualTo("contextual-preview-action");
        assertThat(hints.path("operationKind").asText()).isEqualTo("modify");
        assertThat(hints.path("changeKind").asText()).isEqualTo("enable_chart_drilldown");
        assertThat(hints.path("capabilityId").asText())
                .isEqualTo("praxis-chart.drilldown.enable@0.1.0");
        assertThat(hints.path("targetComponentId").asText()).isEqualTo("praxis-chart");
        assertThat(hints.path("selectedWidgetKey").asText()).isEqualTo("department-chart");
        assertThat(hints.path("surfacePresentation").asText()).isEqualTo("modal");
        assertThat(hints.path("surfaceActionId").asText()).isEqualTo("surface.open");
        assertThat(hints.path("surfaceWidgetId").asText()).isEqualTo("praxis-table");
        assertThat(hints.path("submitUrl").asText()).isEqualTo("/api/people?token=[REDACTED]");
        assertThat(hints.path("previewPage").asText()).isEqualTo("[REDACTED]");
        assertThat(hints.path("targetWidgetSnapshot").asText()).isEqualTo("[REDACTED]");
    }

    private AiTurnEvent event(
            UUID streamId,
            UUID threadId,
            UUID turnId,
            long seq,
            UUID eventId,
            String tenantId,
            String userId,
            String environment) {
        return AiTurnEvent.builder()
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(seq)
                .eventId(eventId)
                .eventType("status")
                .tenantId(tenantId)
                .userId(userId)
                .environment(environment)
                .payload("{}")
                .createdAt(Instant.now())
                .build();
    }
}
