package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.domain.AiTurnEvent;
import org.praxisplatform.config.repository.AiTurnRepository;
import org.praxisplatform.config.repository.AiTurnEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AiTurnEventServiceTest {

    @Mock
    private AiTurnEventRepository repository;
    @Mock
    private AiTurnRepository turnRepository;

    private AiTurnEventService service;

    @BeforeEach
    void setUp() {
        service = new AiTurnEventService(
                repository,
                turnRepository,
                new ObjectMapper(),
                new AiSensitiveDataRedactor());
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

        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId))
                .thenReturn(Optional.of(turn(threadId, turnId)));
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

        when(turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId))
                .thenReturn(Optional.of(turn(threadId, turnId)));
        when(repository.findFirstByThreadIdAndTurnIdOrderBySeqDesc(threadId, turnId)).thenReturn(Optional.empty());
        when(repository.findMaxSeq(threadId, turnId)).thenReturn(null);
        when(repository.saveAndFlush(any(AiTurnEvent.class))).thenAnswer(invocation -> {
            AiTurnEvent event = invocation.getArgument(0, AiTurnEvent.class);
            event.setEventId(eventId);
            if (event.getCreatedAt() == null) {
                event.setCreatedAt(Instant.now());
            }
            return event;
        });

        service.appendEvent(
                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                streamId,
                threadId,
                turnId,
                "status",
                Map.of("state", "in_progress"));

        var locks = (java.util.Map<String, ?>) ReflectionTestUtils.getField(service, "turnLocks");
        assertThat(locks).isNotNull();
        assertThat(locks).isEmpty();
    }

    private AiTurn turn(UUID threadId, UUID turnId) {
        return AiTurn.builder()
                .threadId(threadId)
                .turnId(turnId)
                .status(AiTurnStatus.PROCESSING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
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
