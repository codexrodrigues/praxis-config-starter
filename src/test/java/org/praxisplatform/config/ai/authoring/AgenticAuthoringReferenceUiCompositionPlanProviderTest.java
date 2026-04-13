package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringReferenceUiCompositionPlanProviderTest {

    private final AgenticAuthoringReferenceUiCompositionPlanProvider provider =
            new AgenticAuthoringReferenceUiCompositionPlanProvider(new ObjectMapper());

    @Test
    void returnsDepartmentMasterDetailUiCompositionPlanForSupportedPrompt() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma tela master detail de departamentos com funcionarios e folha",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("version").asText()).isEqualTo("1.0");
        assertThat(plan.path("kind").asText()).isEqualTo("praxis.ui-composition-plan");
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("master-detail-dashboard");
        assertThat(plan.path("widgets")).hasSize(4);
        assertThat(plan.path("widgets").get(0).path("inputs").path("config").path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/departamentos");
        assertThat(plan.path("widgets").get(1).path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(plan.path("widgets").get(2).path("inputs").path("config").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(plan.path("bindings")).hasSize(5);
        assertThat(result.orElseThrow().compiledFormPatch().path("patch").isObject()).isTrue();
    }

    @Test
    void ignoresPromptsOutsideTheControlledReferenceComposition() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um formulario simples para chamado",
                null,
                null,
                null));

        assertThat(result).isEmpty();
    }
}
