package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Default thread-safe in-memory implementation of {@link AiProviderRateLimitStore}.
 */
public class InMemoryAiProviderRateLimitStore implements AiProviderRateLimitStore {

    private static final int MAX_TRACKED_KEYS = 256;

    private final Map<String, CooldownEntry> cooldownsByKey = new ConcurrentHashMap<>();

    @Override
    public void recordCooldown(String provider, String model, Instant retryAfter, String reason) {
        String normalizedProvider = normalize(provider);
        if (normalizedProvider == null || retryAfter == null) {
            return;
        }
        String normalizedModel = normalize(model);
        String key = key(normalizedProvider, normalizedModel);
        Instant now = Instant.now();
        if (!retryAfter.isAfter(now)) {
            return;
        }
        evictExpiredIfNeeded(now);
        CooldownEntry incoming = new CooldownEntry(
                normalizedProvider,
                normalizedModel,
                retryAfter,
                reason,
                now);
        cooldownsByKey.compute(key, (ignored, existing) -> {
            if (existing != null && existing.retryAfter() != null && existing.retryAfter().isAfter(retryAfter)) {
                return existing;
            }
            return incoming;
        });
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
        Instant reference = now != null ? now : Instant.now();
        String normalizedModel = normalize(model);
        if (normalizedModel != null) {
            CooldownEntry modelEntry = getActiveOrEvict(key(normalizedProvider, normalizedModel), reference);
            if (modelEntry != null) {
                return Optional.of(modelEntry);
            }
        }
        CooldownEntry providerEntry = getActiveOrEvict(key(normalizedProvider, null), reference);
        return Optional.ofNullable(providerEntry);
    }

    @Override
    public void clearCooldown(String provider, String model) {
        String normalizedProvider = normalize(provider);
        if (normalizedProvider == null) {
            return;
        }
        String normalizedModel = normalize(model);
        cooldownsByKey.remove(key(normalizedProvider, normalizedModel));
        if (normalizedModel == null) {
            cooldownsByKey.keySet().removeIf(k -> k.startsWith(normalizedProvider + "|"));
        } else {
            cooldownsByKey.remove(key(normalizedProvider, null));
        }
    }

    @Override
    public void clearAll() {
        cooldownsByKey.clear();
    }

    private void evictExpiredIfNeeded(Instant now) {
        if (cooldownsByKey.size() < MAX_TRACKED_KEYS) {
            return;
        }
        cooldownsByKey.entrySet().removeIf(entry -> entry.getValue() == null || !entry.getValue().isActiveAt(now));
        if (cooldownsByKey.size() >= MAX_TRACKED_KEYS) {
            String oldestKey = null;
            Instant oldestRecordedAt = null;
            for (Map.Entry<String, CooldownEntry> entry : cooldownsByKey.entrySet()) {
                Instant recordedAt = entry.getValue() != null ? entry.getValue().recordedAt() : null;
                if (oldestKey == null || (recordedAt != null && (oldestRecordedAt == null || recordedAt.isBefore(oldestRecordedAt)))) {
                    oldestKey = entry.getKey();
                    oldestRecordedAt = recordedAt;
                }
            }
            if (oldestKey != null) {
                cooldownsByKey.remove(oldestKey);
            }
        }
    }

    private CooldownEntry getActiveOrEvict(String key, Instant now) {
        CooldownEntry entry = cooldownsByKey.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isActiveAt(now)) {
            return entry;
        }
        cooldownsByKey.remove(key, entry);
        return null;
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
}
