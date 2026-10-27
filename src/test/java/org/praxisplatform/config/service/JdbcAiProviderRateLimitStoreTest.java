package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcAiProviderRateLimitStoreTest {

    private NamedParameterJdbcTemplate sharedJdbc;
    private AtomicReference<Instant> currentInstant;
    private Clock mutableClock;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:rate_limit_cluster_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
    void cooldownRecordedOnNodeAIsImmediatelyVisibleOnNodeBAndPreservesLongerRetryAfter() {
        JdbcAiProviderRateLimitStore nodeA = new JdbcAiProviderRateLimitStore(sharedJdbc, mutableClock);
        JdbcAiProviderRateLimitStore nodeB = new JdbcAiProviderRateLimitStore(sharedJdbc, mutableClock);

        Instant now = currentInstant.get();
        Instant firstRetryAfter = now.plusSeconds(15);
        nodeA.recordCooldown("openai", "gpt-4o-mini", firstRetryAfter, "HTTP 429 on nodeA");

        assertTrue(nodeB.isInCooldown("openai", "gpt-4o-mini", now));
        Optional<AiProviderRateLimitStore.CooldownEntry> activeOnNodeB =
                nodeB.findActiveCooldown("openai", "gpt-4o-mini", now);
        assertTrue(activeOnNodeB.isPresent());
        assertEquals(firstRetryAfter, activeOnNodeB.get().retryAfter());
        assertEquals("HTTP 429 on nodeA", activeOnNodeB.get().reason());

        // Shorter retry-after on nodeB must not shorten the existing window
        nodeB.recordCooldown("openai", "gpt-4o-mini", now.plusSeconds(5), "Shorter retry-after");
        assertEquals(firstRetryAfter, nodeA.findActiveCooldown("openai", "gpt-4o-mini", now).orElseThrow().retryAfter());

        // Longer retry-after on nodeB extends the window across the cluster
        Instant extendedRetryAfter = now.plusSeconds(45);
        nodeB.recordCooldown("openai", "gpt-4o-mini", extendedRetryAfter, "Extended HTTP 429 on nodeB");
        assertEquals(extendedRetryAfter, nodeA.findActiveCooldown("openai", "gpt-4o-mini", now).orElseThrow().retryAfter());

        // After clock advances past extendedRetryAfter, cooldown is no longer active
        currentInstant.set(extendedRetryAfter.plusSeconds(1));
        assertFalse(nodeA.isInCooldown("openai", "gpt-4o-mini", currentInstant.get()));
        assertFalse(nodeB.isInCooldown("openai", "gpt-4o-mini", currentInstant.get()));
    }

    @Test
    void clearingSpecificModelOnNodeBClearsProviderWildcardAcrossCluster() {
        JdbcAiProviderRateLimitStore nodeA = new JdbcAiProviderRateLimitStore(sharedJdbc, mutableClock);
        JdbcAiProviderRateLimitStore nodeB = new JdbcAiProviderRateLimitStore(sharedJdbc, mutableClock);

        Instant now = currentInstant.get();
        nodeA.recordCooldown("gemini", null, now.plusSeconds(30), "Provider-wide 429");
        nodeA.recordCooldown("gemini", "gemini-2.5-flash", now.plusSeconds(30), "Model 429");

        assertTrue(nodeB.isInCooldown("gemini", "gemini-2.5-flash", now));
        assertTrue(nodeB.isInCooldown("gemini", "gemini-2.5-pro", now));

        nodeB.clearCooldown("gemini", "gemini-2.5-flash");

        assertFalse(nodeA.isInCooldown("gemini", "gemini-2.5-flash", now));
        assertFalse(nodeA.isInCooldown("gemini", null, now));
    }

    @Test
    void duplicateKeyExceptionDuringInsertFallsBackToConditionalUpdateWhenRetryAfterIsLonger() {
        Instant now = currentInstant.get();
        Instant initialRetryAfter = now.plusSeconds(10);
        Instant longerRetryAfter = now.plusSeconds(45);

        // Pre-populate the row in the real H2 database
        JdbcAiProviderRateLimitStore realStore = new JdbcAiProviderRateLimitStore(sharedJdbc, mutableClock);
        realStore.recordCooldown("openai", "gpt-4o-mini", initialRetryAfter, "Initial 429");

        // Wrap sharedJdbc so the pre-insert SELECT returns empty (simulating a cross-node race where the row did not exist
        // at SELECT time, but was inserted by a sibling node right before our INSERT)
        java.util.concurrent.atomic.AtomicInteger preInsertSelectCount = new java.util.concurrent.atomic.AtomicInteger();
        NamedParameterJdbcTemplate racingJdbc = new NamedParameterJdbcTemplate(sharedJdbc.getJdbcOperations()) {
            @Override
            public <T> java.util.List<T> query(
                    String sql,
                    org.springframework.jdbc.core.namedparam.SqlParameterSource paramSource,
                    org.springframework.jdbc.core.RowMapper<T> rowMapper) {
                if (sql.contains("WHERE cooldown_key = :cooldownKey")
                        && !sql.contains("retry_after > :now")) {
                    preInsertSelectCount.incrementAndGet();
                    return java.util.List.of();
                }
                return super.query(sql, paramSource, rowMapper);
            }
        };

        JdbcAiProviderRateLimitStore racingNode = new JdbcAiProviderRateLimitStore(racingJdbc, mutableClock);
        racingNode.recordCooldown("openai", "gpt-4o-mini", longerRetryAfter, "Concurrent 429 with longer retry");
        assertEquals(1, preInsertSelectCount.get());

        Optional<AiProviderRateLimitStore.CooldownEntry> updated =
                realStore.findActiveCooldown("openai", "gpt-4o-mini", now);
        assertTrue(updated.isPresent());
        assertEquals(longerRetryAfter, updated.get().retryAfter());
        assertEquals("Concurrent 429 with longer retry", updated.get().reason());
    }

    @Test
    void throttlesExpiredCooldownPruningWithinThirtySecondWindow() {
        java.util.concurrent.atomic.AtomicInteger pruneDeleteCount = new java.util.concurrent.atomic.AtomicInteger();
        NamedParameterJdbcTemplate countingJdbc = new NamedParameterJdbcTemplate(sharedJdbc.getJdbcOperations()) {
            @Override
            public int update(String sql, org.springframework.jdbc.core.namedparam.SqlParameterSource paramSource) {
                if (sql.contains("DELETE FROM ai_provider_rate_limit_cooldown WHERE retry_after <= :now")) {
                    pruneDeleteCount.incrementAndGet();
                }
                return super.update(sql, paramSource);
            }
        };

        JdbcAiProviderRateLimitStore store = new JdbcAiProviderRateLimitStore(countingJdbc, mutableClock);
        Instant t0 = currentInstant.get();
        store.recordCooldown("openai", "gpt-4o-mini", t0.plusSeconds(15), "First 429");
        assertEquals(1, pruneDeleteCount.get());

        // 10s later (< 30s PRUNE_MIN_INTERVAL): prune is throttled
        currentInstant.set(t0.plusSeconds(10));
        store.recordCooldown("gemini", "gemini-2.5-flash", t0.plusSeconds(25), "Second 429");
        assertEquals(1, pruneDeleteCount.get());

        // 31s after t0 (>= 30s PRUNE_MIN_INTERVAL): prune executes again
        currentInstant.set(t0.plusSeconds(31));
        store.recordCooldown("xai", "grok-3", t0.plusSeconds(60), "Third 429");
        assertEquals(2, pruneDeleteCount.get());
    }
}
