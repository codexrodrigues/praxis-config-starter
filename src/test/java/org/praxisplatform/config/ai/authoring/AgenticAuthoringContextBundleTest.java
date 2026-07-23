package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringContextBundleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsGlobalComponentDiscoveryButScopesDetailedCapabilitiesToRelevantComponents() {
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "Crie uma pagina com acordeon de funcionarios, com dados gerais, detalhes e acoes.",
                "page-builder-ia",
                "praxis-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-5-mini",
                "test-key");

        JsonNode bundle = AgenticAuthoringContextBundle.create(
                objectMapper,
                request,
                request.userPrompt(),
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "");

        JsonNode componentContext = bundle.path("componentContext");
        assertThat(componentContext.path("authorableComponents")).hasSize(8);
        assertThat(componentContext.path("authorableComponents").toString())
                .doesNotContain("changeKinds", "semanticTerms");
        assertThat(componentContext.path("platformGuide").path("componentFamilyCount").asInt())
                .isEqualTo(8);
        assertThat(componentContext.path("platformGuide").has("componentFamilies")).isFalse();
        assertThat(componentContext.path("componentCapabilities").path("totalCatalogs").asInt()).isEqualTo(8);
        assertThat(componentContext.path("componentCapabilities").path("includedCatalogs").asInt())
                .isLessThanOrEqualTo(6);
        assertThat(componentIds(componentContext.path("componentCapabilities").path("catalogs")))
                .contains("praxis-expansion")
                .doesNotContain("praxis-settings-panel", "praxis-files-upload");
        assertThat(bundle.path("toolCatalog").path("presentationAffordanceDiscovery").path("purpose").asText())
                .contains("table column renderers", "badges", "alignment");
        assertThat(bundle.path("toolCatalog").path("presentationAffordanceDiscovery").path("result").asText())
                .contains("component target and data type");
    }

    @Test
    void ranksAddingANewTableColumnAheadOfExistingColumnOperationsAsLlmGrounding() {
        JsonNode componentContext = tableComponentContext(
                "Adicione a coluna e-mail à tabela de funcionários e mantenha as demais colunas.");
        JsonNode tableCatalog = componentContext.path("componentCapabilities").path("catalogs").get(0);

        assertThat(tableCatalog.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(tableCatalog.path("capabilities"))
                .extracting(capability -> capability.path("changeKind").asText())
                .contains("column.add");
        assertThat(componentContext.path("operationSelectionRule").asText())
                .contains("ranked governed operation candidates, not an intent decision", "LLM must select");
        assertThat(componentContext.path("componentCapabilities").path("detailPolicy").asText())
                .contains("only ranks governed candidates", "LLM still decides semantic intent");
    }

    @Test
    void keepsReorderingAsLlMDecidedOperationInsteadOfGuaranteeingLexicalRank() {
        JsonNode componentContext = tableComponentContext(
                "Mova a coluna salário líquido para a primeira posição da tabela.");
        JsonNode tableCatalog = componentContext.path("componentCapabilities").path("catalogs").get(0);

        assertThat(tableCatalog.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(tableCatalog.path("capabilities")).isNotEmpty();
        assertThat(componentContext.path("componentCapabilities").path("detailPolicy").asText())
                .contains("Capability scoring only ranks governed candidates", "LLM still decides semantic intent");
    }

    @Test
    void ranksByTheBestSemanticExampleInsteadOfRewardingRepeatedGenericExamples() {
        var exact = new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                "column.add",
                "column.add",
                List.of(),
                List.of(),
                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                        "Adicione a coluna e-mail à tabela.",
                        "Adicionar uma coluna de schema.",
                        List.of())));
        var repeatedGeneric = new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                "column.visibility.set",
                "column.visibility.set",
                List.of(),
                List.of(),
                List.of(
                        example("Oculte uma coluna."),
                        example("Mostre uma coluna."),
                        example("Alterne uma coluna.")));

        var ranked = AgenticAuthoringContextBundle.promptRelevantCapabilities(
                "Adicione a coluna e-mail à tabela.",
                List.of(repeatedGeneric, exact));

        assertThat(ranked.get(0).id()).isEqualTo("column.add");
    }

    @Test
    void projectsRetrievedCandidateIdsThroughCanonicalServerCapabilities() {
        var contextHints = objectMapper.createObjectNode();
        var evidence = contextHints.putObject("authoringEvidence");
        evidence.put("componentId", "praxis-table");
        evidence.putArray("operationCandidates")
                .addObject()
                .put("id", "table.create")
                .put("changeKind", "client_forged_change")
                .putArray("triggerTerms")
                .add("client-forged-trigger");
        evidence.withArray("operationCandidates")
                .addObject()
                .put("id", "unknown.client.operation")
                .put("changeKind", "unknown_change");
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "Crie uma tabela.",
                "page-builder-ia",
                "praxis-table",
                "/page-builder-ia",
                contextHints,
                null,
                "openai",
                "gpt-5-mini",
                "test-key");

        JsonNode bundle = AgenticAuthoringContextBundle.create(
                objectMapper,
                request,
                request.userPrompt(),
                objectMapper.createObjectNode(),
                new AgenticAuthoringTarget(
                        "employees-table",
                        "praxis-table",
                        "/api/employees",
                        "/schemas/filtered?resource=employees",
                        null,
                        null),
                List.of(),
                componentCapabilities(),
                "");

        JsonNode capabilities = bundle.path("componentContext")
                .path("componentCapabilities")
                .path("catalogs")
                .findValue("capabilities");
        assertThat(capabilities).hasSize(1);
        assertThat(capabilities.path(0).path("id").asText()).isEqualTo("table.create");
        assertThat(capabilities.path(0).path("changeKind").asText()).isEqualTo("create_table");
        assertThat(capabilities.path(0).path("triggerTerms").toString())
                .contains("tabela", "detalhes")
                .doesNotContain("client-forged-trigger");
        assertThat(capabilities.toString()).doesNotContain("unknown.client.operation", "unknown_change");
    }

    @Test
    void mergesPromptRelevantCanonicalOperationsWithAnIncompleteRetrievedCandidateSet() {
        var contextHints = objectMapper.createObjectNode();
        var evidence = contextHints.putObject("authoringEvidence");
        evidence.put("componentId", "praxis-table");
        evidence.putArray("operationCandidates")
                .addObject()
                .put("id", "column.valueMapping.set");
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "Ativo vira Status.",
                "page-builder-ia",
                "praxis-table",
                "/page-builder-ia",
                contextHints,
                null,
                "openai",
                "gpt-5-mini",
                "test-key");
        var header = new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                "column.header.set",
                "column.header.set",
                List.of("Renomear título da coluna"),
                List.of(),
                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                        "Ativo vira Status: troque somente o título da coluna.",
                        "Renomear o título da coluna sem alterar valores das células.",
                        List.of("affectedPaths=columns[].header"))));
        var valueMapping = new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                "column.valueMapping.set",
                "column.valueMapping.set",
                List.of("Mapear valores exibidos nas células"),
                List.of(),
                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                        "Mostre Sim e Não no lugar dos valores das células.",
                        "Mapear valores sem renomear a coluna.",
                        List.of("affectedPaths=columns[].valueMapping"))));
        var capabilities = new AgenticAuthoringComponentCapabilitiesResult(
                "0",
                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                        "praxis-table",
                        "0",
                        List.of(header, valueMapping))));

        JsonNode bundle = AgenticAuthoringContextBundle.create(
                objectMapper,
                request,
                request.userPrompt(),
                objectMapper.createObjectNode(),
                new AgenticAuthoringTarget(
                        "employees-table",
                        "praxis-table",
                        "/api/employees",
                        "/schemas/filtered?resource=employees",
                        null,
                        null),
                List.of(),
                capabilities,
                "");

        JsonNode projected = bundle.path("componentContext")
                .path("componentCapabilities")
                .path("catalogs")
                .get(0)
                .path("capabilities");
        assertThat(projected)
                .extracting(capability -> capability.path("changeKind").asText())
                .containsExactly("column.header.set", "column.valueMapping.set");
    }

    private JsonNode tableComponentContext(String prompt) {
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                prompt,
                "page-builder-ia",
                "praxis-table",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-5-mini",
                "test-key");

        JsonNode bundle = AgenticAuthoringContextBundle.create(
                objectMapper,
                request,
                request.userPrompt(),
                objectMapper.createObjectNode(),
                new AgenticAuthoringTarget(
                        "employees-table",
                        "praxis-table",
                        "/api/employees",
                        "/schemas/filtered?resource=employees",
                        null,
                        null),
                List.of(),
                new AgenticAuthoringComponentCapabilitiesService().listCapabilities(),
                "");
        return bundle.path("componentContext");
    }

    private List<String> componentIds(JsonNode catalogs) {
        return catalogs.findValuesAsText("componentId");
    }

    private AgenticAuthoringComponentCapabilitiesResult componentCapabilities() {
        return new AgenticAuthoringComponentCapabilitiesResult(
                "0",
                List.of(
                        catalog("praxis-chart", "chart.create", "create_chart", "grafico", "indicador"),
                        catalog("praxis-table", "table.create", "create_table", "tabela", "detalhes"),
                        catalog("praxis-dynamic-form", "form.create", "create_form", "formulario", "acoes"),
                        catalog("praxis-tabs", "tab.add", "layout_tabs", "abas", "secoes"),
                        catalog("praxis-expansion", "panel.add", "layout_expansion", "accordion", "acordeon"),
                        catalog("praxis-stepper", "step.add", "layout_stepper", "wizard", "etapas"),
                        catalog("praxis-settings-panel", "setting.add", "configure_settings", "preferencias", "configuracoes"),
                        catalog("praxis-files-upload", "file.upload", "upload_files", "arquivo", "upload")));
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example(String prompt) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                prompt,
                "Alterar uma coluna.",
                List.of());
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog(
            String componentId,
            String capabilityId,
            String changeKind,
            String firstTerm,
            String secondTerm) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                componentId,
                "0",
                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                        capabilityId,
                        changeKind,
                        List.of(firstTerm, secondTerm),
                        List.of(),
                        List.of())));
    }
}
