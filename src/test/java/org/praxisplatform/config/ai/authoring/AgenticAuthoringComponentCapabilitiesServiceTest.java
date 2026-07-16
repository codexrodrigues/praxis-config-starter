package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void exposesTableDateFormatAndStatusPresentationCapabilities() {
        AgenticAuthoringComponentCapabilitiesResult result =
                new AgenticAuthoringComponentCapabilitiesService().listCapabilities();

        AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog tableCatalog =
                result.catalogs().stream()
                        .filter(catalog -> "praxis-table".equals(catalog.componentId()))
                        .findFirst()
                        .orElseThrow();

        assertThat(tableCatalog.capabilities())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapability::id)
                .contains(
                        "praxis-table.column.format.date@0.1.0",
                        "praxis-table.column.presentation.status@0.1.0");
        assertThat(tableCatalog.capabilities())
                .filteredOn(capability -> "praxis-table.column.presentation.status@0.1.0".equals(capability.id()))
                .flatExtracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapability::triggerTerms)
                .contains("status", "ativo", "badge", "chip", "toggle");
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

    @Test
    void keepsLateCanonicalManifestOperationsAvailableToSemanticIntentResolution() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode manifest = payload.putObject("componentDefinition")
                .putObject("jsonSchema")
                .putObject("authoringManifest");
        manifest.put("manifestVersion", "2.0.0");
        manifest.put("componentId", "praxis-table");
        var operations = manifest.putArray("operations");
        for (int index = 0; index < 24; index++) {
            operations.addObject().put("operationId", "table.operation." + index);
        }
        operations.addObject()
                .put("operationId", "filter.advanced.configure")
                .put("title", "Configurar filtros avançados");
        manifest.putArray("examples").addObject()
                .put("operationId", "filter.advanced.configure")
                .put("request", "Ative os filtros avançados desta tabela")
                .put("isPositive", true);

        AiRegistryRepository repository = mock(AiRegistryRepository.class);
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(List.of(AiRegistry.builder()
                        .registryType("component_definition")
                        .registryKey("praxis-table")
                        .componentType("component-definition")
                        .scope(Scope.SYSTEM)
                        .scopeKey("GLOBAL")
                        .payload(payload.toString())
                        .build()));

        AgenticAuthoringComponentCapabilitiesResult result =
                new AgenticAuthoringComponentCapabilitiesService(repository, objectMapper).listCapabilities();

        AgenticAuthoringComponentCapabilitiesResult.ComponentCapability advancedFilters = result.catalogs().stream()
                .filter(catalog -> "praxis-table".equals(catalog.componentId()))
                .flatMap(catalog -> catalog.capabilities().stream())
                .filter(capability -> "filter.advanced.configure".equals(capability.changeKind()))
                .findFirst()
                .orElseThrow();
        assertThat(advancedFilters.examples())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample::prompt)
                .contains("Ative os filtros avançados desta tabela");
    }

    @Test
    void normalizesGovernedCapabilityTermsBeforePublishingCatalog() {
        AiRegistryRepository repository = mock(AiRegistryRepository.class);
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(List.of(AiRegistry.builder()
                        .registryType("component_definition")
                        .registryKey("praxis-table")
                        .componentType("component-definition")
                        .scope(Scope.SYSTEM)
                        .scopeKey("GLOBAL")
                        .payload("""
                                {
                                  "componentDefinition": {
                                    "description": "Tabela governada.",
                                    "jsonSchema": {
                                      "friendlyName": "Praxis Table",
                                      "selector": "praxis-table",
                                      "authoringManifest": {
                                        "manifestVersion": "1.0.0",
                                        "componentId": "praxis-table",
                                        "operations": [
                                          {
                                            "operationId": "column.sortable.set",
                                            "title": "Definir ordenaÃ§Ã£o da coluna",
                                            "target": {"kind": "column"},
                                            "effects": [{"kind": "merge-by-key", "handler": "sortable"}]
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

        AgenticAuthoringComponentCapabilitiesResult.ComponentCapability sortable = result.catalogs().stream()
                .filter(catalog -> "praxis-table".equals(catalog.componentId()))
                .flatMap(catalog -> catalog.capabilities().stream())
                .filter(capability -> "column.sortable.set".equals(capability.id()))
                .findFirst()
                .orElseThrow();
        assertThat(sortable.triggerTerms())
                .contains("Definir ordenação da coluna")
                .doesNotContain("Definir ordenaÃ§Ã£o da coluna");
    }

    @Test
    void reusesCapabilitiesSnapshotWithinServiceInstance() {
        AiRegistryRepository repository = mock(AiRegistryRepository.class);
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(List.of());

        AgenticAuthoringComponentCapabilitiesService service =
                new AgenticAuthoringComponentCapabilitiesService(repository, new ObjectMapper());

        AgenticAuthoringComponentCapabilitiesResult first = service.listCapabilities();
        AgenticAuthoringComponentCapabilitiesResult second = service.listCapabilities();

        assertThat(second).isSameAs(first);
        verify(repository, times(1)).findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL");
    }

    @Test
    void refreshesGovernedCapabilitiesAfterCacheTtl() throws Exception {
        AiRegistryRepository repository = mock(AiRegistryRepository.class);
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(List.of())
                .thenReturn(List.of(AiRegistry.builder()
                        .registryType("component_definition")
                        .registryKey("praxis-tabs")
                        .componentType("component-definition")
                        .scope(Scope.SYSTEM)
                        .scopeKey("GLOBAL")
                        .payload("""
                                {
                                  "componentDefinition": {
                                    "jsonSchema": {
                                      "friendlyName": "Praxis Tabs",
                                      "authoringManifest": {
                                        "manifestVersion": "1.0.0",
                                        "componentId": "praxis-tabs"
                                      }
                                    }
                                  }
                                }
                                """)
                        .build()));
        AgenticAuthoringComponentCapabilitiesService service =
                new AgenticAuthoringComponentCapabilitiesService(repository, new ObjectMapper(), 1L);

        assertThat(service.listCapabilities().catalogs())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog::componentId)
                .doesNotContain("praxis-tabs");
        Thread.sleep(5L);

        assertThat(service.listCapabilities().catalogs())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog::componentId)
                .contains("praxis-tabs");
        verify(repository, times(2)).findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL");
    }

    @Test
    void explicitInvalidationReloadsGovernedCapabilities() {
        AiRegistryRepository repository = mock(AiRegistryRepository.class);
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(List.of())
                .thenReturn(List.of(AiRegistry.builder()
                        .registryType("component_definition")
                        .registryKey("praxis-tabs")
                        .componentType("component-definition")
                        .scope(Scope.SYSTEM)
                        .scopeKey("GLOBAL")
                        .payload("""
                                {
                                  "componentDefinition": {
                                    "jsonSchema": {
                                      "authoringManifest": {
                                        "manifestVersion": "1.0.0",
                                        "componentId": "praxis-tabs"
                                      }
                                    }
                                  }
                                }
                                """)
                        .build()));
        AgenticAuthoringComponentCapabilitiesService service =
                new AgenticAuthoringComponentCapabilitiesService(repository, new ObjectMapper());

        service.listCapabilities();
        service.invalidateCapabilitiesCache();

        assertThat(service.listCapabilities().catalogs())
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog::componentId)
                .contains("praxis-tabs");
        verify(repository, times(2)).findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL");
    }

    @Test
    void boundsRegistryLoadingAndCachesBuiltInFallbackWhenRepositoryBlocks() throws Exception {
        AiRegistryRepository repository = mock(AiRegistryRepository.class);
        CountDownLatch registryQueryStarted = new CountDownLatch(1);
        CountDownLatch registryQueryInterrupted = new CountDownLatch(1);
        CountDownLatch neverReleaseRegistryQuery = new CountDownLatch(1);
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenAnswer(invocation -> {
                    registryQueryStarted.countDown();
                    try {
                        neverReleaseRegistryQuery.await();
                    } catch (InterruptedException ex) {
                        registryQueryInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                });
        AgenticAuthoringComponentCapabilitiesService service =
                new AgenticAuthoringComponentCapabilitiesService(
                        repository,
                        new ObjectMapper(),
                        300_000L,
                        25L);

        try {
            long startedAtNanos = System.nanoTime();
            AgenticAuthoringComponentCapabilitiesResult first = service.listCapabilities();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            AgenticAuthoringComponentCapabilitiesResult second = service.listCapabilities();

            assertThat(registryQueryStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(registryQueryInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(elapsedMs).isLessThan(1_000L);
            assertThat(first.catalogs())
                    .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog::componentId)
                    .containsExactly(
                            "praxis-dynamic-form",
                            "praxis-table",
                            "praxis-chart",
                            "praxis-filter");
            assertThat(second).isSameAs(first);
            verify(repository, times(1)).findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                    "component_definition",
                    "component-definition",
                    Scope.SYSTEM,
                    "GLOBAL");
        } finally {
            neverReleaseRegistryQuery.countDown();
            service.shutdown();
        }
    }
}
