package org.praxisplatform.config.ai.authoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "praxis.ai.authoring.component-capabilities")
public class AgenticAuthoringComponentCapabilitiesProperties {

    private static final long PRELOAD_TIMEOUT_MARGIN_MS = 1_000L;

    private long cacheTtlMs = 600_000L;
    private long registryLoadTimeoutMs = 30_000L;
    private long degradedRetryMs = 5_000L;
    private long preloadTimeoutMs = 35_000L;

    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public void setCacheTtlMs(long cacheTtlMs) {
        this.cacheTtlMs = Math.max(0L, cacheTtlMs);
    }

    public long getRegistryLoadTimeoutMs() {
        return registryLoadTimeoutMs;
    }

    public void setRegistryLoadTimeoutMs(long registryLoadTimeoutMs) {
        this.registryLoadTimeoutMs = Math.max(1L, registryLoadTimeoutMs);
    }

    public long getDegradedRetryMs() {
        return degradedRetryMs;
    }

    public void setDegradedRetryMs(long degradedRetryMs) {
        this.degradedRetryMs = Math.max(0L, degradedRetryMs);
    }

    public long getPreloadTimeoutMs() {
        return preloadTimeoutMs;
    }

    public void setPreloadTimeoutMs(long preloadTimeoutMs) {
        this.preloadTimeoutMs = Math.max(1L, preloadTimeoutMs);
    }

    public long effectivePreloadTimeoutMs() {
        return Math.max(preloadTimeoutMs, registryLoadTimeoutMs + PRELOAD_TIMEOUT_MARGIN_MS);
    }
}
