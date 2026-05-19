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
        assertThat(componentContext.path("componentCapabilities").path("totalCatalogs").asInt()).isEqualTo(8);
        assertThat(componentContext.path("componentCapabilities").path("includedCatalogs").asInt())
                .isLessThanOrEqualTo(6);
        assertThat(componentIds(componentContext.path("componentCapabilities").path("catalogs")))
                .contains("praxis-expansion")
                .doesNotContain("praxis-settings-panel", "praxis-files-upload");
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
