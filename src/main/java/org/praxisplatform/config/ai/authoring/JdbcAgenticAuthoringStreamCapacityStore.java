package org.praxisplatform.config.ai.authoring;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC-backed implementation of {@link AgenticAuthoringStreamCapacityStore} for multi-instance
 * cluster deployments sharing a relational database (such as PostgreSQL).
 *
 * <p>Uses lease-based rows with automatic TTL expiration ({@code expires_at}) so that if a node
 * terminates abruptly while holding active SSE stream permits, its leases expire automatically
 * without permanently leaking cluster capacity.</p>
 */
public class JdbcAgenticAuthoringStreamCapacityStore implements AgenticAuthoringStreamCapacityStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgenticAuthoringStreamCapacityStore.class);

    static final String DEFAULT_TABLE_NAME = "ai_authoring_stream_capacity_lease";
    public static final long DEFAULT_LEASE_TTL_SECONDS = 300L;
    private static final Duration PRUNE_MIN_INTERVAL = Duration.ofSeconds(30);

    private final NamedParameterJdbcTemplate jdbc;
    private final Duration leaseTtl;
    private final Clock clock;
    private final InMemoryAgenticAuthoringStreamCapacityStore fallbackStore;
    private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);
    private final Object reservationLock = new Object();
    private volatile Instant lastPrunedAt;

    public JdbcAgenticAuthoringStreamCapacityStore(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Duration.ofSeconds(DEFAULT_LEASE_TTL_SECONDS), Clock.systemUTC());
    }

    public JdbcAgenticAuthoringStreamCapacityStore(
            NamedParameterJdbcTemplate jdbc,
            Duration leaseTtl,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.leaseTtl = leaseTtl != null && !leaseTtl.isNegative() && !leaseTtl.isZero()
                ? leaseTtl
                : Duration.ofSeconds(DEFAULT_LEASE_TTL_SECONDS);
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.fallbackStore = new InMemoryAgenticAuthoringStreamCapacityStore();
    }

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
        String streamIdText = streamId.toString();
        try {
            ensureSchemaInitialized();
            synchronized (reservationLock) {
                Instant now = clock.instant();
                pruneExpiredLeases(now);

                if (existsActiveLease(streamIdText, now)) {
                    return ReservationStatus.ALREADY_RESERVED;
                }
                if (maxActiveGlobal > 0 && countActiveGlobal(now) >= maxActiveGlobal) {
                    return ReservationStatus.GLOBAL_LIMIT_EXCEEDED;
                }
                if (maxActivePerTenant > 0 && countActiveByTenant(normalizedTenant, now) >= maxActivePerTenant) {
                    return ReservationStatus.TENANT_LIMIT_EXCEEDED;
                }
                if (maxActivePerUser > 0 && countActiveByUser(normalizedTenant, normalizedUser, now) >= maxActivePerUser) {
                    return ReservationStatus.USER_LIMIT_EXCEEDED;
                }
                if (localExecutorAvailable != null && !localExecutorAvailable.getAsBoolean()) {
                    return ReservationStatus.EXECUTOR_SATURATED;
                }

                Instant expiresAt = now.plus(leaseTtl);
                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("streamId", streamIdText)
                        .addValue("tenantKey", normalizedTenant)
                        .addValue("userKey", normalizedUser)
                        .addValue("reservedAt", Timestamp.from(now))
                        .addValue("expiresAt", Timestamp.from(expiresAt));
                jdbc.update(
                        "INSERT INTO " + DEFAULT_TABLE_NAME
                                + " (stream_id, tenant_key, user_key, reserved_at, expires_at)"
                                + " VALUES (:streamId, :tenantKey, :userKey, :reservedAt, :expiresAt)",
                        params);
                ReservationStatus postInsertCapViolation = verifyPostInsertLimits(
                        streamIdText,
                        normalizedTenant,
                        normalizedUser,
                        maxActiveGlobal,
                        maxActivePerTenant,
                        maxActivePerUser,
                        now);
                if (postInsertCapViolation != null) {
                    return postInsertCapViolation;
                }
                return ReservationStatus.RESERVED;
            }
        } catch (DuplicateKeyException ex) {
            return ReservationStatus.ALREADY_RESERVED;
        } catch (DataAccessException ex) {
            log.debug("Failed to reserve SSE stream permit in JDBC store for streamId={}: {}",
                    streamId, ex.getMessage());
            return fallbackStore.tryReserve(
                    streamId,
                    normalizedTenant,
                    normalizedUser,
                    maxActiveGlobal,
                    maxActivePerTenant,
                    maxActivePerUser,
                    localExecutorAvailable);
        }
    }

    @Override
    public boolean release(UUID streamId) {
        if (streamId == null) {
            return false;
        }
        boolean releasedInFallback = fallbackStore.release(streamId);
        try {
            ensureSchemaInitialized();
            synchronized (reservationLock) {
                int deleted = jdbc.update(
                        "DELETE FROM " + DEFAULT_TABLE_NAME + " WHERE stream_id = :streamId",
                        new MapSqlParameterSource("streamId", streamId.toString()));
                return deleted > 0 || releasedInFallback;
            }
        } catch (DataAccessException ex) {
            log.debug("Failed to release SSE stream permit in JDBC store for streamId={}: {}",
                    streamId, ex.getMessage());
            return releasedInFallback;
        }
    }

    @Override
    public boolean renewLease(UUID streamId) {
        if (streamId == null) {
            return false;
        }
        boolean renewedInFallback = fallbackStore.renewLease(streamId);
        try {
            ensureSchemaInitialized();
            synchronized (reservationLock) {
                Instant now = clock.instant();
                Instant expiresAt = now.plus(leaseTtl);
                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("streamId", streamId.toString())
                        .addValue("now", Timestamp.from(now))
                        .addValue("expiresAt", Timestamp.from(expiresAt));
                int updated = jdbc.update(
                        "UPDATE " + DEFAULT_TABLE_NAME
                                + " SET expires_at = :expiresAt"
                                + " WHERE stream_id = :streamId AND expires_at > :now",
                        params);
                return updated > 0 || renewedInFallback;
            }
        } catch (DataAccessException ex) {
            log.debug("Failed to renew SSE stream lease in JDBC store for streamId={}: {}",
                    streamId, ex.getMessage());
            return renewedInFallback;
        }
    }

    @Override
    public int activeGlobalCount() {
        try {
            ensureSchemaInitialized();
            return countActiveGlobal(clock.instant());
        } catch (DataAccessException ex) {
            return fallbackStore.activeGlobalCount();
        }
    }

    @Override
    public int activeTenantCount(String tenantKey) {
        if (tenantKey == null || tenantKey.isBlank()) {
            return 0;
        }
        try {
            ensureSchemaInitialized();
            return countActiveByTenant(tenantKey.trim(), clock.instant());
        } catch (DataAccessException ex) {
            return fallbackStore.activeTenantCount(tenantKey);
        }
    }

    @Override
    public int activeUserCount(String tenantKey, String userKey) {
        if (tenantKey == null || tenantKey.isBlank() || userKey == null || userKey.isBlank()) {
            return 0;
        }
        try {
            ensureSchemaInitialized();
            return countActiveByUser(tenantKey.trim(), userKey.trim(), clock.instant());
        } catch (DataAccessException ex) {
            return fallbackStore.activeUserCount(tenantKey, userKey);
        }
    }

    @Override
    public void clear() {
        fallbackStore.clear();
        try {
            ensureSchemaInitialized();
            synchronized (reservationLock) {
                lastPrunedAt = null;
                jdbc.update("DELETE FROM " + DEFAULT_TABLE_NAME, new MapSqlParameterSource());
            }
        } catch (DataAccessException ex) {
            log.debug("Failed to clear SSE stream capacity leases in JDBC store: {}", ex.getMessage());
        }
    }

    private void pruneExpiredLeases(Instant now) {
        Instant previous = lastPrunedAt;
        if (previous != null
                && !now.isBefore(previous)
                && Duration.between(previous, now).compareTo(PRUNE_MIN_INTERVAL) < 0) {
            return;
        }
        jdbc.update(
                "DELETE FROM " + DEFAULT_TABLE_NAME + " WHERE expires_at <= :now",
                new MapSqlParameterSource("now", Timestamp.from(now)));
        lastPrunedAt = now;
    }

    private ReservationStatus verifyPostInsertLimits(
            String streamIdText,
            String tenantKey,
            String userKey,
            int maxActiveGlobal,
            int maxActivePerTenant,
            int maxActivePerUser,
            Instant now) {
        ReservationStatus exceeded = null;
        if (maxActivePerUser > 0 && countActiveByUser(tenantKey, userKey, now) > maxActivePerUser) {
            exceeded = ReservationStatus.USER_LIMIT_EXCEEDED;
        } else if (maxActivePerTenant > 0 && countActiveByTenant(tenantKey, now) > maxActivePerTenant) {
            exceeded = ReservationStatus.TENANT_LIMIT_EXCEEDED;
        } else if (maxActiveGlobal > 0 && countActiveGlobal(now) > maxActiveGlobal) {
            exceeded = ReservationStatus.GLOBAL_LIMIT_EXCEEDED;
        }
        if (exceeded != null) {
            jdbc.update(
                    "DELETE FROM " + DEFAULT_TABLE_NAME + " WHERE stream_id = :streamId",
                    new MapSqlParameterSource("streamId", streamIdText));
        }
        return exceeded;
    }

    private boolean existsActiveLease(String streamIdText, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("streamId", streamIdText)
                .addValue("now", Timestamp.from(now));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + DEFAULT_TABLE_NAME
                        + " WHERE stream_id = :streamId AND expires_at > :now",
                params,
                Integer.class);
        return count != null && count > 0;
    }

    private int countActiveGlobal(Instant now) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + DEFAULT_TABLE_NAME + " WHERE expires_at > :now",
                new MapSqlParameterSource("now", Timestamp.from(now)),
                Integer.class);
        return count != null ? Math.max(0, count) : 0;
    }

    private int countActiveByTenant(String tenantKey, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantKey", tenantKey)
                .addValue("now", Timestamp.from(now));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + DEFAULT_TABLE_NAME
                        + " WHERE tenant_key = :tenantKey AND expires_at > :now",
                params,
                Integer.class);
        return count != null ? Math.max(0, count) : 0;
    }

    private int countActiveByUser(String tenantKey, String userKey, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantKey", tenantKey)
                .addValue("userKey", userKey)
                .addValue("now", Timestamp.from(now));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + DEFAULT_TABLE_NAME
                        + " WHERE tenant_key = :tenantKey AND user_key = :userKey AND expires_at > :now",
                params,
                Integer.class);
        return count != null ? Math.max(0, count) : 0;
    }

    private void ensureSchemaInitialized() {
        if (schemaInitialized.get()) {
            return;
        }
        synchronized (reservationLock) {
            if (schemaInitialized.get()) {
                return;
            }
            try {
                if (jdbc.getJdbcTemplate() != null) {
                    jdbc.getJdbcTemplate().execute(
                            "CREATE TABLE IF NOT EXISTS " + DEFAULT_TABLE_NAME + " ("
                                    + "stream_id VARCHAR(64) PRIMARY KEY, "
                                    + "tenant_key VARCHAR(255) NOT NULL, "
                                    + "user_key VARCHAR(255) NOT NULL, "
                                    + "reserved_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                                    + "expires_at TIMESTAMP WITH TIME ZONE NOT NULL"
                                    + ")");
                }
            } catch (DataAccessException ex) {
                log.debug("Skipping runtime DDL for {} (assuming provisioned via Flyway V71): {}",
                        DEFAULT_TABLE_NAME, ex.getMessage());
            }
            schemaInitialized.set(true);
        }
    }
}
