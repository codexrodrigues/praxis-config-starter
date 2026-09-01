package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.domain.ApiMetadataIndexingState;
import org.praxisplatform.config.domain.ApiMetadataIndexingStatus;
import org.praxisplatform.config.repository.ApiMetadataIndexingStateRepository;
import org.praxisplatform.config.repository.ApiMetadataRepository;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class ApiMetadataIndexingStateServiceTest {

    private static final ApiMetadataIndexingScope SCOPE =
            new ApiMetadataIndexingScope("tenant-a", "prod", "default", "release-1");

    @Mock private ApiMetadataIndexingStateRepository stateRepository;
    @Mock private ApiMetadataRepository metadataRepository;

    @Test
    void shouldAdvancePersistedGenerationAndResetDerivedLifecycle() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.READY, 3L);
        state.setLegacyIndexedDocumentCount(4L);
        state.setPublishedDocumentCount(4L);
        when(stateRepository.findForUpdate("tenant-a", "prod", "default", "release-1"))
                .thenReturn(Optional.of(state));
        ApiMetadataIndexingStateService service = service();

        long revision = service.request(SCOPE);

        assertThat(revision).isEqualTo(4L);
        assertThat(state.getStatus()).isEqualTo(ApiMetadataIndexingStatus.PENDING);
        assertThat(state.getLegacyIndexedDocumentCount()).isZero();
        assertThat(state.getPublishedDocumentCount()).isZero();
        verify(stateRepository).ensureState("tenant-a", "prod", "default", "release-1");
    }

    @Test
    void shouldRetryFailedGenerationThroughTheSameIdempotentRequestPath() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.FAILED, 7L);
        state.setFailureCode("VECTOR_STORE_UNAVAILABLE");
        state.setFailureMessage("Vector store is unavailable.");
        state.setCompletedAt(Instant.now().minusSeconds(1));
        when(stateRepository.findForUpdate("tenant-a", "prod", "default", "release-1"))
                .thenReturn(Optional.of(state));

        long revision = service().request(SCOPE);

        assertThat(revision).isEqualTo(8L);
        assertThat(state.getStatus()).isEqualTo(ApiMetadataIndexingStatus.PENDING);
        assertThat(state.getFailureCode()).isNull();
        assertThat(state.getFailureMessage()).isNull();
        assertThat(state.getCompletedAt()).isNull();
    }

    @Test
    void shouldClaimOnlyAfterTheCoalescingQuietPeriod() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.PENDING, 4L);
        state.setRequestedAt(Instant.now());
        when(stateRepository.findForUpdate("tenant-a", "prod", "default", "release-1"))
                .thenReturn(Optional.of(state));
        ApiMetadataIndexingStateService service = service();

        assertThat(service.claimIfQuiet(SCOPE, Duration.ofSeconds(10))).isEmpty();

        state.setRequestedAt(Instant.now().minusSeconds(11));
        assertThat(service.claimIfQuiet(SCOPE, Duration.ofSeconds(10))).isPresent();
        assertThat(state.getStatus()).isEqualTo(ApiMetadataIndexingStatus.PROCESSING);
        assertThat(state.getAttempt()).isEqualTo(1);
    }

    @Test
    void shouldRejectLegacyEmbeddingCommitFromSupersededGeneration() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.PROCESSING, 5L);
        when(stateRepository.findForUpdate("tenant-a", "prod", "default", "release-1"))
                .thenReturn(Optional.of(state));

        boolean committed = service().commitLegacyEmbeddings(SCOPE, 4L, Map.of(41L, List.of(0.1f)));

        assertThat(committed).isFalse();
        verify(metadataRepository, never()).findAllById(any());
        verify(metadataRepository, never()).saveAll(any());
    }

    @Test
    void shouldFailCurrentGenerationWhenDerivedCountsDiverge() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.PROCESSING, 5L);
        when(stateRepository.findForUpdate("tenant-a", "prod", "default", "release-1"))
                .thenReturn(Optional.of(state));
        when(metadataRepository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1")).thenReturn(2L);
        when(metadataRepository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndEmbeddingIsNotNull(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);

        assertThat(service().publishAndCompleteIfCurrent(SCOPE, 5L, () -> 2L)).isTrue();

        assertThat(state.getStatus()).isEqualTo(ApiMetadataIndexingStatus.FAILED);
        assertThat(state.getFailureCode()).isEqualTo("INDEXING_COUNT_MISMATCH");
    }

    @Test
    void shouldRejectPublicationFromSupersededGenerationWithoutInvokingPublisher() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.PENDING, 6L);
        when(stateRepository.findForUpdate("tenant-a", "prod", "default", "release-1"))
                .thenReturn(Optional.of(state));
        AtomicBoolean invoked = new AtomicBoolean();

        boolean published = service().publishAndCompleteIfCurrent(SCOPE, 5L, () -> {
            invoked.set(true);
            return 1L;
        });

        assertThat(published).isFalse();
        assertThat(invoked).isFalse();
        verify(metadataRepository, never()).countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                any(), any(), any(), any());
    }

    @Test
    void shouldPublishAndCompleteCurrentGenerationUnderTheStateLease() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.PROCESSING, 5L);
        state.setExpectedDocumentCount(1L);
        state.setLegacyIndexedDocumentCount(1L);
        when(stateRepository.findForUpdate("tenant-a", "prod", "default", "release-1"))
                .thenReturn(Optional.of(state));
        when(metadataRepository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);
        when(metadataRepository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndEmbeddingIsNotNull(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);

        assertThat(service().publishAndCompleteIfCurrent(SCOPE, 5L, () -> 1L)).isTrue();

        assertThat(state.getStatus()).isEqualTo(ApiMetadataIndexingStatus.READY);
        assertThat(state.getPublishedDocumentCount()).isEqualTo(1L);
        assertThat(state.getCompletedAt()).isNotNull();
    }

    @Test
    void shouldRecoverInterruptedProcessingAsPendingAfterRestart() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.PROCESSING, 5L);
        state.setStartedAt(Instant.now().minusSeconds(30));
        when(stateRepository.findAllByStatusIn(any())).thenReturn(List.of(state));

        List<ApiMetadataIndexingScope> recovered = service().recoverInterrupted();

        assertThat(recovered).containsExactly(SCOPE);
        assertThat(state.getStatus()).isEqualTo(ApiMetadataIndexingStatus.PENDING);
        assertThat(state.getStartedAt()).isNull();
        verify(stateRepository).saveAll(List.of(state));
    }

    @Test
    void shouldSanitizePersistedFailureDetails() {
        ApiMetadataIndexingState state = state(ApiMetadataIndexingStatus.PROCESSING, 5L);
        when(stateRepository.findForUpdate("tenant-a", "prod", "default", "release-1"))
                .thenReturn(Optional.of(state));

        assertThat(service().fail(SCOPE, 5L, "EMBEDDING_FAILED", "provider\n secret\t detail")).isTrue();

        assertThat(state.getFailureMessage()).isEqualTo("provider secret detail");
        assertThat(state.getStatus()).isEqualTo(ApiMetadataIndexingStatus.FAILED);
    }

    private ApiMetadataIndexingStateService service() {
        return new ApiMetadataIndexingStateService(stateRepository, metadataRepository);
    }

    private ApiMetadataIndexingState state(ApiMetadataIndexingStatus status, long revision) {
        ApiMetadataIndexingState state = new ApiMetadataIndexingState();
        state.setTenantId(SCOPE.tenantId());
        state.setEnvironment(SCOPE.environment());
        state.setServiceKey(SCOPE.serviceKey());
        state.setReleaseId(SCOPE.releaseId());
        state.setStatus(status);
        state.setRevision(revision);
        state.setRequestedAt(Instant.now().minusSeconds(1));
        state.setUpdatedAt(Instant.now().minusSeconds(1));
        return state;
    }
}
