package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

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
    void resolvesCreateChartDrillDownForQuickstartPayrollAnalytics() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Use um chart para criar drill down da folha por departamento",
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
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_chart_drilldown");
        assertThat(result.authoringProfile()).isEqualTo("generic-page-change");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento&operation=get&schemaType=response");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void asksForConfirmationWhenUserAsksBestWayToVisualizePayrollInformation() {
        List<String> prompts = List.of(
                "qual e melhor forma de visualizar informacoes sobre a folha de pagamento?",
                "Como visualizar informacoes da folha de pagamento por departamento?",
                "Quero analisar a folha de pagamento por departamento antes de criar a tela",
                "Me ajude a escolher um dashboard para folha de pagamento",
                "Como visualizar a folha?",
                "Quero visualizar a folha por departamento em um dashboard",
                "qual e melhor forma de ver pagamentos?",
                "Mostre uma visao de pagamento por departamento");

        for (String prompt : prompts) {
            AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                    prompt,
                    "praxis-ui-angular",
                    "praxis-dynamic-page-builder",
                    "/page-builder-ia",
                    objectMapper.createObjectNode(),
                    null,
                    null,
                    null,
                    null));

            assertThat(result.valid()).isFalse();
            assertThat(result.operationKind()).isEqualTo("explore");
            assertThat(result.artifactKind()).isEqualTo("dashboard");
            assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
            assertThat(result.selectedCandidate().resourcePath())
                    .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
            assertThat(result.selectedCandidate().operation()).isEqualTo("get");
            assertThat(result.gate().status()).isEqualTo("clarification_required");
            assertThat(result.failureCodes()).containsExactly("intent-confirmation-required");
            assertThat(result.clarificationQuestions())
                    .containsExactly("Posso criar um dashboard de folha de pagamento com grafico por departamento, indicadores e detalhamento?");
        }
    }

    @Test
    void resolvesConfirmedPayrollVisualizationAsDashboardCreation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Sim, crie um dashboard para visualizar informacoes sobre a folha de pagamento",
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
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento&operation=get&schemaType=response");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void suggestsApproximatePayrollEndpointOptionsWhenPromptHasOnlyBareDomainTerm() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("unknown");
        assertThat(result.artifactKind()).isEqualTo("unknown");
        assertThat(result.changeKind()).isEqualTo("unknown");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "/api/human-resources/folhas-pagamento");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::operation)
                .containsExactly("get", "get");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes())
                .containsExactly(
                        "intent-operation-unknown",
                        "intent-artifact-unknown",
                        "resource-candidate-ambiguous");
        assertThat(result.clarificationQuestions())
                .contains(
                        "O que voce quer fazer com esse tema: visualizar, criar, alterar ou abrir um detalhe?",
                        "Voce quer criar ou alterar formulario, tabela, dashboard, stepper ou outro componente?",
                        "Encontrei recursos proximos: /api/human-resources/vw-analytics-folha-pagamento (GET), /api/human-resources/folhas-pagamento (GET). Qual deles voce quer usar?");
    }

    @Test
    void resolvesPayrollTableFollowUpToOperationalPayrollCollectionBeforeConfirmation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quero visualizar folhas de pagamento em uma tabela",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.changeKind()).isEqualTo("recommend_table_visualization");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("intent-confirmation-required");
        assertThat(result.clarificationQuestions())
                .containsExactly("Posso criar uma tabela operacional de folhas de pagamento usando /api/human-resources/folhas-pagamento?");
    }

    @Test
    void resolvesDirectPayrollTableCreationWhenPromptHasEnoughContext() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma tabela operacional de folhas de pagamento",
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
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.clarificationQuestions()).isEmpty();
    }

    @Test
    void asksForBreakdownWhenPayrollDashboardCreationLacksAnalyticsDimension() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard de folha de pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("analytics-breakdown-required");
        assertThat(result.clarificationQuestions())
                .containsExactly("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
    }

    @Test
    void metadataBackedPayrollDashboardIgnoresTechnicalSchemaEndpointsAndAsksForBreakdown() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento/schemas",
                        "GET",
                        "human-resources,folha,pagamento",
                        "Schema tecnico de folhas de pagamento",
                        "Endpoint auxiliar de schema",
                        "folhasPagamentoSchemas",
                        null,
                        null,
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento",
                        "GET",
                        "human-resources,folha,pagamento",
                        "Lista folhas de pagamento",
                        "Consulta operacional de folhas de pagamento",
                        "listFolhasPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "human-resources,analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica para dashboards de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard de folha de pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .doesNotContain("/api/human-resources/folhas-pagamento/schemas");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("analytics-breakdown-required");
    }

    @Test
    void metadataBackedBarePayrollTermSuggestsOnlyCanonicalRenderableResources() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata("/api/human-resources/folhas-pagamento/{id}", "GET", "folha,pagamento", "Busca folha por id", null, "getFolha", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/all", "GET", "folha,pagamento", "Lista todas as folhas", null, "allFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/by-ids", "GET", "folha,pagamento", "Busca folhas por ids", null, "byIdsFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/options/filter", "GET", "folha,pagamento", "Opcoes de filtro", null, "filterOptionsFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/filter", "GET", "folha,pagamento", "Filtro de folhas", null, "filterFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/filter/cursor", "GET", "folha,pagamento", "Filtro cursor de folhas", null, "filterCursorFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/batch", "GET", "folha,pagamento", "Operacao batch de folhas", null, "batchFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/locate", "GET", "folha,pagamento", "Localizacao de folha", null, "locateFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento", "POST", "folha,pagamento", "Cria folha de pagamento", "Endpoint de escrita de folhas de pagamento", "createFolhaPagamento", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento", "GET", "folha,pagamento", "Folhas de pagamento", "Recurso operacional de folhas de pagamento", "listFolhasPagamento", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/vw-analytics-folha-pagamento", "GET", "analytics,folha,pagamento", "Analytics de folha de pagamento", "Visao analitica de folha de pagamento", "listVwAnalyticsFolhaPagamento", null, null, "[]", "{}", null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "/api/human-resources/folhas-pagamento");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes())
                .containsExactly(
                        "intent-operation-unknown",
                        "intent-artifact-unknown",
                        "resource-candidate-ambiguous");
    }

    @Test
    void discoversCandidateFromApiMetadataWhenEndpointIsNotInKnownQuickstartFallback() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/beneficios/schemas",
                        "GET",
                        "human-resources,beneficios",
                        "Schema tecnico de beneficios",
                        "Endpoint auxiliar de schema que nao deve ser tratado como recurso renderizavel",
                        "beneficiosSchemas",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/beneficios",
                        "GET",
                        "human-resources,beneficios",
                        "Lista beneficios corporativos",
                        "Consulta beneficios disponiveis para colaboradores",
                        "listBeneficios",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma tabela de beneficios",
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
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/beneficios");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/beneficios&operation=get&schemaType=response");
        assertThat(result.selectedCandidate().evidence()).contains("api-metadata");
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
    void summarizesExistingFormFieldsForAgenticEdits() {
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
        ObjectNode config = inputs.putObject("config");
        var fieldMetadata = config.putArray("fieldMetadata");
        ObjectNode nome = fieldMetadata.addObject();
        nome.put("name", "nome");
        nome.put("label", "Nome completo do colaborador");
        nome.put("controlType", "text");
        ObjectNode observacaoInterna = fieldMetadata.addObject();
        observacaoInterna.put("name", "observacaoInterna");
        observacaoInterna.put("label", "Observacao interna");
        observacaoInterna.put("controlType", "textarea");
        observacaoInterna.put("source", "local");
        observacaoInterna.put("transient", true);
        observacaoInterna.put("submitPolicy", "omit");
        var sections = config.putArray("sections");
        ObjectNode section = sections.addObject();
        var rows = section.putArray("rows");
        ObjectNode row = rows.addObject();
        var columns = row.putArray("columns");
        ObjectNode column = columns.addObject();
        column.putArray("fields").add("observacaoInterna");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Remova o campo observacaoInterna do formulario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        var formSummary = result.currentPageSummary().path("formWidgets").get(0);
        assertThat(formSummary.path("fieldCount").asInt()).isEqualTo(2);
        assertThat(formSummary.path("localFieldCount").asInt()).isEqualTo(1);
        assertThat(formSummary.path("fieldNames")).extracting(JsonNode::asText)
                .containsExactly("nome", "observacaoInterna");
        assertThat(formSummary.path("localFieldNames")).extracting(JsonNode::asText)
                .containsExactly("observacaoInterna");
        assertThat(formSummary.path("serverBackedOverrideNames")).extracting(JsonNode::asText)
                .containsExactly("nome");
        assertThat(formSummary.path("layoutFieldNames")).extracting(JsonNode::asText)
                .containsExactly("observacaoInterna");
        assertThat(formSummary.path("fieldMetadata").get(0).path("label").asText())
                .isEqualTo("Nome completo do colaborador");
        assertThat(formSummary.path("fieldMetadata").get(1).path("source").asText()).isEqualTo("local");
        assertThat(formSummary.path("fieldMetadata").get(1).path("transient").asBoolean()).isTrue();
        assertThat(formSummary.path("fieldMetadata").get(1).path("submitPolicy").asText()).isEqualTo("omit");
    }

    @Test
    void resolvesModifyRelabelBeforeGenericFieldAddition() {
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
                "Renomeie o campo nome para Nome completo do colaborador",
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
        assertThat(result.changeKind()).isEqualTo("rename_or_relabel");
        assertThat(result.target().widgetKey()).isEqualTo("funcionarios-form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void resolvesRemoveFieldAgainstExistingDynamicForm() {
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
                "Remova o campo observacaoInterna do formulario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("remove");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("remove_field");
        assertThat(result.authoringProfile()).isEqualTo("create-minimal-form");
        assertThat(result.target().widgetKey()).isEqualTo("funcionarios-form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
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
