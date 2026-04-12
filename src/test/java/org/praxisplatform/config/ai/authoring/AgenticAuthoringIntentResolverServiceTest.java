package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringIntentResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringIntentResolverService service =
            new AgenticAuthoringIntentResolverService(objectMapper);

    @Test
    void resolvesCreateMinimalFormForQuickstartFuncionarios() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um formulario didatico para cadastrar funcionarios",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("create_minimal_form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void resolvesModifyAddFieldAgainstExistingDynamicForm() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "funcionarios-form");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-dynamic-form");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Adicione o campo salario no formulario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("add_field");
        assertThat(result.target().widgetKey()).isEqualTo("funcionarios-form");
        assertThat(result.target().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.currentPageSummary().path("formWidgets").size()).isEqualTo(1);
    }

    @Test
    void asksClarificationWhenModifyHasNoTargetWidget() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Adicione prioridade no formulario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).contains("target-widget-required");
        assertThat(result.clarificationQuestions()).contains("Qual componente existente deve ser alterado?");
    }

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> service.resolve(new AgenticAuthoringIntentResolutionRequest(
                " ",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userPrompt must not be blank.");
    }
}
