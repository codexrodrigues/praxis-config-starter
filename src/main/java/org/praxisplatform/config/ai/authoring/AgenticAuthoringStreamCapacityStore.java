package org.praxisplatform.config.ai.authoring;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Pluggable store for governing active SSE stream permits across global, tenant, and user quotas.
 *
 * <p>The default implementation is in-memory ({@link InMemoryAgenticAuthoringStreamCapacityStore}),
 * while multi-instance cluster deployments can register a distributed implementation (for example,
 * backed by Redis or PostgreSQL advisory/lease tables) to enforce cluster-wide stream quotas while
 * {@link org.praxisplatform.config.service.AiTurnEventService} handles cross-node SSE event replay.</p>
 */
public interface AgenticAuthoringStreamCapacityStore {

    enum ReservationStatus {
        RESERVED,
        ALREADY_RESERVED,
        GLOBAL_LIMIT_EXCEEDED,
        TENANT_LIMIT_EXCEEDED,
        USER_LIMIT_EXCEEDED,
        EXECUTOR_SATURATED
    }

    /**
     * Atomically reserves a stream capacity permit for the given {@code streamId}, {@code tenantKey},
     * and {@code userKey} if within the configured limits and {@code localExecutorAvailable} is true.
     */
    ReservationStatus tryReserve(
            UUID streamId,
            String tenantKey,
            String userKey,
            int maxActiveGlobal,
            int maxActivePerTenant,
            int maxActivePerUser,
            BooleanSupplier localExecutorAvailable);

    /**
     * Releases any active permit held by {@code streamId}.
     *
     * @return {@code true} if an active permit was removed, {@code false} otherwise
     */
    boolean release(UUID streamId);

    /**
     * Extends the expiration lease of an active permit held by {@code streamId}, if supported by
     * the underlying store.
     *
     * @return {@code true} if the permit is active and its lease was renewed, {@code false} otherwise
     */
    default boolean renewLease(UUID streamId) {
        return streamId != null;
    }

    /**
     * Returns the current number of active reserved stream permits globally.
     */
    int activeGlobalCount();

    /**
     * Returns the current number of active reserved stream permits for the given tenant.
     */
    int activeTenantCount(String tenantKey);

    /**
     * Returns the current number of active reserved stream permits for the given tenant and user.
     */
    int activeUserCount(String tenantKey, String userKey);

    /**
     * Clears all tracked permits (used during shutdown or test reset).
     */
    void clear();
}
