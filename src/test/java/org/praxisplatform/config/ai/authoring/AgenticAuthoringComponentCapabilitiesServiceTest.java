package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringComponentCapabilitiesServiceTest {

    @Test
    void exposesFilterCatalogTogetherWithCanonicalComponentCatalogs() {
        AgenticAuthoringComponentCapabilitiesResult result =
                new AgenticAuthoringComponentCapabilitiesService().listCapabilities();

        assertThat(result.catalogs())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog::componentId)
                .containsExactly(
                        "praxis-dynamic-form",
                        "praxis-table",
                        "praxis-chart",
                        "praxis-filter");

        AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog filterCatalog =
                result.catalogs().stream()
                        .filter(catalog -> "praxis-filter".equals(catalog.componentId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(filterCatalog.capabilities())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapability::changeKind)
                .contains("recommend_search_fields", "connect_filter_to_results");
    }
}
