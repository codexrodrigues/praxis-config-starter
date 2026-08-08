package org.praxisplatform.config.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
