package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;

@Tag("unit")
class AgenticAuthoringComponentCapabilitiesServiceTest {

    @Test
    void exposesFilterCatalogTogetherWithCanonicalComponentCatalogs() {
        AgenticAuthoringComponentCapabilitiesResult result =
                new AgenticAuthoringComponentCapabilitiesService().listCapabilities();

        assertThat(result.catalogs())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog::componentId)
                .contains(
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

    @Test
    void mergesGovernedComponentsFromAiRegistryAuthoringManifests() {
        AiRegistryRepository repository = mock(AiRegistryRepository.class);
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(List.of(AiRegistry.builder()
                        .registryType("component_definition")
                        .registryKey("praxis-tabs")
                        .componentType("component-definition")
                        .scope(Scope.SYSTEM)
                        .scopeKey("GLOBAL")
                        .payload("""
                                {
                                  "componentDefinition": {
                                    "description": "Abas dinamicas para organizar conteudo em secoes.",
                                    "jsonSchema": {
                                      "friendlyName": "Praxis Tabs",
                                      "selector": "praxis-tabs",
                                      "tags": ["widget", "tabs", "container"],
                                      "authoringManifest": {
                                        "schemaVersion": "1.0.0",
                                        "manifestVersion": "1.0.0",
                                        "componentId": "praxis-tabs",
                                        "editableTargets": [{"kind": "tab"}, {"kind": "tabContent"}],
                                        "operations": [
                                          {
                                            "operationId": "tab.add",
                                            "description": "Add a governed tab.",
                                            "target": {"kind": "tab"},
                                            "effects": [{"kind": "append"}]
                                          }
                                        ]
                                      }
                                    }
                                  }
                                }
                                """)
                        .build()));

        AgenticAuthoringComponentCapabilitiesResult result =
                new AgenticAuthoringComponentCapabilitiesService(repository, new ObjectMapper()).listCapabilities();

        AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog tabsCatalog = result.catalogs().stream()
                .filter(catalog -> "praxis-tabs".equals(catalog.componentId()))
                .findFirst()
                .orElseThrow();
        assertThat(tabsCatalog.capabilities())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapability::id)
                .contains("component.author", "tab.add");
        assertThat(tabsCatalog.capabilities().get(0).triggerTerms())
                .contains("praxis-tabs", "Praxis Tabs", "tabs");
    }
}
