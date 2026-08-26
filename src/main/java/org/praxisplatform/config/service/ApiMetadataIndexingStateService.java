package org.praxisplatform.config.service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.domain.ApiMetadataIndexingState;
import org.praxisplatform.config.domain.ApiMetadataIndexingStatus;
import org.praxisplatform.config.repository.ApiMetadataIndexingStateRepository;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiMetadataIndexingStateService {

    private final ApiMetadataIndexingStateRepository stateRepository;
    private final ApiMetadataRepository metadataRepository;

    public ApiMetadataIndexingStateService(
            ApiMetadataIndexingStateRepository stateRepository,
            ApiMetadataRepository metadataRepository) {
        this.stateRepository = stateRepository;
        this.metadataRepository = metadataRepository;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public long request(ApiMetadataIndexingScope scope) {
        ApiMetadataIndexingState state = lockOrCreate(scope);
        Instant now = Instant.now();
        state.setRevision(state.getRevision() + 1L);
        state.setStatus(ApiMetadataIndexingStatus.PENDING);
        state.setExpectedDocumentCount(0L);
        state.setLegacyIndexedDocumentCount(0L);
        state.setPublishedDocumentCount(0L);
        state.setFailureCode(null);
        state.setFailureMessage(null);
        state.setRequestedAt(now);
        state.setStartedAt(null);
        state.setCompletedAt(null);
        state.setUpdatedAt(now);
        stateRepository.save(state);
        return state.getRevision();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public void updateExpectedCount(ApiMetadataIndexingScope scope, long revision, long expectedCount) {
        ApiMetadataIndexingState state = lock(scope).orElseThrow();
        if (state.getRevision() == revision) {
            state.setExpectedDocumentCount(expectedCount);
            state.setUpdatedAt(Instant.now());
            stateRepository.save(state);
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public Optional<StateSnapshot> snapshot(ApiMetadataIndexingScope scope) {
        return stateRepository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                        scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId())
                .map(this::snapshot);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public Optional<WorkClaim> claimIfQuiet(ApiMetadataIndexingScope scope, Duration quietPeriod) {
        Optional<ApiMetadataIndexingState> locked = lock(scope);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        ApiMetadataIndexingState state = locked.get();
        if (state.getStatus() != ApiMetadataIndexingStatus.PENDING) {
            return Optional.empty();
        }
        Instant quietBefore = Instant.now().minus(quietPeriod);
        if (state.getRequestedAt() != null && state.getRequestedAt().isAfter(quietBefore)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        state.setStatus(ApiMetadataIndexingStatus.PROCESSING);
        state.setAttempt(state.getAttempt() + 1);
        state.setStartedAt(now);
        state.setCompletedAt(null);
        state.setUpdatedAt(now);
        stateRepository.save(state);
        return Optional.of(new WorkClaim(scope, state.getRevision(), state.getExpectedDocumentCount()));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public boolean commitLegacyEmbeddings(
            ApiMetadataIndexingScope scope,
            long revision,
            Map<Long, List<Float>> embeddingsById) {
        ApiMetadataIndexingState state = lock(scope).orElse(null);
        if (!isCurrentProcessing(state, revision)) {
            return false;
        }
        if (embeddingsById != null && !embeddingsById.isEmpty()) {
            Map<Long, ApiMetadata> rowsById = new LinkedHashMap<>();
            metadataRepository.findAllById(embeddingsById.keySet())
                    .forEach(row -> rowsById.put(row.getId(), row));
            for (Map.Entry<Long, List<Float>> entry : embeddingsById.entrySet()) {
                ApiMetadata row = rowsById.get(entry.getKey());
                if (row != null) {
                    row.setEmbedding(entry.getValue());
                }
            }
            metadataRepository.saveAll(rowsById.values());
        }
        long indexed = metadataRepository
                .countByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndEmbeddingIsNotNull(
                        scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId());
        state.setLegacyIndexedDocumentCount(indexed);
        state.setUpdatedAt(Instant.now());
        stateRepository.save(state);
        return true;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public boolean complete(ApiMetadataIndexingScope scope, long revision, long publishedCount) {
        ApiMetadataIndexingState state = lock(scope).orElse(null);
        if (!isCurrentProcessing(state, revision)) {
            return false;
        }
        long expected = metadataRepository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId());
        long indexed = metadataRepository
                .countByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndEmbeddingIsNotNull(
                        scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId());
        if (indexed != expected || publishedCount != expected) {
            failState(
                    state,
                    "INDEXING_COUNT_MISMATCH",
                    "Indexed API metadata counts do not match the persisted release snapshot.");
            return true;
        }
        Instant now = Instant.now();
        state.setExpectedDocumentCount(expected);
        state.setLegacyIndexedDocumentCount(indexed);
        state.setPublishedDocumentCount(publishedCount);
        state.setStatus(ApiMetadataIndexingStatus.READY);
        state.setFailureCode(null);
        state.setFailureMessage(null);
        state.setCompletedAt(now);
        state.setUpdatedAt(now);
        stateRepository.save(state);
        return true;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public boolean fail(
            ApiMetadataIndexingScope scope,
            long revision,
            String failureCode,
            String failureMessage) {
        ApiMetadataIndexingState state = lock(scope).orElse(null);
        if (state == null || state.getRevision() != revision) {
            return false;
        }
        failState(state, failureCode, failureMessage);
        return true;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public void failPending(
            ApiMetadataIndexingScope scope,
            String failureCode,
            String failureMessage) {
        ApiMetadataIndexingState state = lock(scope).orElse(null);
        if (state != null && state.getStatus() == ApiMetadataIndexingStatus.PENDING) {
            failState(state, failureCode, failureMessage);
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public List<ApiMetadataIndexingScope> recoverInterrupted() {
        List<ApiMetadataIndexingState> states = stateRepository.findAllByStatusIn(
                EnumSet.of(ApiMetadataIndexingStatus.PENDING, ApiMetadataIndexingStatus.PROCESSING));
        Instant now = Instant.now();
        for (ApiMetadataIndexingState state : states) {
            state.setStatus(ApiMetadataIndexingStatus.PENDING);
            state.setFailureCode(null);
            state.setFailureMessage(null);
            state.setRequestedAt(now);
            state.setStartedAt(null);
            state.setCompletedAt(null);
            state.setUpdatedAt(now);
        }
        stateRepository.saveAll(states);
        return states.stream()
                .map(state -> new ApiMetadataIndexingScope(
                        state.getTenantId(), state.getEnvironment(), state.getServiceKey(), state.getReleaseId()))
                .toList();
    }

    private ApiMetadataIndexingState lockOrCreate(ApiMetadataIndexingScope scope) {
        stateRepository.ensureState(
                scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId());
        return lock(scope).orElseThrow();
    }

    private Optional<ApiMetadataIndexingState> lock(ApiMetadataIndexingScope scope) {
        return stateRepository.findForUpdate(
                scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId());
    }

    private boolean isCurrentProcessing(ApiMetadataIndexingState state, long revision) {
        return state != null
                && state.getRevision() == revision
                && state.getStatus() == ApiMetadataIndexingStatus.PROCESSING;
    }

    private void failState(ApiMetadataIndexingState state, String code, String message) {
        Instant now = Instant.now();
        state.setStatus(ApiMetadataIndexingStatus.FAILED);
        state.setFailureCode(code);
        state.setFailureMessage(sanitize(message));
        state.setCompletedAt(now);
        state.setUpdatedAt(now);
        stateRepository.save(state);
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "API metadata indexing failed.";
        }
        String sanitized = message.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return sanitized.length() <= 320 ? sanitized : sanitized.substring(0, 320);
    }

    private StateSnapshot snapshot(ApiMetadataIndexingState state) {
        return new StateSnapshot(
                state.getStatus(),
                state.getRevision(),
                state.getAttempt(),
                state.getExpectedDocumentCount(),
                state.getLegacyIndexedDocumentCount(),
                state.getPublishedDocumentCount(),
                state.getFailureCode(),
                state.getFailureMessage(),
                state.getRequestedAt(),
                state.getStartedAt(),
                state.getCompletedAt(),
                state.getUpdatedAt());
    }

    public record WorkClaim(ApiMetadataIndexingScope scope, long revision, long expectedDocumentCount) { }

    public record StateSnapshot(
            ApiMetadataIndexingStatus status,
            long revision,
            int attempt,
            long expectedDocumentCount,
            long legacyIndexedDocumentCount,
            long publishedDocumentCount,
            String failureCode,
            String failureMessage,
            Instant requestedAt,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt
    ) { }
}
