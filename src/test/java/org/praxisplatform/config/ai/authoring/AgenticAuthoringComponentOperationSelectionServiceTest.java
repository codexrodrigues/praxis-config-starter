package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringComponentOperationSelectionServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock private AiProviderManagementService provider;

    @Test
    void acceptsOneOrTwoDeclaredOperationsInSemanticOrder() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("column.renderer.set"), selection("column.renderer.set", "column.visibility.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").operationIds())
                .containsExactly("column.renderer.set");
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").operationIds())
                .containsExactly("column.renderer.set", "column.visibility.set");
    }

    @Test
    void rejectsUndeclaredOrOversizedSelectionsBeforeAnyPlanCanCompile() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("unknown.operation"), selection("column.renderer.set", "column.visibility.set", "a", "b", "c", "d", "e"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").selected()).isFalse();
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").selected()).isFalse();
        verify(provider, org.mockito.Mockito.times(2)).generateJson(anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsSelectionsThatDoNotCoverEveryDecomposedGoal() throws Exception {
        JsonNode selection = selection("column.renderer.set");
        ((com.fasterxml.jackson.databind.node.ObjectNode) selection.path("goals").get(0))
                .withArray("operationIds")
                .add("column.visibility.set");
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection);
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);

        var result = service.select(
                request(),
                "praxis-table",
                objectMapper.createObjectNode(),
                manifest(),
                "t",
                "u",
                "e");

        assertThat(result.selected()).isFalse();
        assertThat(result.clarificationReason())
                .isEqualTo("component-operation-selection-goal-coverage-mismatch");
    }

    @Test
    void doesNotCarryAPreviousProviderFailureIntoTheNextSelection() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("transient provider failure"))
                .thenReturn(selection("column.renderer.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").operationIds())
                .containsExactly("column.renderer.set");
    }

    @Test
    void groundsMultiOperationSelectionWithCanonicalSemanticEvidence() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("column.renderer.set", "column.visibility.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);

        var result = service.select(
                new AgenticAuthoringPlanRequest(
                        "Na coluna Código, mostre a foto antes do código e não deixe Foto separada.",
                        "openai",
                        "gpt",
                        "key",
                        incorrectPrimaryIntent()),
                "praxis-table",
                objectMapper.createObjectNode(),
                semanticManifest(),
                "t",
                "u",
                "e");

        assertThat(result.operationIds())
                .containsExactly("column.renderer.set", "column.visibility.set");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(provider).generateJson(prompt.capture(), any(), any(), anyString(), anyString(), anyString());
        assertThat(prompt.getValue())
                .contains(
                        "userPrompt is the authoritative delta for this turn.",
                        "The resolved intent describes that delta's",
                        "only when the current userPrompt explicitly asks for another",
                        "operationCards are a governed semantic-retrieval shortlist in stable canonical catalog order",
                        "Do not select an operation that moves sibling surfaces to order elements inside rendered content.",
                        "A current column label used only to",
                        "Never repeat an operation from a prior",
                        "\"semanticEffect\":\"Compose media and text from multiple fields inside one cell.\"",
                        "\"affectedPaths\":[\"columns[].renderer\"]",
                        "\"inputConcepts\":[\"type\",\"compose\"]",
                        "\"request\":\"Mostrar foto e código na mesma célula.\"",
                        "\"semanticEffect\":\"Hide the standalone source column without deleting it.\"",
                        "\"request\":\"Não mostrar Foto como coluna separada.\"",
                        "\"operationBundles\":[{\"semanticIntent\":\"Mostrar foto e código na mesma célula.\"",
                        "\"operationIds\":[\"column.renderer.set\",\"column.visibility.set\"]",
                        "\"semanticCounterExamples\":[{\"request\":\"This negative example must not ground selection.\"")
                .doesNotContain(
                        "\"resolvedIntent\"",
                        "\"primaryCandidateOperationId\"",
                        "\"changeKind\"");
    }

    @Test
    void projectsTheMostRelevantCanonicalExamplesInsteadOfTheFirstCatalogEntries() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("column.renderer.composeItem.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        JsonNode manifest = objectMapper.readTree("""
                {
                  "operations": [
                    {
                      "operationId": "column.renderer.composeItem.set",
                      "title": "Refinar item composto",
                      "description": "Altera somente um item já materializado na célula composta.",
                      "targetKind": "renderer",
                      "affectedPaths": ["columns[].renderer.compose.items[]"]
                    }
                  ],
                  "examples": [
                    {
                      "request": "Aumente um pouco somente a foto dessa célula composta.",
                      "operationId": "column.renderer.composeItem.set",
                      "target": "codigo",
                      "isPositive": true
                    },
                    {
                      "request": "Diminua só essa foto e preserve o restante.",
                      "operationId": "column.renderer.composeItem.set",
                      "target": "codigo",
                      "isPositive": true
                    },
                    {
                      "request": "Deixe somente a foto dessa célula composta circular.",
                      "operationId": "column.renderer.composeItem.set",
                      "target": "codigo",
                      "isPositive": true
                    },
                    {
                      "request": "Deixe a foto circular... não, cancela essa parte: quero quadrada com cantos arredondados",
                      "operationId": "column.renderer.composeItem.set",
                      "target": "codigo",
                      "isPositive": true
                    }
                  ]
                }
                """);

        var result = service.select(
                new AgenticAuthoringPlanRequest(
                        "Deixe a foto circular... não, cancela essa parte: quero quadrada com cantos arredondados",
                        "openai",
                        "gpt",
                        "key",
                        null,
                        intentForOperation("column.renderer.composeItem.set"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        objectMapper.createObjectNode()),
                "praxis-table",
                objectMapper.createObjectNode(),
                manifest,
                "t",
                "u",
                "e");

        assertThat(result.operationIds()).containsExactly("column.renderer.composeItem.set");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(provider).generateJson(prompt.capture(), any(), any(), anyString(), anyString(), anyString());
        assertThat(prompt.getValue())
                .contains(
                        "Deixe a foto circular... não, cancela essa parte: quero quadrada com cantos arredondados",
                        "Deixe somente a foto dessa célula composta circular.")
                .doesNotContain(
                        "Aumente um pouco somente a foto dessa célula composta.",
                        "Diminua só essa foto e preserve o restante.");
    }

    @Test
    void keepsPromptGroundedAlternativesWhenTheResolvedRefinementRequiresMissingState() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("column.renderer.set", "column.visibility.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        JsonNode currentConfig = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "id", "header": "Código" },
                    { "field": "avatarUrl", "header": "Foto" }
                  ]
                }
                """);

        var result = service.select(
                new AgenticAuthoringPlanRequest(
                        "Na mesma linha, e a Foto não deve continuar separada: no Código coloca primeiro a foto e depois o código.",
                        "openai",
                        "gpt",
                        "key",
                        null,
                        intentForOperation("column.renderer.composeLayout.set"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        objectMapper.createObjectNode()),
                "praxis-table",
                currentConfig,
                semanticManifest(),
                "t",
                "u",
                "e");

        assertThat(result.operationIds())
                .containsExactly("column.renderer.set", "column.visibility.set");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(provider).generateJson(prompt.capture(), any(), any(), anyString(), anyString(), anyString());
        assertThat(prompt.getValue()).contains(
                "\"operationId\":\"column.renderer.composeLayout.set\"",
                "\"operationId\":\"column.renderer.set\"",
                "\"operationId\":\"column.visibility.set\"",
                "\"stateRequirements\":[\"target-exists\",\"renderer-compose-exists\"]",
                "Reject an operation whose declared state requirement is not satisfied by currentConfig",
                "select the declared operation that creates");
    }

    @Test
    void usesServerGroundedCandidatesAsABoundedShortlistWithoutTrustingCandidatePayloadFields() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("column.header.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        var contextHints = objectMapper.createObjectNode();
        var candidates = contextHints.putObject("authoringEvidence").putArray("operationCandidates");
        candidates.addObject()
                .put("id", "column.valueMapping.set")
                .put("title", "FORGED HEADER")
                .put("semanticEffect", "FORGED");
        candidates.addObject().put("id", "unknown.operation");

        var result = service.select(
                new AgenticAuthoringPlanRequest(
                        "Ativo vira Status.",
                        "openai",
                        "gpt",
                        "key",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        contextHints),
                "praxis-table",
                objectMapper.createObjectNode(),
                shortlistManifest(),
                "t",
                "u",
                "e");

        assertThat(result.operationIds()).containsExactly("column.header.set");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(provider).generateJson(prompt.capture(), any(), any(), anyString(), anyString(), anyString());
        assertThat(prompt.getValue())
                .contains(
                        "\"operationId\":\"column.header.set\"",
                        "\"operationId\":\"column.valueMapping.set\"",
                        "\"semanticEffect\":\"Rename the structural column header without changing cell values.\"",
                        "\"semanticCounterExamples\":[{\"request\":\"Ativo vira Status sem mudar os valores.\"")
                .doesNotContain(
                        "\"operationId\":\"column.renderer.set\"",
                        "unknown.operation",
                        "FORGED");
        assertThat(prompt.getValue().indexOf("\"operationId\":\"column.header.set\""))
                .isLessThan(prompt.getValue().indexOf("\"operationId\":\"column.valueMapping.set\""));
    }

    @Test
    void restoresSemanticOperationNeighborhoodWhenRetrievalOmitsCompoundComposeRefinements() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection(
                        "column.renderer.composeLayout.set",
                        "column.renderer.composeItem.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        var contextHints = objectMapper.createObjectNode();
        contextHints.putObject("authoringEvidence")
                .putArray("operationCandidates")
                .addObject()
                .put("id", "column.visibility.set");

        var result = service.select(
                new AgenticAuthoringPlanRequest(
                        "Pensando melhor, ajuste o composto sem alterar o restante.",
                        "openai",
                        "gpt",
                        "key",
                        null,
                        intentForOperation("column.renderer.composeLayout.set"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        contextHints),
                "praxis-table",
                objectMapper.createObjectNode(),
                semanticContinuityManifest(),
                "t",
                "u",
                "e");

        assertThat(result.operationIds()).containsExactlyInAnyOrder(
                "column.renderer.composeLayout.set",
                "column.renderer.composeItem.set");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(provider).generateJson(prompt.capture(), any(), any(), anyString(), anyString(), anyString());
        assertThat(prompt.getValue())
                .contains(
                        "\"operationId\":\"column.renderer.composeLayout.set\"",
                        "\"operationId\":\"column.renderer.composeItem.set\"",
                        "\"operationId\":\"column.visibility.set\"")
                .doesNotContain(
                        "\"operationId\":\"column.renderer.set\"",
                        "\"operationId\":\"column.header.set\"");
    }

    @Test
    void restoresManifestDeclaredBundleSiblingForPairedStatusPresentation() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("column.renderer.set", "column.conditionalRenderer.add"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        var contextHints = objectMapper.createObjectNode();
        contextHints.putObject("authoringEvidence")
                .putArray("operationCandidates")
                .addObject()
                .put("id", "column.conditionalRenderer.add");

        var result = service.select(
                new AgenticAuthoringPlanRequest(
                        "Aplique a apresentação sem alterar o restante.",
                        "openai",
                        "gpt",
                        "key",
                        null,
                        intentForOperation("column.conditionalRenderer.add"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        contextHints),
                "praxis-table",
                objectMapper.createObjectNode(),
                statusPresentationBundleManifest(),
                "t",
                "u",
                "e");

        assertThat(result.operationIds())
                .containsExactlyInAnyOrder("column.renderer.set", "column.conditionalRenderer.add");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(provider).generateJson(prompt.capture(), any(), any(), anyString(), anyString(), anyString());
        assertThat(prompt.getValue())
                .contains(
                        "\"operationId\":\"column.renderer.set\"",
                        "\"operationId\":\"column.conditionalRenderer.add\"",
                        "\"operationBundles\":[{\"semanticIntent\":\"Status com base verde e exceção vermelha.\"",
                        "\"operationIds\":[\"column.renderer.set\",\"column.conditionalRenderer.add\"]");
    }

    @Test
    void treatsTheCurrentPromptAsTheDeltaWithoutProjectingHistoricalObjectives() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("column.renderer.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        var currentConfig = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "id",
                      "renderer": {
                        "type": "compose",
                        "compose": {
                          "items": [
                            { "type": "avatar", "avatar": { "srcField": "avatarUrl", "size": 32 } },
                            { "type": "value", "field": "id" }
                          ]
                        }
                      }
                    }
                  ]
                }
                """);

        var result = service.select(
                new AgenticAuthoringPlanRequest(
                        "Aumenta um pouco essa foto aí, sem bagunçar o resto.",
                        "openai",
                        "gpt",
                        "key",
                        intentWithHistoricalObjective()),
                "praxis-table",
                currentConfig,
                semanticManifest(),
                "t",
                "u",
                "e");

        assertThat(result.operationIds()).containsExactly("column.renderer.set");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(provider).generateJson(prompt.capture(), any(), any(), anyString(), anyString(), anyString());
        assertThat(prompt.getValue())
                .contains(
                        "userPrompt is the authoritative delta for this turn.",
                        "\"userPrompt\":\"Aumenta um pouco essa foto aí, sem bagunçar o resto.\"",
                        "\"size\":32")
                .doesNotContain(
                        "Renomear Ativo para Status e criar chip vermelho",
                        "Criar a tabela original de funcionários",
                        "\"userGoal\"",
                        "\"activeObjective\"");
    }

    private AgenticAuthoringPlanRequest request() { return new AgenticAuthoringPlanRequest("compose", "openai", "gpt", "key"); }
    private AgenticAuthoringIntentResolutionResult incorrectPrimaryIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                "column.order.set",
                "semantic-manifest",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget("funcionarios-table", "praxis-table", "", "", "", ""),
                null,
                java.util.List.of(),
                new AgenticAuthoringGateResult("component-edit", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                objectMapper.createObjectNode());
    }
    private AgenticAuthoringIntentResolutionResult intentWithHistoricalObjective() {
        var decision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-current",
                "modify",
                "table",
                "column.renderer.set",
                null,
                null,
                null,
                null,
                false,
                "",
                "",
                "",
                "conversation",
                "turn",
                "Renomear Ativo para Status e criar chip vermelho. Aumentar a foto atual.",
                "Criar a tabela original de funcionários",
                "modify:table:column.renderer.set",
                "table",
                null,
                null,
                "decision-previous",
                "",
                0.9d);
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                "column.renderer.set",
                "semantic-manifest",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget("funcionarios-table", "praxis-table", "", "", "", ""),
                null,
                java.util.List.of(),
                new AgenticAuthoringGateResult("component-edit", "eligible", java.util.List.of()),
                "Aumenta um pouco essa foto aí, sem bagunçar o resto.",
                "",
                null,
                null,
                java.util.List.of(),
                null,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null,
                decision);
    }
    private AgenticAuthoringIntentResolutionResult intentForOperation(String operationKind) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                operationKind,
                "semantic-manifest",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget("funcionarios-table", "praxis-table", "", "", "", ""),
                null,
                java.util.List.of(),
                new AgenticAuthoringGateResult("component-edit", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                objectMapper.createObjectNode());
    }
    private JsonNode manifest() throws Exception { return objectMapper.readTree("""
        {"operations":[{"operationId":"column.renderer.set","title":"Renderer"},{"operationId":"column.visibility.set","title":"Visibility"}]}
        """); }
    private JsonNode semanticManifest() throws Exception { return objectMapper.readTree("""
        {
          "operations": [
            {
              "operationId": "column.renderer.set",
              "title": "Definir renderizador",
              "description": "Compose media and text from multiple fields inside one cell.",
              "scope": "column",
              "targetKind": "renderer",
              "inputSchema": {
                "type": "object",
                "properties": {
                  "type": { "type": "string" },
                  "compose": { "type": "object" }
                }
              },
              "affectedPaths": ["columns[].renderer"]
            },
            {
              "operationId": "column.visibility.set",
              "title": "Definir visibilidade",
              "description": "Hide the standalone source column without deleting it.",
              "scope": "column",
              "targetKind": "column",
              "inputSchema": {
                "type": "object",
                "properties": { "visible": { "type": "boolean" } }
              },
              "affectedPaths": ["columns[].visible"]
            },
            {
              "operationId": "column.renderer.composeLayout.set",
              "title": "Refinar layout composto existente",
              "description": "Change direction only when a compose renderer already exists.",
              "scope": "column",
              "targetKind": "renderer",
              "inputSchema": {
                "type": "object",
                "properties": { "direction": { "type": "string" } }
              },
              "preconditions": ["target-exists"],
              "validators": ["renderer-compose-exists"],
              "affectedPaths": ["columns[].renderer.compose.layout"]
            }
          ],
          "examples": [
            {
              "id": "compose-photo-code",
              "request": "Mostrar foto e código na mesma célula.",
              "operationId": "column.renderer.set",
              "target": "codigo",
              "isPositive": true
            },
            {
              "id": "negative-renderer",
              "request": "This negative example must not ground selection.",
              "operationId": "column.renderer.set",
              "isPositive": false
            },
            {
              "id": "hide-photo-source",
              "request": "Não mostrar Foto como coluna separada.",
              "operationId": "column.visibility.set",
              "target": "foto",
              "isPositive": true
            },
            {
              "id": "compose-photo-code-hide-source",
              "request": "Mostrar foto e código na mesma célula.",
              "operationId": "column.visibility.set",
              "target": "foto",
              "isPositive": true
            }
          ]
        }
        """); }
    private JsonNode shortlistManifest() throws Exception { return objectMapper.readTree("""
        {
          "operations": [
            {
              "operationId": "column.renderer.set",
              "title": "Renderer",
              "description": "Render rich cell content."
            },
            {
              "operationId": "column.header.set",
              "title": "Rename header",
              "description": "Rename the structural column header without changing cell values.",
              "inputSchema": { "properties": { "header": { "type": "string" } } },
              "affectedPaths": ["columns[].header"]
            },
            {
              "operationId": "column.valueMapping.set",
              "title": "Map values",
              "description": "Map values rendered inside cells without changing the structural header.",
              "inputSchema": { "properties": { "valueMapping": { "type": "object" } } },
              "affectedPaths": ["columns[].valueMapping"]
            }
          ],
          "examples": [
            {
              "request": "Ativo vira Status.",
              "operationId": "column.header.set",
              "target": "ativo",
              "isPositive": true
            },
            {
              "request": "Mostre Sim e Não no lugar dos valores.",
              "operationId": "column.header.set",
              "isPositive": false
            },
            {
              "request": "Mostre Sim e Não no lugar dos valores.",
              "operationId": "column.valueMapping.set",
              "target": "ativo",
              "isPositive": true
            },
            {
              "request": "Ativo vira Status sem mudar os valores.",
              "operationId": "column.valueMapping.set",
              "isPositive": false
            }
          ]
        }
        """); }
    private JsonNode semanticContinuityManifest() throws Exception { return objectMapper.readTree("""
        {
          "operations": [
            {
              "operationId": "column.header.set",
              "targetKind": "column",
              "affectedPaths": ["columns[].header"]
            },
            {
              "operationId": "column.visibility.set",
              "targetKind": "column",
              "affectedPaths": ["columns[].visible"]
            },
            {
              "operationId": "column.renderer.set",
              "targetKind": "renderer",
              "affectedPaths": ["columns[].renderer"]
            },
            {
              "operationId": "column.renderer.composeLayout.set",
              "targetKind": "renderer",
              "affectedPaths": ["columns[].renderer.compose.layout"]
            },
            {
              "operationId": "column.renderer.composeItem.set",
              "targetKind": "renderer",
              "affectedPaths": ["columns[].renderer.compose.items[]"]
            }
          ]
        }
        """); }
    private JsonNode statusPresentationBundleManifest() throws Exception { return objectMapper.readTree("""
        {
          "operations": [
            {
              "operationId": "column.renderer.set",
              "targetKind": "renderer",
              "affectedPaths": ["columns[].renderer"]
            },
            {
              "operationId": "column.conditionalRenderer.add",
              "targetKind": "column",
              "affectedPaths": ["columns[].conditionalRenderers[]"]
            },
            {
              "operationId": "column.visibility.set",
              "targetKind": "column",
              "affectedPaths": ["columns[].visible"]
            }
          ],
          "examples": [
            {
              "request": "Status com base verde e exceção vermelha.",
              "operationId": "column.renderer.set",
              "target": "ativo",
              "isPositive": true
            },
            {
              "request": "Status com base verde e exceção vermelha.",
              "operationId": "column.conditionalRenderer.add",
              "target": "ativo",
              "isPositive": true
            }
          ]
        }
        """); }
    private JsonNode selection(String... ids) {
        var root = objectMapper.createObjectNode(); root.put("schemaVersion", AgenticAuthoringComponentOperationSelectionService.SCHEMA_VERSION);
        root.put("componentId", "praxis-table");
        var goals = root.putArray("goals");
        for (String id : ids) {
            var goal = goals.addObject();
            goal.put("description", "Materialize " + id);
            goal.put("targetConcept", "praxis-table");
            goal.putArray("operationIds").add(id);
        }
        root.put("requiresClarification", false); root.put("clarificationReason", "");
        var selected = root.putArray("selectedOperationIds"); for (String id : ids) selected.add(id); return root;
    }
}
