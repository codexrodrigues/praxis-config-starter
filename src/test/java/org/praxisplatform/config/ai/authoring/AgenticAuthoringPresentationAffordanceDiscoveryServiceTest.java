package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;

@Tag("unit")
class AgenticAuthoringPresentationAffordanceDiscoveryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void delegatesToRegisteredProviderForSupportedTarget() {
        AgenticAuthoringPresentationAffordanceDiscoveryService service =
                new AgenticAuthoringPresentationAffordanceDiscoveryService(List.of(
                        AgenticAuthoringResourceBackedPresentationAffordanceProvider.defaultProvider(objectMapper)));

        Optional<JsonNode> result = service.discover(new PresentationAffordanceDiscoveryToolRequest(
                null,
                "praxis-table",
                "column",
                "statusPriority",
                null,
                "text",
                null,
                null,
                "recursos visuais para coluna calculada",
                20));

        assertThat(result).isPresent();
        JsonNode payload = result.orElseThrow();
        assertThat(payload.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(payload.path("targetField").asText()).isEqualTo("statusPriority");
        assertThat(payload.path("dataType").asText()).isEqualTo("string");
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains("column.renderer.badge", "column.renderer.compose")
                .doesNotContain("column.format.date");
    }

    @Test
    void loadsTablePresentationAffordanceCatalogFromVersionedResource() {
        AgenticAuthoringPresentationAffordanceCatalog catalog =
                AgenticAuthoringPresentationAffordanceCatalog.load(
                        "ai-authoring/table-presentation-affordances.v0.json");

        assertThat(catalog.version()).isEqualTo("0.1.0");
        assertThat(catalog.componentId()).isEqualTo("praxis-table");
        assertThat(catalog.defaultTargetKind()).isEqualTo("column");
        assertThat(catalog.sourceRef()).isEqualTo("@praxisui/core:ColumnDefinition");
        assertThat(catalog.compatibleAffordances("column", "string"))
                .extracting(AgenticAuthoringPresentationAffordanceCatalog.PresentationAffordance::id)
                .contains("column.renderer.badge", "column.renderer.compose")
                .doesNotContain("column.format.date");
        assertThat(catalog.compatibleAffordances("column", "unknown"))
                .extracting(AgenticAuthoringPresentationAffordanceCatalog.PresentationAffordance::id)
                .contains("column.align", "column.renderer.badge")
                .doesNotContain("column.format.numeric", "column.format.date");
        assertThat(catalog.compatibleAffordances("column", "boolean"))
                .extracting(AgenticAuthoringPresentationAffordanceCatalog.PresentationAffordance::id)
                .contains("column.format.boolean", "column.presentation.status", "column.renderer.badge");
        assertThat(catalog.compatibleAffordances("column", "date"))
                .filteredOn(affordance -> "column.format.date".equals(affordance.id()))
                .flatExtracting(AgenticAuthoringPresentationAffordanceCatalog.PresentationAffordance::options)
                .contains("dd/MM/yyyy", "yyyy-MM-dd", "yyyy-MM-dd HH:mm");
    }

