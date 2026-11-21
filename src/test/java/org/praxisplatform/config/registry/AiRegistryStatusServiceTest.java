package org.praxisplatform.config.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;

/** Verifies that generated catalog readiness is independent from optional authored templates. */
@Tag("unit")
class AiRegistryStatusServiceTest {

    private static final String COMPONENT_DEFINITION = "component_definition";
    private static final String TEMPLATE = "template";
    private static final String COMPONENT_TYPE = "component-definition";

    private final AiRegistryRepository repository = org.mockito.Mockito.mock(AiRegistryRepository.class);
    private final AiRegistryBootstrapProperties bootstrapProperties = new AiRegistryBootstrapProperties();
    private final AiRegistryHealthProperties healthProperties = new AiRegistryHealthProperties();
    private final AiRegistryBootstrapState bootstrapState = new AiRegistryBootstrapState();
    private final AiRegistryStatusService service = new AiRegistryStatusService(
            repository,
            bootstrapProperties,
            healthProperties,
            bootstrapState);

    @Test
    void considersTheGeneratedComponentCatalogReadyWithoutAuthoredTemplatesByDefault() {
        arrangeGeneratedCatalog(105, 0);

        AiRegistryStatusReport report = service.getStatus();

        assertThat(report.isReady()).isTrue();
        assertThat(report.getStatus()).isEqualTo("ready");
        assertThat(report.getMinTemplates()).isZero();
        assertThat(report.getTemplateCount()).isZero();
    }

    @Test
    void honorsAnExplicitTemplateReadinessRequirement() {
        arrangeGeneratedCatalog(105, 0);
        healthProperties.setMinTemplates(1L);

        AiRegistryStatusReport report = service.getStatus();

        assertThat(report.isReady()).isFalse();
        assertThat(report.getStatus()).isEqualTo("low-counts");
        assertThat(report.getMinTemplates()).isEqualTo(1L);
    }

    @Test
    void cachesReadyStatusWithinConfiguredTtlAndExpiresAfterTtl() {
        arrangeGeneratedCatalog(105, 0);
        AtomicReference<Instant> currentInstant =
                new AtomicReference<>(Instant.parse("2026-11-21T12:00:00Z"));
        Clock mutableClock = new Clock() {
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
        AiRegistryHealthProperties cachedHealthProperties = new AiRegistryHealthProperties();
        cachedHealthProperties.setCacheTtlMs(60_000L);
        AiRegistryStatusService cachedService = new AiRegistryStatusService(
                repository, bootstrapProperties, cachedHealthProperties, bootstrapState, mutableClock);

        AiRegistryStatusReport first = cachedService.getStatus();
        currentInstant.set(Instant.parse("2026-11-21T12:00:30Z"));
        AiRegistryStatusReport second = cachedService.getStatus();

        assertThat(first.isReady()).isTrue();
        assertThat(second).isSameAs(first);
        verify(repository, times(1)).countByRegistryType(COMPONENT_DEFINITION);

        currentInstant.set(Instant.parse("2026-11-21T12:01:01Z"));
        AiRegistryStatusReport afterExpiry = cachedService.getStatus();

        assertThat(afterExpiry.isReady()).isTrue();
        verify(repository, times(2)).countByRegistryType(COMPONENT_DEFINITION);
    }

    @Test
    void invalidatesCachedReadyStatusOnManualOrEventInvalidation() {
        arrangeGeneratedCatalog(105, 0);
        service.getStatus();
        service.getStatus();
        verify(repository, times(1)).countByRegistryType(COMPONENT_DEFINITION);

        service.invalidateCache();
        service.getStatus();
        verify(repository, times(2)).countByRegistryType(COMPONENT_DEFINITION);

        service.onComponentDefinitionsChanged(new AiRegistryComponentDefinitionsChangedEvent(
                "release-1", 105, Instant.parse("2026-11-21T12:00:00Z")));
        service.getStatus();
        verify(repository, times(3)).countByRegistryType(COMPONENT_DEFINITION);
    }

    @Test
    void doesNotCacheUnreadyStatusReports() {
        arrangeGeneratedCatalog(0, 0);
        bootstrapProperties.setMinComponentDefinitions(1L);

        AiRegistryStatusReport first = service.getStatus();
        AiRegistryStatusReport second = service.getStatus();

        assertThat(first.isReady()).isFalse();
        assertThat(second.isReady()).isFalse();
        verify(repository, times(2)).countByRegistryType(COMPONENT_DEFINITION);
    }

    private void arrangeGeneratedCatalog(long componentDefinitions, long templates) {
        when(repository.countByRegistryType(COMPONENT_DEFINITION)).thenReturn(componentDefinitions);
        when(repository.countByRegistryType(TEMPLATE)).thenReturn(templates);
        for (String componentId : bootstrapProperties.getRequiredComponents()) {
            when(repository.existsByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                    COMPONENT_DEFINITION,
                    componentId,
                    COMPONENT_TYPE,
                    Scope.SYSTEM,
                    "GLOBAL")).thenReturn(true);
        }
    }
}
