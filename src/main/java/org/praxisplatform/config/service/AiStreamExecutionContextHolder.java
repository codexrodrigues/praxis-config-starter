package org.praxisplatform.config.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Ponte thread-local usada pelas workers de SSE para expor contexto de execucao e cancelamento
 * cooperativo aos providers.
 */
final class AiStreamExecutionContextHolder {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final Supplier<Boolean> NEVER_CANCELLED = () -> false;
    private static final AbortRegistration NOOP_ABORT_REGISTRATION = () -> {};
    private static final ConcurrentMap<UUID, Runnable> ABORT_ACTIONS_BY_STREAM = new ConcurrentHashMap<>();

    private AiStreamExecutionContextHolder() {
    }

    static <T> T execute(
            UUID streamId,
            UUID threadId,
            UUID turnId,
            Supplier<Boolean> cancellationRequested,
            Supplier<T> action) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(
                streamId,
                threadId,
                turnId,
                cancellationRequested != null ? cancellationRequested : NEVER_CANCELLED));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static Supplier<Boolean> cancellationRequested() {
        Context context = CURRENT.get();
        return context != null ? context.cancellationRequested() : NEVER_CANCELLED;
    }

    static boolean hasActiveContext() {
        return CURRENT.get() != null;
    }

    static AbortRegistration registerAbortAction(Runnable abortAction) {
        if (abortAction == null) {
            return NOOP_ABORT_REGISTRATION;
        }
        Context context = CURRENT.get();
        if (context == null || context.streamId() == null) {
            return NOOP_ABORT_REGISTRATION;
        }
        UUID streamId = context.streamId();
        ABORT_ACTIONS_BY_STREAM.put(streamId, abortAction);
        return () -> ABORT_ACTIONS_BY_STREAM.remove(streamId, abortAction);
    }

    static void abortStream(UUID streamId) {
        if (streamId == null) {
            return;
        }
        Runnable abortAction = ABORT_ACTIONS_BY_STREAM.remove(streamId);
        if (abortAction == null) {
            return;
        }
        try {
            abortAction.run();
        } catch (Exception ignored) {
            // Best-effort abort path.
        }
    }

    @FunctionalInterface
    interface AbortRegistration {
        void close();
    }

    private record Context(
            UUID streamId,
            UUID threadId,
            UUID turnId,
            Supplier<Boolean> cancellationRequested) {
    }
}
