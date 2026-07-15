package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringComponentEditPlanServiceTest {

    @Mock
    private AiProviderManagementService providerManagementService;

    @Mock
    private AgenticAuthoringManifestService manifestService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authorsStructuredPlanAndCompilesWithTheSameTransientContext() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-chart",
                  "manifestVersion": "1.0.0",
                  "operations": [{
                    "operationId": "crossFilter.configure",
                    "title": "Configure cross-filter",
                    "inputSchema": {
                      "type": "object",
                      "required": ["action", "target"],
                      "properties": {
                        "action": { "const": "filter-widget" },
                        "target": { "type": "string" }
                      }
                    }
                  }],
                  "examples": [{
                    "id": "configure-cross-filter",
                    "operationId": "crossFilter.configure",
                    "params": { "action": "filter-widget", "target": "employeesTable" },
                    "isPositive": true
                  }]
                }
                """);
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-chart",
                  "operations": [{
                    "operationId": "crossFilter.configure",
                    "input": { "action": "filter-widget", "target": "employeesTable" }
                  }]
                }
                """);
        JsonNode config = objectMapper.readTree("""
                { "chartDocument": { "version": "0.1.0", "kind": "bar" } }
                """);
        JsonNode validationContext = objectMapper.readTree("""
                {
                  "availableTargets": [{
                    "id": "employeesTable",
                    "actions": ["filter-widget"],
                    "events": ["crossFilter"]
                  }]
                }
                """);
        JsonNode compiledPatch = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "proposedConfig": {
                    "chartDocument": {
                      "version": "0.1.0",
                      "kind": "bar",
                      "events": {
                        "crossFilter": { "action": "filter-widget", "target": "employeesTable" }
                      }
                    }
                  }
                }
                """);
        when(manifestService.getManifest("praxis-chart")).thenReturn(manifest);
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local"))).thenReturn(plan);
        when(manifestService.compilePatch(eq("praxis-chart"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(true, java.util.List.of(), java.util.List.of(), compiledPatch));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(
                        providerManagementService,
                        manifestService,
                        objectMapper,
                        9);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest("Conecte o filtro ao quadro de colaboradores", "openai", "gpt-5", "secret"),
                "praxis-chart",
                config,
                validationContext,
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.compiledPatch().path("proposedConfig").path("chartDocument")
                .path("events").path("crossFilter").path("target").asText()).isEqualTo("employeesTable");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiJsonSchema> schema = ArgumentCaptor.forClass(AiJsonSchema.class);
        ArgumentCaptor<AiCallConfig> callConfig = ArgumentCaptor.forClass(AiCallConfig.class);
        verify(providerManagementService).generateJson(
                prompt.capture(), schema.capture(), callConfig.capture(), eq("tenant"), eq("user"), eq("local"));
        assertThat(prompt.getValue())
                .contains("Never route intent by keywords or regex")
                .contains("crossFilter.configure")
                .contains("employeesTable")
                .contains("transientValidationContext");
        assertThat(schema.getValue().jsonSchema())
                .contains("praxis-component-edit-plan.v1")
                .contains("crossFilter.configure");
        assertThat(callConfig.getValue().getTemperature()).isZero();
        assertThat(callConfig.getValue().getTimeoutSeconds()).isEqualTo(9);

        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> compileRequest =
                ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService).compilePatch(eq("praxis-chart"), compileRequest.capture());
        assertThat(compileRequest.getValue().config()).isEqualTo(config);
        assertThat(compileRequest.getValue().validationContext()).isEqualTo(validationContext);
        assertThat(compileRequest.getValue().plan()).isEqualTo(plan);
    }

    @Test
    void rejectsInvalidProviderEnvelopeBeforeManifestCompilation() throws Exception {
        when(manifestService.getManifest("praxis-chart")).thenReturn(objectMapper.readTree("""
                {
                  "componentId": "praxis-chart",
                  "operations": [{
                    "operationId": "crossFilter.configure",
                    "inputSchema": { "type": "object" }
                  }]
                }
                """));
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-component-edit-plan.v1",
                          "componentId": "praxis-table",
                          "operations": []
                        }
                        """));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(providerManagementService, manifestService, objectMapper);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest("Ajuste o componente", "openai", "gpt-5", "secret"),
                "praxis-chart",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .contains("component-edit-plan-component-mismatch", "component-edit-plan-operations-required");
        verify(manifestService, never()).compilePatch(any(), any());
    }
}
