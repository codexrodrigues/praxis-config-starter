package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainCatalogRagPublicationState;
import org.praxisplatform.config.domain.DomainCatalogRagPublicationStatus;
import org.praxisplatform.config.repository.DomainCatalogRagPublicationStateRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DomainCatalogRagPublicationStateService {

    private final DomainCatalogRagPublicationStateRepository repository;

    public DomainCatalogRagPublicationStateService(
            DomainCatalogRagPublicationStateRepository repository) {
        this.repository = repository;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public long request(UUID releaseId, long expectedDocumentCount) {
        repository.ensureState(releaseId);
        DomainCatalogRagPublicationState state = repository.findForUpdate(releaseId).orElseThrow();
        Instant now = Instant.now();
        state.setRevision(state.getRevision() + 1L);
        state.setStatus(DomainCatalogRagPublicationStatus.PENDING);
        state.setExpectedDocumentCount(Math.max(0L, expectedDocumentCount));
        state.setPublishedDocumentCount(0L);
        state.setFailureKind(null);
        state.setRetryable(null);
        state.setRetryAfter(null);
        state.setRequestedAt(now);
        state.setStartedAt(null);
        state.setCompletedAt(null);
        state.setUpdatedAt(now);
        repository.save(state);
        return state.getRevision();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public boolean markPublishing(UUID releaseId, long revision) {
        DomainCatalogRagPublicationState state = current(releaseId, revision).orElse(null);
        if (state == null || state.getStatus() != DomainCatalogRagPublicationStatus.PENDING) {
            return false;
        }
        Instant now = Instant.now();
        state.setStatus(DomainCatalogRagPublicationStatus.PUBLISHING);
        state.setAttempt(state.getAttempt() + 1);
        state.setStartedAt(now);
        state.setCompletedAt(null);
        state.setUpdatedAt(now);
        repository.save(state);
        return true;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public boolean markPublished(UUID releaseId, long revision, long publishedDocumentCount) {
        DomainCatalogRagPublicationState state = current(releaseId, revision).orElse(null);
        if (state == null || state.getStatus() != DomainCatalogRagPublicationStatus.PUBLISHING) {
            return false;
        }
        Instant now = Instant.now();
        state.setStatus(DomainCatalogRagPublicationStatus.PUBLISHED);
        state.setPublishedDocumentCount(Math.max(0L, publishedDocumentCount));
        state.setFailureKind(null);
        state.setRetryable(null);
        state.setRetryAfter(null);
        state.setCompletedAt(now);
        state.setUpdatedAt(now);
        repository.save(state);
        return true;
    }

    /**
     * Reconciles terminal publication evidence when the current physical corpus is already exact.
     *
     * <p>This path does not represent a provider attempt. It repairs an absent or stale terminal
     * state after the vector corpus has independently proved exact expected/actual equality. Active
     * pending or publishing revisions remain owned by their current worker.</p>
     */
    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public boolean reconcilePublished(
            UUID releaseId,
            long expectedDocumentCount,
            long publishedDocumentCount) {
        boolean inserted = repository.ensureState(releaseId) > 0;
        DomainCatalogRagPublicationState state = repository.findForUpdate(releaseId).orElseThrow();
        long normalizedExpected = Math.max(0L, expectedDocumentCount);
        long normalizedPublished = Math.max(0L, publishedDocumentCount);
        boolean initialState = inserted
                && state.getRevision() == 0L
                && state.getAttempt() == 0
                && state.getStatus() == DomainCatalogRagPublicationStatus.PENDING;
        if (!initialState && (state.getStatus() == DomainCatalogRagPublicationStatus.PENDING
                || state.getStatus() == DomainCatalogRagPublicationStatus.PUBLISHING)) {
            return false;
        }
        if (state.getStatus() == DomainCatalogRagPublicationStatus.PUBLISHED
                && state.getExpectedDocumentCount() == normalizedExpected
                && state.getPublishedDocumentCount() == normalizedPublished
                && state.getFailureKind() == null
                && state.getRetryable() == null
                && state.getRetryAfter() == null) {
            return false;
        }
        Instant now = Instant.now();
        state.setRevision(state.getRevision() + 1L);
        state.setStatus(DomainCatalogRagPublicationStatus.PUBLISHED);
        state.setExpectedDocumentCount(normalizedExpected);
        state.setPublishedDocumentCount(normalizedPublished);
        state.setFailureKind(null);
        state.setRetryable(null);
        state.setRetryAfter(null);
        state.setRequestedAt(now);
        state.setStartedAt(null);
        state.setCompletedAt(now);
        state.setUpdatedAt(now);
        repository.save(state);
        return true;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public boolean markFailed(
            UUID releaseId,
            long revision,
            String failureKind,
            boolean retryable,
            Instant retryAfter) {
        DomainCatalogRagPublicationState state = current(releaseId, revision).orElse(null);
        if (state == null) {
            return false;
        }
        Instant now = Instant.now();
        state.setStatus(DomainCatalogRagPublicationStatus.FAILED);
        state.setFailureKind(sanitizeKind(failureKind));
        state.setRetryable(retryable);
        state.setRetryAfter(retryAfter);
        state.setCompletedAt(now);
        state.setUpdatedAt(now);
        repository.save(state);
        return true;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public Optional<StateSnapshot> snapshot(UUID releaseId) {
        return repository.findById(releaseId).map(this::snapshot);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public List<UUID> recoverInterrupted() {
        List<DomainCatalogRagPublicationState> states = repository.findAllByStatusIn(EnumSet.of(
                DomainCatalogRagPublicationStatus.PENDING,
                DomainCatalogRagPublicationStatus.PUBLISHING));
        Instant now = Instant.now();
        for (DomainCatalogRagPublicationState state : states) {
            state.setStatus(DomainCatalogRagPublicationStatus.PENDING);
            state.setFailureKind(null);
            state.setRetryable(null);
            state.setRetryAfter(null);
            state.setRequestedAt(now);
            state.setStartedAt(null);
            state.setCompletedAt(null);
            state.setUpdatedAt(now);
        }
        repository.saveAll(states);
        return states.stream().map(DomainCatalogRagPublicationState::getReleaseId).toList();
    }

    private Optional<DomainCatalogRagPublicationState> current(UUID releaseId, long revision) {
        return repository.findForUpdate(releaseId)
                .filter(state -> state.getRevision() == revision);
    }

    private String sanitizeKind(String failureKind) {
        if (failureKind == null || failureKind.isBlank()) {
            return "unknown";
        }
        String sanitized = failureKind.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(Locale.ROOT);
        return sanitized.length() <= 80 ? sanitized : sanitized.substring(0, 80);
    }

    private StateSnapshot snapshot(DomainCatalogRagPublicationState state) {
        return new StateSnapshot(
                state.getStatus(),
                state.getRevision(),
                state.getAttempt(),
                state.getExpectedDocumentCount(),
                state.getPublishedDocumentCount(),
                state.getFailureKind(),
                state.getRetryable(),
                state.getRetryAfter(),
                state.getRequestedAt(),
                state.getStartedAt(),
                state.getCompletedAt(),
                state.getUpdatedAt());
    }

    public record StateSnapshot(
            DomainCatalogRagPublicationStatus status,
            long revision,
            int attempt,
            long expectedDocumentCount,
            long publishedDocumentCount,
            String failureKind,
            Boolean retryable,
            Instant retryAfter,
            Instant requestedAt,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt
    ) { }
}
