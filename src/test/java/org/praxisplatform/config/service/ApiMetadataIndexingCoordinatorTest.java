package org.praxisplatform.config.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.ApiMetadataIndexingStatus;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class ApiMetadataIndexingCoordinatorTest {

    @Mock private ApiMetadataIndexingStateService stateService;
    @Mock private ObjectProvider<ApiMetadataIngestionService> ingestionServiceProvider;
    @Mock private ApiMetadataIngestionService ingestionService;

    private ApiMetadataIndexingCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    @Test
    void shouldCoalesceRepeatedSchedulesForTheSameRelease() {
        ApiMetadataIndexingScope scope = scope("release-1");
        ApiMetadataIndexingStateService.WorkClaim claim =
                new ApiMetadataIndexingStateService.WorkClaim(scope, 4L, 1L);
        when(ingestionServiceProvider.getObject()).thenReturn(ingestionService);
        when(stateService.snapshot(scope))
                .thenReturn(Optional.of(snapshot(ApiMetadataIndexingStatus.PENDING, 4L)))
                .thenReturn(Optional.of(snapshot(ApiMetadataIndexingStatus.READY, 4L)));
        when(stateService.claimIfQuiet(scope, java.time.Duration.ZERO)).thenReturn(Optional.of(claim));
        coordinator = coordinator(1, 4);

        coordinator.schedule(scope);
        coordinator.schedule(scope);

        verify(ingestionService, timeout(1000).times(1)).processIndexingClaim(claim);
    }

    @Test
    void shouldPersistExplicitFailureWhenBoundedQueueIsSaturated() throws Exception {
        ApiMetadataIndexingScope running = scope("running");
        ApiMetadataIndexingScope queued = scope("queued");
        ApiMetadataIndexingScope rejected = scope("rejected");
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        when(ingestionServiceProvider.getObject()).thenReturn(ingestionService);
        when(stateService.snapshot(any())).thenAnswer(invocation -> Optional.of(
                snapshot(ApiMetadataIndexingStatus.PENDING, 1L)));
        when(stateService.claimIfQuiet(any(), any())).thenAnswer(invocation -> Optional.of(
                new ApiMetadataIndexingStateService.WorkClaim(invocation.getArgument(0), 1L, 1L)));
        org.mockito.Mockito.doAnswer(invocation -> {
            workerStarted.countDown();
            releaseWorker.await(2, TimeUnit.SECONDS);
            return null;
        }).when(ingestionService).processIndexingClaim(any());
        coordinator = coordinator(1, 1);

        coordinator.schedule(running);
        workerStarted.await(1, TimeUnit.SECONDS);
        coordinator.schedule(queued);
        coordinator.schedule(rejected);

        verify(stateService, timeout(1000)).failPending(
                rejected,
                "INDEXING_QUEUE_SATURATED",
                "API metadata indexing queue is saturated; request an idempotent reconcile.");
        releaseWorker.countDown();
    }

    @Test
    void shouldResumePersistedPendingWorkWhenApplicationRestarts() {
        ApiMetadataIndexingScope scope = scope("release-1");
        ApiMetadataIndexingStateService.WorkClaim claim =
                new ApiMetadataIndexingStateService.WorkClaim(scope, 4L, 1L);
        when(stateService.recoverInterrupted()).thenReturn(List.of(scope));
        when(ingestionServiceProvider.getObject()).thenReturn(ingestionService);
        when(stateService.snapshot(scope))
                .thenReturn(Optional.of(snapshot(ApiMetadataIndexingStatus.PENDING, 4L)))
                .thenReturn(Optional.of(snapshot(ApiMetadataIndexingStatus.READY, 4L)));
        when(stateService.claimIfQuiet(scope, java.time.Duration.ZERO)).thenReturn(Optional.of(claim));
        coordinator = coordinator(1, 4);

        coordinator.recoverInterruptedIndexing();

        verify(ingestionService, timeout(1000)).processIndexingClaim(claim);
    }

    @Test
    void shouldRejectNewWorkAfterGracefulShutdownBegins() {
        ApiMetadataIndexingScope scope = scope("release-1");
        coordinator = coordinator(1, 4);

        coordinator.shutdown();
        coordinator.schedule(scope);

        verifyNoInteractions(stateService, ingestionServiceProvider, ingestionService);
    }

    private ApiMetadataIndexingCoordinator coordinator(int workers, int queueCapacity) {
        return new ApiMetadataIndexingCoordinator(
                stateService,
                ingestionServiceProvider,
                workers,
                queueCapacity,
                0L,
                1L);
    }

    private ApiMetadataIndexingScope scope(String release) {
        return new ApiMetadataIndexingScope("tenant-a", "prod", "default", release);
    }

    private ApiMetadataIndexingStateService.StateSnapshot snapshot(
            ApiMetadataIndexingStatus status,
            long revision) {
        Instant now = Instant.now().minusSeconds(1);
        return new ApiMetadataIndexingStateService.StateSnapshot(
                status, revision, 1, 1L, 0L, 0L, null, null, now, now, null, now);
    }
}
