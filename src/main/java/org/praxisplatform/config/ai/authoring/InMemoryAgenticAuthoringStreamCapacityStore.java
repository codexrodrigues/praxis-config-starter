package org.praxisplatform.config.ai.authoring;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
/**
 * Default thread-safe in-memory implementation of {@link AgenticAuthoringStreamCapacityStore}.
 */
public class InMemoryAgenticAuthoringStreamCapacityStore implements AgenticAuthoringStreamCapacityStore {

    private final Map<UUID, CapacityOwner> capacityOwnersByStream = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tenantActiveCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tenantUserActiveCounts = new ConcurrentHashMap<>();
    private final Object capacityLock = new Object();

    @Override
    public ReservationStatus tryReserve(
            UUID streamId,
            String tenantKey,
            String userKey,
            int maxActiveGlobal,
            int maxActivePerTenant,
            int maxActivePerUser,
            BooleanSupplier localExecutorAvailable) {
        if (streamId == null || tenantKey == null || userKey == null) {
            throw new IllegalArgumentException("streamId, tenantKey, and userKey are required.");
        }
        String normalizedTenant = tenantKey.trim();
        String normalizedUser = userKey.trim();
        String tenantUserKey = tenantUserKey(normalizedTenant, normalizedUser);
        synchronized (capacityLock) {
            if (capacityOwnersByStream.containsKey(streamId)) {
                return ReservationStatus.ALREADY_RESERVED;
            }
            if (maxActiveGlobal > 0 && capacityOwnersByStream.size() >= maxActiveGlobal) {
                return ReservationStatus.GLOBAL_LIMIT_EXCEEDED;
            }
            if (maxActivePerTenant > 0 && currentCount(tenantActiveCounts, normalizedTenant) >= maxActivePerTenant) {
                return ReservationStatus.TENANT_LIMIT_EXCEEDED;
            }
            if (maxActivePerUser > 0 && currentCount(tenantUserActiveCounts, tenantUserKey) >= maxActivePerUser) {
                return ReservationStatus.USER_LIMIT_EXCEEDED;
            }
            if (localExecutorAvailable != null && !localExecutorAvailable.getAsBoolean()) {
                return ReservationStatus.EXECUTOR_SATURATED;
            }
            capacityOwnersByStream.put(streamId, new CapacityOwner(normalizedTenant, tenantUserKey));
            incrementCounter(tenantActiveCounts, normalizedTenant);
            incrementCounter(tenantUserActiveCounts, tenantUserKey);
            return ReservationStatus.RESERVED;
        }
    }

    @Override
    public boolean release(UUID streamId) {
        if (streamId == null) {
            return false;
        }
        synchronized (capacityLock) {
            CapacityOwner owner = capacityOwnersByStream.remove(streamId);
            if (owner == null) {
                return false;
            }
            decrementCounter(tenantActiveCounts, owner.tenantKey());
            decrementCounter(tenantUserActiveCounts, owner.tenantUserKey());
            return true;
        }
    }

    @Override
    public boolean renewLease(UUID streamId) {
        if (streamId == null) {
            return false;
        }
        synchronized (capacityLock) {
            return capacityOwnersByStream.containsKey(streamId);
        }
    }

    @Override
    public int activeGlobalCount() {
        synchronized (capacityLock) {
            return capacityOwnersByStream.size();
        }
    }

    @Override
    public int activeTenantCount(String tenantKey) {
        if (tenantKey == null || tenantKey.isBlank()) {
            return 0;
        }
        synchronized (capacityLock) {
            return currentCount(tenantActiveCounts, tenantKey.trim());
        }
    }

    @Override
    public int activeUserCount(String tenantKey, String userKey) {
        if (tenantKey == null || tenantKey.isBlank() || userKey == null || userKey.isBlank()) {
            return 0;
        }
        synchronized (capacityLock) {
            return currentCount(tenantUserActiveCounts, tenantUserKey(tenantKey.trim(), userKey.trim()));
        }
    }

    @Override
    public void clear() {
        synchronized (capacityLock) {
            capacityOwnersByStream.clear();
            tenantActiveCounts.clear();
            tenantUserActiveCounts.clear();
        }
    }

    private int currentCount(Map<String, AtomicInteger> counters, String key) {
        if (counters == null || key == null) {
            return 0;
        }
        AtomicInteger value = counters.get(key);
        return value != null ? Math.max(0, value.get()) : 0;
    }

    private void incrementCounter(Map<String, AtomicInteger> counters, String key) {
        if (counters == null || key == null) {
            return;
        }
        counters.computeIfAbsent(key, ignored -> new AtomicInteger(0)).incrementAndGet();
    }

    private void decrementCounter(Map<String, AtomicInteger> counters, String key) {
        if (counters == null || key == null) {
            return;
        }
        counters.computeIfPresent(key, (ignored, counter) -> {
            int remaining = counter.decrementAndGet();
            return remaining <= 0 ? null : counter;
        });
    }

    private String tenantUserKey(String tenantKey, String userKey) {
        return tenantKey + "|" + userKey;
    }

    private record CapacityOwner(String tenantKey, String tenantUserKey) {
    }
}
