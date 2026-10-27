package org.praxisplatform.config.ai.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringStreamCapacityStore.ReservationStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcAgenticAuthoringStreamCapacityStoreTest {

    private NamedParameterJdbcTemplate sharedJdbc;
    private AtomicReference<Instant> currentInstant;
    private Clock mutableClock;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:stream_capacity_cluster_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        dataSource.setDriverClassName("org.h2.Driver");
        sharedJdbc = new NamedParameterJdbcTemplate(dataSource);
        currentInstant = new AtomicReference<>(Instant.parse("2026-10-25T18:00:00Z"));
        mutableClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return currentInstant.get();
            }
        };
    }

    @Test
    void enforcesClusterWideQuotasAcrossNodesAndReleasesCleanly() {
        JdbcAgenticAuthoringStreamCapacityStore nodeA = new JdbcAgenticAuthoringStreamCapacityStore(
                sharedJdbc, Duration.ofSeconds(60), mutableClock);
        JdbcAgenticAuthoringStreamCapacityStore nodeB = new JdbcAgenticAuthoringStreamCapacityStore(
                sharedJdbc, Duration.ofSeconds(60), mutableClock);

        UUID stream1 = UUID.randomUUID();
        UUID stream2 = UUID.randomUUID();
        UUID stream3 = UUID.randomUUID();

        assertEquals(
                ReservationStatus.RESERVED,
                nodeA.tryReserve(stream1, "tenant-a", "user-1", 2, 2, 1, () -> true));
        assertEquals(
                ReservationStatus.ALREADY_RESERVED,
                nodeB.tryReserve(stream1, "tenant-a", "user-1", 2, 2, 1, () -> true));
        assertEquals(
                ReservationStatus.USER_LIMIT_EXCEEDED,
                nodeB.tryReserve(stream2, "tenant-a", "user-1", 2, 2, 1, () -> true));
        assertEquals(
                ReservationStatus.RESERVED,
                nodeB.tryReserve(stream2, "tenant-a", "user-2", 2, 2, 1, () -> true));
        assertEquals(
                ReservationStatus.GLOBAL_LIMIT_EXCEEDED,
                nodeA.tryReserve(stream3, "tenant-b", "user-3", 2, 2, 1, () -> true));

        assertEquals(2, nodeA.activeGlobalCount());
        assertEquals(2, nodeB.activeTenantCount("tenant-a"));
        assertEquals(1, nodeB.activeUserCount("tenant-a", "user-1"));

        assertTrue(nodeA.release(stream1));
        assertFalse(nodeB.release(stream1));
        assertEquals(1, nodeB.activeGlobalCount());
    }

    @Test
    void expiredNodeLeasesAreAutomaticallyPrunedWhenNodeCrashesWithoutReleasing() {
        JdbcAgenticAuthoringStreamCapacityStore nodeA = new JdbcAgenticAuthoringStreamCapacityStore(
                sharedJdbc, Duration.ofSeconds(30), mutableClock);
        JdbcAgenticAuthoringStreamCapacityStore nodeB = new JdbcAgenticAuthoringStreamCapacityStore(
                sharedJdbc, Duration.ofSeconds(30), mutableClock);

        UUID crashedStream = UUID.randomUUID();
        UUID newStream = UUID.randomUUID();

        assertEquals(
                ReservationStatus.RESERVED,
                nodeA.tryReserve(crashedStream, "tenant-a", "user-1", 1, 1, 1, () -> true));
        assertEquals(
                ReservationStatus.GLOBAL_LIMIT_EXCEEDED,
                nodeB.tryReserve(newStream, "tenant-a", "user-1", 1, 1, 1, () -> true));

        // Simulate nodeA crash and clock advancing past the 30s lease TTL
        currentInstant.set(currentInstant.get().plusSeconds(31));

        assertEquals(0, nodeB.activeGlobalCount());
        assertEquals(
                ReservationStatus.RESERVED,
                nodeB.tryReserve(newStream, "tenant-a", "user-1", 1, 1, 1, () -> true));
        assertEquals(1, nodeB.activeGlobalCount());
    }

    @Test
    void activeStreamLeaseRenewalExtendsExpirationAcrossClockAdvances() {
        JdbcAgenticAuthoringStreamCapacityStore nodeA = new JdbcAgenticAuthoringStreamCapacityStore(
                sharedJdbc, Duration.ofSeconds(30), mutableClock);
        JdbcAgenticAuthoringStreamCapacityStore nodeB = new JdbcAgenticAuthoringStreamCapacityStore(
                sharedJdbc, Duration.ofSeconds(30), mutableClock);

        UUID activeStream = UUID.randomUUID();
        UUID competingStream = UUID.randomUUID();

        assertEquals(
                ReservationStatus.RESERVED,
                nodeA.tryReserve(activeStream, "tenant-a", "user-1", 1, 1, 1, () -> true));

        // Advance 20s and renew lease (new expiration = T+50s)
        currentInstant.set(currentInstant.get().plusSeconds(20));
        assertTrue(nodeA.renewLease(activeStream));

        // Advance another 20s (T+40s, past original T+30s TTL, but before renewed T+50s)
        currentInstant.set(currentInstant.get().plusSeconds(20));
        assertEquals(1, nodeB.activeGlobalCount());
        assertEquals(
                ReservationStatus.GLOBAL_LIMIT_EXCEEDED,
                nodeB.tryReserve(competingStream, "tenant-a", "user-1", 1, 1, 1, () -> true));

        // Advance past T+50s without further renewal -> lease expires
        currentInstant.set(currentInstant.get().plusSeconds(15));
        assertFalse(nodeA.renewLease(activeStream));
        assertEquals(0, nodeB.activeGlobalCount());
    }

    @Test
    void rollsBackReservationWhenConcurrentInsertInSharedDatabasePushesCountAboveLimit() {
        JdbcAgenticAuthoringStreamCapacityStore realNode = new JdbcAgenticAuthoringStreamCapacityStore(
                sharedJdbc, Duration.ofSeconds(60), mutableClock);
        UUID preExistingStream = UUID.randomUUID();
        UUID racingStream = UUID.randomUUID();

        // Pre-insert one active stream in the real H2 database
        assertEquals(
                ReservationStatus.RESERVED,
                realNode.tryReserve(preExistingStream, "tenant-a", "user-1", 5, 5, 1, () -> true));

        // Simulate a cross-node race where the pre-insert SELECT COUNT(*) saw 0, so INSERT proceeds,
        // and then the post-insert verification sees the true count (2 > 1) and rolls back racingStream
        java.util.concurrent.atomic.AtomicInteger userCountQueries = new java.util.concurrent.atomic.AtomicInteger();
        NamedParameterJdbcTemplate racingJdbc = new NamedParameterJdbcTemplate(sharedJdbc.getJdbcOperations()) {
            @Override
            public <T> T queryForObject(
                    String sql,
                    org.springframework.jdbc.core.namedparam.SqlParameterSource paramSource,
                    Class<T> requiredType) {
                if (sql.contains("user_key = :userKey") && userCountQueries.incrementAndGet() == 1) {
                    return requiredType.cast(0);
                }
                return super.queryForObject(sql, paramSource, requiredType);
            }
        };

        JdbcAgenticAuthoringStreamCapacityStore racingNode = new JdbcAgenticAuthoringStreamCapacityStore(
                racingJdbc, Duration.ofSeconds(60), mutableClock);
        assertEquals(
                ReservationStatus.USER_LIMIT_EXCEEDED,
                racingNode.tryReserve(racingStream, "tenant-a", "user-1", 5, 5, 1, () -> true));

        // Verify that racingStream was rolled back and only preExistingStream remains
        assertEquals(1, realNode.activeGlobalCount());
        assertFalse(realNode.release(racingStream));
        assertTrue(realNode.release(preExistingStream));
    }

    @Test
    void throttlesExpiredLeasePruningAndKeepsActiveCountQueriesReadOnly() {
        java.util.concurrent.atomic.AtomicInteger pruneDeleteCount = new java.util.concurrent.atomic.AtomicInteger();
        NamedParameterJdbcTemplate countingJdbc = new NamedParameterJdbcTemplate(sharedJdbc.getJdbcOperations()) {
            @Override
            public int update(String sql, org.springframework.jdbc.core.namedparam.SqlParameterSource paramSource) {
                if (sql.contains("DELETE FROM ai_authoring_stream_capacity_lease WHERE expires_at <= :now")) {
                    pruneDeleteCount.incrementAndGet();
                }
                return super.update(sql, paramSource);
            }
        };

        JdbcAgenticAuthoringStreamCapacityStore store = new JdbcAgenticAuthoringStreamCapacityStore(
                countingJdbc, Duration.ofSeconds(60), mutableClock);
        Instant t0 = currentInstant.get();

        // Read-only count scrapes must not trigger DELETE prune statements
        assertEquals(0, store.activeGlobalCount());
        assertEquals(0, store.activeTenantCount("tenant-a"));
        assertEquals(0, store.activeUserCount("tenant-a", "user-1"));
        assertEquals(0, pruneDeleteCount.get());

        // First tryReserve triggers prune #1
        assertEquals(
                ReservationStatus.RESERVED,
                store.tryReserve(UUID.randomUUID(), "tenant-a", "user-1", 5, 5, 5, () -> true));
        assertEquals(1, pruneDeleteCount.get());

        // Second tryReserve 10s later (< 30s PRUNE_MIN_INTERVAL) is throttled
        currentInstant.set(t0.plusSeconds(10));
        assertEquals(
                ReservationStatus.RESERVED,
                store.tryReserve(UUID.randomUUID(), "tenant-a", "user-1", 5, 5, 5, () -> true));
        assertEquals(1, pruneDeleteCount.get());

        // Third tryReserve 31s after t0 (>= 30s PRUNE_MIN_INTERVAL) triggers prune #2
        currentInstant.set(t0.plusSeconds(31));
        assertEquals(
                ReservationStatus.RESERVED,
                store.tryReserve(UUID.randomUUID(), "tenant-a", "user-1", 5, 5, 5, () -> true));
        assertEquals(2, pruneDeleteCount.get());
    }
}
