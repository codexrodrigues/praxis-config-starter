package org.praxisplatform.config.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC-backed implementation of {@link AiProviderRateLimitStore} for multi-instance cluster
 * deployments sharing a relational database (such as PostgreSQL).
 *
 * <p>When an HTTP 429 / {@code Retry-After} cooldown is recorded on one node, sibling nodes
 * querying {@link #findActiveCooldown(String, String, Instant)} immediately observe the active
 * cooldown window and prioritize healthy fallback providers.</p>
 *
 * <p>Includes a fail-safe local {@link InMemoryAiProviderRateLimitStore} mirror so transient
 * database connectivity issues never break AI turn routing.</p>
 */
public class JdbcAiProviderRateLimitStore implements AiProviderRateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAiProviderRateLimitStore.class);

    static final String DEFAULT_TABLE_NAME = "ai_provider_rate_limit_cooldown";
    private static final java.time.Duration PRUNE_MIN_INTERVAL = java.time.Duration.ofSeconds(30);

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final InMemoryAiProviderRateLimitStore fallbackMirror;
    private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private volatile Instant lastPrunedAt;

    public JdbcAiProviderRateLimitStore(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public JdbcAiProviderRateLimitStore(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.fallbackMirror = new InMemoryAiProviderRateLimitStore();
    }

    @Override
    public void recordCooldown(String provider, String model, Instant retryAfter, String reason) {
        String normalizedProvider = normalize(provider);
        if (normalizedProvider == null || retryAfter == null) {
            return;
        }
        String normalizedModel = normalize(model);
        Instant now = clock.instant();
        if (!retryAfter.isAfter(now)) {
            return;
        }
        fallbackMirror.recordCooldown(normalizedProvider, normalizedModel, retryAfter, reason);
        String cooldownKey = key(normalizedProvider, normalizedModel);
        try {
            ensureSchemaInitialized();
            synchronized (writeLock) {
                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("cooldownKey", cooldownKey)
                        .addValue("provider", normalizedProvider)
                        .addValue("model", normalizedModel)
                        .addValue("retryAfter", Timestamp.from(retryAfter))
                        .addValue("reason", truncate(reason, 512))
                        .addValue("recordedAt", Timestamp.from(now))
                        .addValue("now", Timestamp.from(now));

                pruneExpiredCooldownsIfNeeded(now, params);

                List<CooldownEntry> existing = jdbc.query(
                        "SELECT provider, model, retry_after, reason, recorded_at FROM " + DEFAULT_TABLE_NAME
                                + " WHERE cooldown_key = :cooldownKey",
                        params,
                        this::mapRow);

                if (existing.isEmpty()) {
                    try {
                        jdbc.update(
                                "INSERT INTO " + DEFAULT_TABLE_NAME
                                        + " (cooldown_key, provider, model, retry_after, reason, recorded_at)"
                                        + " VALUES (:cooldownKey, :provider, :model, :retryAfter, :reason, :recordedAt)",
                                params);
                    } catch (DuplicateKeyException duplicateEx) {
                        jdbc.update(
                                "UPDATE " + DEFAULT_TABLE_NAME
                                        + " SET retry_after = :retryAfter, reason = :reason, recorded_at = :recordedAt"
                                        + " WHERE cooldown_key = :cooldownKey AND retry_after < :retryAfter",
                                params);
                    }
                } else {
                    CooldownEntry current = existing.getFirst();
                    if (current.retryAfter() == null || retryAfter.isAfter(current.retryAfter())) {
                        jdbc.update(
                                "UPDATE " + DEFAULT_TABLE_NAME
                                        + " SET retry_after = :retryAfter, reason = :reason, recorded_at = :recordedAt"
                                        + " WHERE cooldown_key = :cooldownKey AND retry_after < :retryAfter",
                                params);
                    }
                }
            }
        } catch (DataAccessException ex) {
            log.debug("Failed to persist AI provider cooldown in JDBC store for key={}: {}", cooldownKey, ex.getMessage());
        }
    }

    @Override
    public boolean isInCooldown(String provider, String model, Instant now) {
        return findActiveCooldown(provider, model, now).isPresent();
    }

    @Override
    public Optional<CooldownEntry> findActiveCooldown(String provider, String model, Instant now) {
        String normalizedProvider = normalize(provider);
        if (normalizedProvider == null) {
            return Optional.empty();
        }
        Instant reference = now != null ? now : clock.instant();
        String normalizedModel = normalize(model);
        try {
            ensureSchemaInitialized();
            if (normalizedModel != null) {
                Optional<CooldownEntry> modelEntry = queryActiveByKey(
                        key(normalizedProvider, normalizedModel),
                        reference);
                if (modelEntry.isPresent()) {
                    return modelEntry;
                }
            }
            return queryActiveByKey(key(normalizedProvider, null), reference);
        } catch (DataAccessException ex) {
            log.debug("Failed to read AI provider cooldown from JDBC store for provider={}: {}",
                    normalizedProvider, ex.getMessage());
            return fallbackMirror.findActiveCooldown(normalizedProvider, normalizedModel, reference);
        }
    }

    @Override
    public void clearCooldown(String provider, String model) {
        String normalizedProvider = normalize(provider);
        if (normalizedProvider == null) {
            return;
        }
        String normalizedModel = normalize(model);
        fallbackMirror.clearCooldown(normalizedProvider, normalizedModel);
        try {
            ensureSchemaInitialized();
            synchronized (writeLock) {
                if (normalizedModel == null) {
                    jdbc.update(
                            "DELETE FROM " + DEFAULT_TABLE_NAME + " WHERE provider = :provider",
                            new MapSqlParameterSource("provider", normalizedProvider));
                } else {
                    MapSqlParameterSource params = new MapSqlParameterSource()
                            .addValue("modelKey", key(normalizedProvider, normalizedModel))
                            .addValue("wildcardKey", key(normalizedProvider, null));
                    jdbc.update(
                            "DELETE FROM " + DEFAULT_TABLE_NAME
                                    + " WHERE cooldown_key IN (:modelKey, :wildcardKey)",
                            params);
                }
            }
        } catch (DataAccessException ex) {
            log.debug("Failed to clear AI provider cooldown in JDBC store for provider={}: {}",
                    normalizedProvider, ex.getMessage());
        }
    }

    @Override
    public void clearAll() {
        fallbackMirror.clearAll();
        try {
            ensureSchemaInitialized();
            synchronized (writeLock) {
                lastPrunedAt = null;
                jdbc.update("DELETE FROM " + DEFAULT_TABLE_NAME, new MapSqlParameterSource());
            }
        } catch (DataAccessException ex) {
            log.debug("Failed to clear all AI provider cooldowns in JDBC store: {}", ex.getMessage());
        }
    }

    private void pruneExpiredCooldownsIfNeeded(Instant now, MapSqlParameterSource params) {
        Instant previous = lastPrunedAt;
        if (previous != null
                && !now.isBefore(previous)
                && java.time.Duration.between(previous, now).compareTo(PRUNE_MIN_INTERVAL) < 0) {
            return;
        }
        jdbc.update("DELETE FROM " + DEFAULT_TABLE_NAME + " WHERE retry_after <= :now", params);
        lastPrunedAt = now;
    }

    private Optional<CooldownEntry> queryActiveByKey(String cooldownKey, Instant reference) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cooldownKey", cooldownKey)
                .addValue("now", Timestamp.from(reference));
        List<CooldownEntry> rows = jdbc.query(
                "SELECT provider, model, retry_after, reason, recorded_at FROM " + DEFAULT_TABLE_NAME
                        + " WHERE cooldown_key = :cooldownKey AND retry_after > :now",
                params,
                this::mapRow);
        if (!rows.isEmpty()) {
            return Optional.of(rows.getFirst());
        }
        return Optional.empty();
    }

    private CooldownEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp retryAfterTs = rs.getTimestamp("retry_after");
        Timestamp recordedAtTs = rs.getTimestamp("recorded_at");
        return new CooldownEntry(
                rs.getString("provider"),
                rs.getString("model"),
                retryAfterTs != null ? retryAfterTs.toInstant() : null,
                rs.getString("reason"),
                recordedAtTs != null ? recordedAtTs.toInstant() : null);
    }

    private void ensureSchemaInitialized() {
        if (schemaInitialized.get()) {
            return;
        }
        synchronized (writeLock) {
            if (schemaInitialized.get()) {
                return;
            }
            try {
                if (jdbc.getJdbcTemplate() != null) {
                    jdbc.getJdbcTemplate().execute(
                            "CREATE TABLE IF NOT EXISTS " + DEFAULT_TABLE_NAME + " ("
                                    + "cooldown_key VARCHAR(255) PRIMARY KEY, "
                                    + "provider VARCHAR(64) NOT NULL, "
                                    + "model VARCHAR(160), "
                                    + "retry_after TIMESTAMP WITH TIME ZONE NOT NULL, "
                                    + "reason VARCHAR(512), "
                                    + "recorded_at TIMESTAMP WITH TIME ZONE NOT NULL"
                                    + ")");
                }
            } catch (DataAccessException ex) {
                log.debug("Skipping runtime DDL for {} (assuming provisioned via Flyway V71): {}",
                        DEFAULT_TABLE_NAME, ex.getMessage());
            }
            schemaInitialized.set(true);
        }
    }

    private String key(String normalizedProvider, String normalizedModel) {
        return normalizedProvider + "|" + (normalizedModel == null ? "*" : normalizedModel);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
