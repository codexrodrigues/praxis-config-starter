package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.DomainCatalogRagPublicationState;
import org.praxisplatform.config.domain.DomainCatalogRagPublicationStatus;
import org.praxisplatform.config.repository.DomainCatalogRagPublicationStateRepository;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class DomainCatalogRagPublicationStateServiceTest {

    private static final UUID RELEASE_ID = UUID.fromString("d070c524-b67a-4cc0-b754-d652d7424e14");

    @Mock private DomainCatalogRagPublicationStateRepository repository;

    @Test
    void shouldAdvanceRevisionAndResetLifecycleWhenPublicationIsRequested() {
        DomainCatalogRagPublicationState state = state(DomainCatalogRagPublicationStatus.FAILED, 3L);
        state.setPublishedDocumentCount(4L);
        state.setFailureKind("quota_exhausted");
        state.setRetryable(false);
        state.setCompletedAt(Instant.now().minusSeconds(1));
        when(repository.findForUpdate(RELEASE_ID)).thenReturn(Optional.of(state));

        long revision = service().request(RELEASE_ID, 13L);

        assertThat(revision).isEqualTo(4L);
        assertThat(state.getStatus()).isEqualTo(DomainCatalogRagPublicationStatus.PENDING);
        assertThat(state.getExpectedDocumentCount()).isEqualTo(13L);
        assertThat(state.getPublishedDocumentCount()).isZero();
        assertThat(state.getFailureKind()).isNull();
        assertThat(state.getRetryable()).isNull();
        assertThat(state.getCompletedAt()).isNull();
        verify(repository).ensureState(RELEASE_ID);
    }

    @Test
    void shouldPublishOnlyTheCurrentPendingRevision() {
        DomainCatalogRagPublicationState state = state(DomainCatalogRagPublicationStatus.PENDING, 5L);
        when(repository.findForUpdate(RELEASE_ID)).thenReturn(Optional.of(state));

        assertThat(service().markPublishing(RELEASE_ID, 5L)).isTrue();
        assertThat(state.getStatus()).isEqualTo(DomainCatalogRagPublicationStatus.PUBLISHING);
        assertThat(state.getAttempt()).isEqualTo(1);

        assertThat(service().markPublished(RELEASE_ID, 5L, 11L)).isTrue();
        assertThat(state.getStatus()).isEqualTo(DomainCatalogRagPublicationStatus.PUBLISHED);
        assertThat(state.getPublishedDocumentCount()).isEqualTo(11L);
        assertThat(state.getCompletedAt()).isNotNull();
    }

    @Test
    void shouldPersistSanitizedTypedFailureEvidence() {
        DomainCatalogRagPublicationState state = state(DomainCatalogRagPublicationStatus.PUBLISHING, 5L);
        when(repository.findForUpdate(RELEASE_ID)).thenReturn(Optional.of(state));

        assertThat(service().markFailed(
                RELEASE_ID, 5L, "QUOTA EXHAUSTED\nsecret", false, null)).isTrue();

        assertThat(state.getStatus()).isEqualTo(DomainCatalogRagPublicationStatus.FAILED);
        assertThat(state.getFailureKind()).isEqualTo("quota_exhausted_secret");
        assertThat(state.getRetryable()).isFalse();
        assertThat(state.getCompletedAt()).isNotNull();
    }

    @Test
    void shouldIgnoreSupersededRevision() {
        DomainCatalogRagPublicationState state = state(DomainCatalogRagPublicationStatus.PENDING, 6L);
        when(repository.findForUpdate(RELEASE_ID)).thenReturn(Optional.of(state));

        assertThat(service().markPublishing(RELEASE_ID, 5L)).isFalse();
        assertThat(state.getStatus()).isEqualTo(DomainCatalogRagPublicationStatus.PENDING);
    }

    @Test
    void shouldRecoverInterruptedPublicationsAsPending() {
        DomainCatalogRagPublicationState state = state(DomainCatalogRagPublicationStatus.PUBLISHING, 5L);
        state.setStartedAt(Instant.now().minusSeconds(30));
        when(repository.findAllByStatusIn(any())).thenReturn(List.of(state));

        List<UUID> recovered = service().recoverInterrupted();

        assertThat(recovered).containsExactly(RELEASE_ID);
        assertThat(state.getStatus()).isEqualTo(DomainCatalogRagPublicationStatus.PENDING);
        assertThat(state.getStartedAt()).isNull();
        verify(repository).saveAll(List.of(state));
    }

    private DomainCatalogRagPublicationStateService service() {
        return new DomainCatalogRagPublicationStateService(repository);
    }

    private DomainCatalogRagPublicationState state(
            DomainCatalogRagPublicationStatus status,
            long revision) {
        DomainCatalogRagPublicationState state = new DomainCatalogRagPublicationState();
        state.setReleaseId(RELEASE_ID);
        state.setStatus(status);
        state.setRevision(revision);
        state.setRequestedAt(Instant.now().minusSeconds(1));
        state.setUpdatedAt(Instant.now().minusSeconds(1));
        return state;
    }
}
