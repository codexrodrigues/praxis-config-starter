package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringSemanticDecision;
import org.praxisplatform.config.domain.AiTurnEvent;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.repository.AiTurnRepository;
import org.praxisplatform.config.repository.AiTurnEventRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AiTurnEventService {

    private final AiTurnEventRepository turnEventRepository;
    private final AiTurnRepository turnRepository;
    private final ObjectMapper objectMapper;
    private final AiSensitiveDataRedactor sensitiveDataRedactor;
    private final ConcurrentHashMap<String, ReentrantLock> turnLocks = new ConcurrentHashMap<>();

    @Value("${praxis.ai.stream.event-schema-version:v1}")
    private String eventSchemaVersion;

    @Value("${praxis.ai.stream.expires-seconds:900}")
    private long streamExpirySeconds;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public AiTurnEventEnvelope appendEvent(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String eventType,
            Object payload) {
        return appendEvent(principalContext, streamId, threadId, turnId, eventType, payload, null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public AiTurnEventEnvelope appendEvent(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String eventType,
            Object payload,
            UUID eventId) {
        if (principalContext == null
                || principalContext.tenantId() == null
                || principalContext.userId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identity context is required.");
        }
        if (streamId == null || threadId == null || turnId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "streamId/threadId/turnId are required.");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventType is required.");
        }

        String key = lockKey(threadId, turnId);
        ReentrantLock lock = turnLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            lockTurnForAppend(threadId, turnId);
            AiTurnEvent terminalEvent = turnEventRepository.findFirstByThreadIdAndTurnIdOrderBySeqDesc(threadId, turnId)
                    .filter(existing -> isTerminalType(existing.getEventType()))
                    .orElse(null);
            boolean terminal = isTerminalType(eventType);
            if (terminalEvent != null) {
                if (terminal
                        && safeUuidEquals(terminalEvent.getStreamId(), streamId)
                        && safeEquals(normalize(terminalEvent.getEventType()), normalize(eventType))) {
                    return toEnvelope(terminalEvent);
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Turn already reached terminal state.");
            }
            Long maxSeq = turnEventRepository.findMaxSeq(threadId, turnId);
            long nextSeq = maxSeq != null ? maxSeq + 1 : 1L;
            UUID resolvedEventId = eventId != null ? eventId : UUID.randomUUID();
            JsonNode payloadNode = toPayloadNode(payload);
            AiTurnEvent entity = AiTurnEvent.builder()
                    .tenantId(principalContext.tenantId())
                    .userId(principalContext.userId())
                    .environment(principalContext.environment())
                    .streamId(streamId)
                    .threadId(threadId)
                    .turnId(turnId)
                    .seq(nextSeq)
                    .eventId(resolvedEventId)
                    .eventType(eventType)
                    .payload(serializePayload(payloadNode))
                    .createdAt(Instant.now())
                    .build();
            try {
                AiTurnEvent saved = turnEventRepository.saveAndFlush(entity);
                return toEnvelope(saved);
            } catch (DataIntegrityViolationException ex) {
                throw mapIntegrityViolation(ex);
            }
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                turnLocks.remove(key, lock);
            }
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public ReplayResult replay(UUID streamId, String lastEventId, AiPrincipalContext principalContext) {
        StreamOwnership ownership = requireOwnership(streamId, principalContext);
        long afterSeq = 0L;
        String normalizedLastEventId = normalizeLastEventId(lastEventId);
        if (normalizedLastEventId != null) {
            UUID parsedLastEventId = parseLastEventId(normalizedLastEventId);
            Optional<AiTurnEvent> sameStream = turnEventRepository.findByStreamIdAndEventId(streamId, parsedLastEventId);
            if (sameStream.isEmpty()) {
                Optional<AiTurnEvent> foreignEvent = turnEventRepository.findByEventId(parsedLastEventId);
                if (foreignEvent.isPresent()) {
                    AiTurnEvent foreign = foreignEvent.get();
                    if (!isOwnedByPrincipal(foreign, principalContext)) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Last-Event-ID is outside authorized scope.");
                    }
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Last-Event-ID is outside stream scope.");
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID.");
            }
            afterSeq = sameStream.get().getSeq();
        }

        List<AiTurnEvent> events = afterSeq > 0
                ? turnEventRepository.findByStreamIdAndSeqGreaterThanOrderBySeqAsc(streamId, afterSeq)
                : turnEventRepository.findByStreamIdOrderBySeqAsc(streamId);
        List<AiTurnEventEnvelope> envelopes = events.stream().map(this::toEnvelope).toList();
        return new ReplayResult(ownership, envelopes, afterSeq);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public ReplayResult replayFromSeq(UUID streamId, long afterSeq, AiPrincipalContext principalContext) {
        StreamOwnership ownership = requireOwnership(streamId, principalContext);
        long normalizedAfterSeq = Math.max(afterSeq, 0L);
        List<AiTurnEvent> events = normalizedAfterSeq > 0
                ? turnEventRepository.findByStreamIdAndSeqGreaterThanOrderBySeqAsc(streamId, normalizedAfterSeq)
                : turnEventRepository.findByStreamIdOrderBySeqAsc(streamId);
        List<AiTurnEventEnvelope> envelopes = events.stream().map(this::toEnvelope).toList();
        return new ReplayResult(ownership, envelopes, normalizedAfterSeq);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public StreamOwnership requireOwnership(UUID streamId, AiPrincipalContext principalContext) {
        if (streamId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "streamId is required.");
        }
        if (principalContext == null
                || principalContext.tenantId() == null
                || principalContext.userId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identity context is required.");
        }
        AiTurnEvent firstEvent = turnEventRepository.findFirstByStreamIdOrderBySeqAsc(streamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stream not found."));
        if (!isOwnedByPrincipal(firstEvent, principalContext)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Stream access denied.");
        }
        Instant expiresAt = resolveExpiresAt(firstEvent);
        if (expiresAt.isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Stream expired.");
        }
        return new StreamOwnership(
                firstEvent.getStreamId(),
                firstEvent.getThreadId(),
                firstEvent.getTurnId(),
                firstEvent.getTenantId(),
                firstEvent.getUserId(),
                firstEvent.getEnvironment(),
                expiresAt);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public Optional<StreamStartMetadata> findStartMetadata(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return Optional.empty();
        }
        return turnEventRepository.findFirstByThreadIdAndTurnIdOrderBySeqAsc(threadId, turnId)
                .map(event -> new StreamStartMetadata(
                        event.getStreamId(),
                        event.getThreadId(),
                        event.getTurnId(),
                        event.getCreatedAt(),
                        resolveExpiresAt(event),
                        extractRequestHash(event),
                        event.getEventType()));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public Optional<AiTurnEventEnvelope> findLastEvent(UUID streamId) {
        if (streamId == null) {
            return Optional.empty();
        }
        return turnEventRepository.findFirstByStreamIdOrderBySeqDesc(streamId).map(this::toEnvelope);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public Optional<AgenticAuthoringSemanticDecision> findLatestSemanticDecision(
            UUID threadId,
            AiPrincipalContext principalContext) {
        if (threadId == null || principalContext == null) {
            return Optional.empty();
        }
        return turnEventRepository.findResultEventsByThreadIdOrderByNewest(threadId).stream()
                .filter(event -> isOwnedByPrincipal(event, principalContext))
                .map(event -> parsePayload(event.getPayload())
                        .path("intentResolution")
                        .path("semanticDecision"))
                .filter(node -> node != null && node.isObject() && node.hasNonNull("decisionId"))
                .map(node -> objectMapper.convertValue(node, AgenticAuthoringSemanticDecision.class))
                .findFirst();
    }

    public boolean isTerminalType(String eventType) {
        if (eventType == null) {
            return false;
        }
        return "result".equalsIgnoreCase(eventType)
                || "error".equalsIgnoreCase(eventType)
                || "cancelled".equalsIgnoreCase(eventType);
    }

    private boolean isOwnedByPrincipal(AiTurnEvent event, AiPrincipalContext principalContext) {
        if (event == null || principalContext == null) {
            return false;
        }
        if (!safeEquals(event.getTenantId(), principalContext.tenantId())) {
            return false;
        }
        if (!safeEquals(event.getUserId(), principalContext.userId())) {
            return false;
        }
        String eventEnvironment = normalize(event.getEnvironment());
        String principalEnvironment = normalize(principalContext.environment());
        return eventEnvironment == null || safeEquals(eventEnvironment, principalEnvironment);
    }

    private Instant resolveExpiresAt(AiTurnEvent firstEvent) {
        long ttl = Math.max(streamExpirySeconds, 60L);
        Instant createdAt = firstEvent.getCreatedAt() != null ? firstEvent.getCreatedAt() : Instant.now();
        return createdAt.plusSeconds(ttl);
    }

    private AiTurnEventEnvelope toEnvelope(AiTurnEvent entity) {
        return AiTurnEventEnvelope.builder()
                .eventId(entity.getEventId())
                .streamId(entity.getStreamId())
                .threadId(entity.getThreadId())
                .turnId(entity.getTurnId())
                .seq(entity.getSeq())
                .eventSchemaVersion(eventSchemaVersion)
                .timestamp(entity.getCreatedAt())
                .type(entity.getEventType())
                .payload(parsePayload(entity.getPayload()))
                .build();
    }

    private JsonNode parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            return sensitiveDataRedactor.sanitizeEventPayload(parsed);
        } catch (Exception ignored) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("raw", sensitiveDataRedactor.redactText(payload));
            return fallback;
        }
    }

    private JsonNode toPayloadNode(Object payload) {
        if (payload == null) {
            return objectMapper.createObjectNode();
        }
        JsonNode node = payload instanceof JsonNode jsonNode ? jsonNode : objectMapper.valueToTree(payload);
        return sensitiveDataRedactor.sanitizeEventPayload(node);
    }

    private String serializePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : objectMapper.createObjectNode());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize event payload.", ex);
        }
    }

    private UUID parseLastEventId(String lastEventId) {
        try {
            return UUID.fromString(lastEventId.trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID.");
        }
    }

    private String normalizeLastEventId(String lastEventId) {
        if (lastEventId == null) {
            return null;
        }
        String normalized = lastEventId.trim();
        if (normalized.isEmpty() || "null".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String extractRequestHash(AiTurnEvent event) {
        JsonNode payload = parsePayload(event.getPayload());
        JsonNode requestHash = payload != null ? payload.get("requestHash") : null;
        if (requestHash == null || requestHash.isNull()) {
            return null;
        }
        String value = requestHash.asText(null);
        return normalize(value);
    }

    private String lockKey(UUID threadId, UUID turnId) {
        return threadId + ":" + turnId;
    }

    private ResponseStatusException mapIntegrityViolation(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause() != null
                ? ex.getMostSpecificCause().getMessage()
                : ex.getMessage();
        String normalized = message != null ? message.toLowerCase() : "";
        if (normalized.contains("uk_ai_turn_event_event_id")
                || normalized.contains("unique")
                || normalized.contains("duplicate")) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate event detected.", ex);
        }
        if (normalized.contains("fk_ai_turn_event_turn")) {
            return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Turn reservation is missing before appending stream events.",
                    ex);
        }
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to append stream event.",
                ex);
    }

    private void lockTurnForAppend(UUID threadId, UUID turnId) {
        boolean exists = turnRepository.findByThreadIdAndTurnIdForUpdate(threadId, turnId).isPresent();
        if (!exists) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Turn reservation is missing before appending stream events.");
        }
    }

    private boolean safeEquals(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft == null) {
            return normalizedRight == null;
        }
        return normalizedLeft.equals(normalizedRight);
    }

    private boolean safeUuidEquals(UUID left, UUID right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record StreamOwnership(
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String tenantId,
            String userId,
            String environment,
            Instant expiresAt) {
    }

    public record ReplayResult(
            StreamOwnership ownership,
            List<AiTurnEventEnvelope> events,
            long afterSeq) {
    }

    public record StreamStartMetadata(
            UUID streamId,
            UUID threadId,
            UUID turnId,
            Instant createdAt,
            Instant expiresAt,
            String requestHash,
            String firstEventType) {
    }
}
