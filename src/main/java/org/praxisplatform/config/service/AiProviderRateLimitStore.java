package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Pluggable store for coordinating AI provider rate-limit and capacity cooldown windows
 * across threads and cluster instances.
 *
 * <p>The default implementation is in-memory ({@link InMemoryAiProviderRateLimitStore}),
 * while multi-instance deployments can provide a shared implementation (such as Redis or
 * JDBC) so that an HTTP 429 / {@code Retry-After} signal observed on one node immediately
 * steers sibling nodes toward healthy fallback candidates.</p>
 */
public interface AiProviderRateLimitStore {

    record CooldownEntry(
            String provider,
            String model,
            Instant retryAfter,
            String reason,
            Instant recordedAt) {

        public boolean isActiveAt(Instant now) {
            Instant reference = now != null ? now : Instant.now();
            return retryAfter != null && retryAfter.isAfter(reference);
        }
    }

    /**
     * Records a rate-limit or capacity cooldown window for a provider (and optional model).
     */
    void recordCooldown(String provider, String model, Instant retryAfter, String reason);

    /**
     * Returns {@code true} if either the specific {@code (provider, model)} pair or the
     * provider-wide key is currently in cooldown at {@code now}.
     */
    boolean isInCooldown(String provider, String model, Instant now);

    /**
     * Returns the active cooldown entry for {@code (provider, model)} or provider-wide if active.
     */
    Optional<CooldownEntry> findActiveCooldown(String provider, String model, Instant now);

    /**
     * Clears any active cooldown recorded for {@code (provider, model)}.
     */
    void clearCooldown(String provider, String model);

    /**
     * Clears all recorded cooldowns.
     */
    void clearAll();
}