    @Test
    void catalogServiceReturnsPublicManifestAffordanceSlice() {
        AgenticAuthoringPresentationAffordanceCatalogService service =
                AgenticAuthoringPresentationAffordanceCatalogService.defaultService(objectMapper);

        JsonNode slice = service.getCatalogSlice("praxis-table");

        assertThat(slice.path("kind").asText())
                .isEqualTo("praxis.ai-authoring.presentation-affordance-catalog");
        assertThat(slice.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(slice.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains("column.renderer.badge", "column.format.date", "column.format.numeric");
    }

    @Test
    void registryPresentationAffordanceCatalogOverridesBuiltInCatalog() {
        AiRegistryRepository repository = Mockito.mock(AiRegistryRepository.class);
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "praxis-table",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(Optional.of(AiRegistry.builder()
                        .registryKey("praxis-table")
                        .payload("""
                                {
                                  "componentDefinition": {
                                    "jsonSchema": {
                                      "authoringManifest": {
                                        "componentId": "praxis-table",
                                        "presentationAffordances": {
                                          "version": "9.9.9",
                                          "kind": "praxis.ai-authoring.presentation-affordance-catalog",
                                          "defaultTargetKind": "column",
                                          "sourceRef": "ai_registry:praxis-table:authoringManifest.presentationAffordances",
                                          "affordances": [
                                            {
                                              "id": "column.renderer.enterpriseStatusBadge",
                                              "targetKind": "column",
                                              "category": "renderer",
                                              "description": "Render enterprise-governed status values as a badge.",
                                              "options": ["governed palette", "tooltip"],
                                              "appliesToTypes": ["string"],
                                              "unknownCompatible": false
                                            }
                                          ]
                                        }
                                      }
                                    }
                                  }
                                }
                                """)
                        .build()));
        AgenticAuthoringPresentationAffordanceCatalogService catalogService =
                AgenticAuthoringPresentationAffordanceCatalogService.defaultService(objectMapper, repository);

        JsonNode slice = catalogService.getCatalogSlice("praxis-table");

        assertThat(slice.path("version").asText()).isEqualTo("9.9.9");
        assertThat(slice.path("sourceRef").asText())
                .isEqualTo("ai_registry:praxis-table:authoringManifest.presentationAffordances");
        assertThat(slice.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .containsExactly("column.renderer.enterpriseStatusBadge");

        AgenticAuthoringPresentationAffordanceDiscoveryService discoveryService =
                new AgenticAuthoringPresentationAffordanceDiscoveryService(List.of(
                        new AgenticAuthoringResourceBackedPresentationAffordanceProvider(objectMapper, catalogService)));

        Optional<JsonNode> result = discoveryService.discover(new PresentationAffordanceDiscoveryToolRequest(
                null,
                "praxis-table",
                "column",
                "statusPriority",
                null,
                "string",
                null,
                null,
                "opcoes enterprise de badge",
                20));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .containsExactly("column.renderer.enterpriseStatusBadge");
    }

    @Test
    void registryPresentationAffordanceCatalogFailsWhenPayloadIsInvalid() {
        AiRegistryRepository repository = Mockito.mock(AiRegistryRepository.class);
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "praxis-table",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(Optional.of(AiRegistry.builder()
                        .registryKey("praxis-table")
                        .payload("""
                                {
                                  "componentDefinition": {
                                    "jsonSchema": {
                                      "authoringManifest": {
                                        "componentId": "praxis-table",
                                        "presentationAffordances": {
                                          "version": "",
                                          "defaultTargetKind": "column",
                                          "sourceRef": "",
                                          "affordances": []
                                        }
                                      }
                                    }
                                  }
                                }
                                """)
                        .build()));
        AgenticAuthoringPresentationAffordanceCatalogService catalogService =
                AgenticAuthoringPresentationAffordanceCatalogService.defaultService(objectMapper, repository);

        assertThatThrownBy(() -> catalogService.getCatalogSlice("praxis-table"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Presentation affordance catalog field is required: version");
    }

    @Test
    void returnsEmptyWhenNoProviderSupportsTarget() {
        AgenticAuthoringPresentationAffordanceDiscoveryService service =
                new AgenticAuthoringPresentationAffordanceDiscoveryService(List.of(
                        AgenticAuthoringResourceBackedPresentationAffordanceProvider.defaultProvider(objectMapper)));

        Optional<JsonNode> result = service.discover(new PresentationAffordanceDiscoveryToolRequest(
                null,
                "praxis-unknown",
                "field",
                "statusPriority",
                null,
                "string",
                null,
                null,
                "recursos visuais para campo",
                20));

        assertThat(result).isEmpty();
    }

    @Test
    void discoversFormAffordancesFromSameResourceBackedProvider() {
        AgenticAuthoringPresentationAffordanceDiscoveryService service =
                new AgenticAuthoringPresentationAffordanceDiscoveryService(List.of(
                        AgenticAuthoringResourceBackedPresentationAffordanceProvider.defaultProvider(objectMapper)));

        Optional<JsonNode> result = service.discover(new PresentationAffordanceDiscoveryToolRequest(
                null,
                "praxis-dynamic-form",
                null,
                "observacaoInterna",
                null,
                null,
                null,
                null,
                "quais recursos visuais posso usar neste campo",
                20));

        assertThat(result).isPresent();
        JsonNode payload = result.orElseThrow();
        assertThat(payload.path("targetKind").asText()).isEqualTo("field");
        assertThat(payload.path("requiresTypeConfirmation").asBoolean()).isTrue();
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains("field.label", "field.helperText", "field.layout")
                .doesNotContain("field.controlType");
    }

    @Test
    void discoversFilterAffordancesFromSameResourceBackedProvider() {
        AgenticAuthoringPresentationAffordanceDiscoveryService service =
                new AgenticAuthoringPresentationAffordanceDiscoveryService(List.of(
                        AgenticAuthoringResourceBackedPresentationAffordanceProvider.defaultProvider(objectMapper)));

        Optional<JsonNode> result = service.discover(new PresentationAffordanceDiscoveryToolRequest(
                null,
                "praxis-filter",
                null,
                "",
                null,
                "string",
                null,
                null,
                "opcoes de apresentacao para filtros",
                20));

        assertThat(result).isPresent();
        JsonNode payload = result.orElseThrow();
        assertThat(payload.path("targetKind").asText()).isEqualTo("filter");
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains("filter.fieldSelection", "filter.layout", "filter.activeCriteriaChips");
    }
}
