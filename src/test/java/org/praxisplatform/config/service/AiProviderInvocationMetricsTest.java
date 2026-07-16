package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AiProviderInvocationMetricsTest {

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        Metrics.addRegistry(meterRegistry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(meterRegistry);
        meterRegistry.close();
    }

    @Test
    void recordsOnlyBoundedOperationalDimensionsAndAvailableUsage() {
        AiProviderInvocationMetrics.record(new AiProviderInvocationTelemetry(
                "preview_message",
                1,
                "OpenAI",
                "gpt-5-mini",
                "responses-http",
                "success",
                null,
                420L,
                120,
                30,
                50,
                null,
                150,
                "response-secret-id",
                "stop"));

        assertThat(meterRegistry.find(AiProviderInvocationMetrics.INVOCATIONS_TOTAL)
                .tags("phase", "preview_message", "provider", "openai", "status", "success")
                .counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.find(AiProviderInvocationMetrics.DURATION_MS)
                .tags("phase", "preview_message", "provider", "openai", "status", "success")
                .summary().totalAmount()).isEqualTo(420.0d);
        assertThat(meterRegistry.find(AiProviderInvocationMetrics.TOKENS)
                .tags("phase", "preview_message", "provider", "openai", "kind", "cache_read_input")
                .summary().totalAmount()).isEqualTo(50.0d);
        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().equals("model")
                                || tag.getValue().contains("secret")));
    }
}
