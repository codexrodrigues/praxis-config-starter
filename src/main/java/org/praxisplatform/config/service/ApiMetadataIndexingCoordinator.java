package org.praxisplatform.config.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.ApiMetadataIndexingStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
public class ApiMetadataIndexingCoordinator {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ApiMetadataIndexingStateService stateService;
    private final ObjectProvider<ApiMetadataIngestionService> ingestionServiceProvider;
    private final ThreadPoolExecutor executor;
    private final Duration quietPeriod;
    private final Duration shutdownTimeout;
    private final Set<ApiMetadataIndexingScope> activeScopes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public ApiMetadataIndexingCoordinator(
            ApiMetadataIndexingStateService stateService,
            ObjectProvider<ApiMetadataIngestionService> ingestionServiceProvider,
            @Value("${praxis.api-metadata.indexing.worker-count:1}") int workerCount,
            @Value("${praxis.api-metadata.indexing.queue-capacity:32}") int queueCapacity,
            @Value("${praxis.api-metadata.indexing.coalesce-delay-ms:750}") long coalesceDelayMs,
            @Value("${praxis.api-metadata.indexing.shutdown-timeout-seconds:10}") long shutdownTimeoutSeconds) {
        this.stateService = stateService;
        this.ingestionServiceProvider = ingestionServiceProvider;
        int resolvedWorkers = Math.max(1, workerCount);
        int resolvedQueueCapacity = Math.max(1, queueCapacity);
        this.executor = new ThreadPoolExecutor(
                resolvedWorkers,
                resolvedWorkers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(resolvedQueueCapacity),
                threadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        this.quietPeriod = Duration.ofMillis(Math.max(0L, coalesceDelayMs));
        this.shutdownTimeout = Duration.ofSeconds(Math.max(1L, shutdownTimeoutSeconds));
    }

    public void scheduleAfterCommit(ApiMetadataIndexingScope scope) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(scope);
                }
            });
            return;
        }
        schedule(scope);
    }

    public void schedule(ApiMetadataIndexingScope scope) {
        if (scope == null || shuttingDown.get() || !activeScopes.add(scope)) {
            return;
        }
        try {
            executor.execute(() -> drain(scope));
        } catch (RejectedExecutionException ex) {
            activeScopes.remove(scope);
            stateService.failPending(
                    scope,
                    "INDEXING_QUEUE_SATURATED",
                    "API metadata indexing queue is saturated; request an idempotent reconcile.");
            log.warn(
                    "API metadata indexing queue is saturated for tenant={}, env={}, serviceKey={}, release={}",
                    scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedIndexing() {
        stateService.recoverInterrupted().forEach(this::schedule);
    }

    private void drain(ApiMetadataIndexingScope scope) {
        try {
            while (!shuttingDown.get() && !Thread.currentThread().isInterrupted()) {
                ApiMetadataIndexingStateService.StateSnapshot snapshot = stateService.snapshot(scope).orElse(null);
                if (snapshot == null || snapshot.status() != ApiMetadataIndexingStatus.PENDING) {
                    return;
                }
                awaitQuietPeriod(snapshot.requestedAt());
                if (Thread.currentThread().isInterrupted() || shuttingDown.get()) {
                    return;
                }
                ApiMetadataIndexingStateService.WorkClaim claim =
                        stateService.claimIfQuiet(scope, quietPeriod).orElse(null);
                if (claim == null) {
                    continue;
                }
                ingestionServiceProvider.getObject().processIndexingClaim(claim);
            }
        } catch (RuntimeException ex) {
            ApiMetadataIndexingStateService.StateSnapshot current = stateService.snapshot(scope).orElse(null);
            if (current != null && current.status() == ApiMetadataIndexingStatus.PROCESSING) {
                stateService.fail(
                        scope,
                        current.revision(),
                        "INDEXING_WORKER_FAILED",
                        "API metadata indexing worker failed before completing the release.");
            }
            log.error(
                    "API metadata indexing worker failed for tenant={}, env={}, serviceKey={}, release={}",
                    scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId(), ex);
        } finally {
            activeScopes.remove(scope);
            if (!shuttingDown.get()) {
                stateService.snapshot(scope)
                        .filter(snapshot -> snapshot.status() == ApiMetadataIndexingStatus.PENDING)
                        .ifPresent(ignored -> schedule(scope));
            }
        }
    }

    private void awaitQuietPeriod(Instant requestedAt) {
        if (requestedAt == null || quietPeriod.isZero()) {
            return;
        }
        long remainingMillis = Duration.between(Instant.now(), requestedAt.plus(quietPeriod)).toMillis();
        if (remainingMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(remainingMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toSeconds(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "praxis-api-metadata-indexing-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
