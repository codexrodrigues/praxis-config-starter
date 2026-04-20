package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServiceContextHintsTest {

    private AiOrchestratorService service;
    private SchemaRetrievalService schemaRetrievalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        schemaRetrievalService = mock(SchemaRetrievalService.class);
        service = new AiOrchestratorService(
                mock(AiContextService.class),
                mock(AiProvider.class),
                mock(AiInteractionLogger.class),
                mock(ContextRetrievalService.class),
                schemaRetrievalService,
                mock(AiRegistryTemplateService.class),
                mock(AiRagContextService.class),
                mock(UserConfigService.class),
                objectMapper,
                mock(AiApiKeyCryptoService.class),
                mock(AiThreadService.class),
                mock(AiMessageService.class));
        ReflectionTestUtils.setField(service, "maxConfigChars", 12000);
        ReflectionTestUtils.setField(service, "maxSchemaChars", 12000);
        ReflectionTestUtils.setField(service, "maxTemplateConfigChars", 8000);
        ReflectionTestUtils.setField(service, "maxTemplateMetaChars", 4000);
        ReflectionTestUtils.setField(service, "maxCapabilitiesChars", 12000);
        ReflectionTestUtils.setField(service, "maxCapabilityNotesChars", 3000);
        ReflectionTestUtils.setField(service, "maxRuntimeMetadataChars", 4000);
        ReflectionTestUtils.setField(service, "maxRagHintsChars", 2000);
        ReflectionTestUtils.setField(service, "maxConceptsChars", 4000);
    }

    @Test
    void buildContextHintsResolvesSchemaFieldsAndSampleViaTypedJitFetch() throws Exception {
        when(schemaRetrievalService.fetchSchemaResult(any(), nullable(String.class)))
                .thenReturn(SchemaFetchResult.success(objectMapper.readTree("""
                        {
                          "properties": {
                            "name": { "type": "string" },
                            "active": { "type": "boolean" }
                          }
                        }
                        """), "http://localhost/schemas/filtered?path=/api/users"));

        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .resourcePath("/api/users")
                .build();

        Object result = ReflectionTestUtils.invokeMethod(
                service,
                "buildContextHintsFromRequest",
                null,
                request,
                null,
                null,
                List.of(30, 40),
                null,
                null,
                null);
        JsonNode hints = (JsonNode) ReflectionTestUtils.getField(result, "hints");

        assertThat(hints).isNotNull();
        JsonNode contextPack = hints.path("contextPack");
        assertThat(contextPack.path("schemaFields").isArray()).isTrue();
        assertThat(contextPack.path("schemaFields").get(0).asText()).isEqualTo("name");
        assertThat(contextPack.path("schemaFields").get(1).asText()).isEqualTo("active");
        assertThat(contextPack.path("schemaSample").path("name").asText()).isEqualTo("<text>");
        assertThat(contextPack.path("schemaSample").path("active").asBoolean()).isFalse();
        verify(schemaRetrievalService, times(2)).fetchSchemaResult(any(), nullable(String.class));
        verify(schemaRetrievalService, never()).fetchSchema(any(), nullable(String.class));
    }

    @Test
    void buildContextHintsSkipsSchemaPackWhenTypedJitFetchFails() {
        when(schemaRetrievalService.fetchSchemaResult(any(), nullable(String.class)))
                .thenReturn(SchemaFetchResult.failure(
                        SchemaFetchResult.Status.UNAVAILABLE,
                        503,
                        "http://localhost/schemas/filtered?path=/api/users",
                        "SCHEMA_PLATFORM_UNAVAILABLE",
                        "upstream unavailable"));

        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .resourcePath("/api/users")
                .build();
        List<String> warnings = new java.util.ArrayList<>();

        Object result = ReflectionTestUtils.invokeMethod(
                service,
                "buildContextHintsFromRequest",
                null,
                request,
                null,
                null,
                List.of(30, 40),
                null,
                null,
                warnings);
        JsonNode hints = (JsonNode) ReflectionTestUtils.getField(result, "hints");

        assertThat(hints).isNull();
        assertThat(warnings).containsExactly("SCHEMA_CONTEXT_DEGRADED: SCHEMA_PLATFORM_UNAVAILABLE");
        verify(schemaRetrievalService, times(2)).fetchSchemaResult(any(), nullable(String.class));
        verify(schemaRetrievalService, never()).fetchSchema(any(), nullable(String.class));
    }

    @Test
    void buildExecutionPromptPromotesAuthoringContractAsDedicatedBlock() throws Exception {
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "authoringContract": {
                    "kind": "praxis.component-authoring-context",
                    "preferredResponse": "componentEditPlan",
                    "componentEditPlan": {
                      "kind": "praxis.table.component-edit-plan",
                      "batchKind": "praxis.table.component-edit-plan.batch",
                      "schemaId": "https://praxisui.dev/schemas/table/component-edit-plan.v1.schema.json",
                      "allowedChangeKinds": ["set_column_header"]
                    }
                  }
                }
                """);
        JsonNode authoringContract = ReflectionTestUtils.invokeMethod(
                service,
                "extractAuthoringContract",
                contextHints);

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentType("table")
                .aiMode("assist")
                .componentDefinition(objectMapper.createObjectNode())
                .currentState(objectMapper.createObjectNode())
                .build();

        String prompt = ReflectionTestUtils.invokeMethod(
                service,
                "buildExecutionPrompt",
                "Renomeie a coluna status para Situação",
                context,
                objectMapper.createObjectNode(),
                List.of(),
                "",
                "",
                null,
                null,
                "N/A",
                authoringContract,
                "N/A",
                "N/A",
                null);

        assertThat(prompt).contains("CONTRATO DECLARATIVO DE AUTORIA");
        assertThat(prompt).contains("\"preferredResponse\" : \"componentEditPlan\"");
        assertThat(prompt).contains("component-edit-plan.v1.schema.json");
        assertThat(prompt).contains("retorne componentEditPlan em vez de patch livre");
    }

    @Test
    void componentEditPlanResponsePreservesDeclarativePlanForFrontendAdapter() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "kind": "praxis.table.component-edit-plan",
                    "version": "1.0",
                    "componentId": "praxis-table",
                    "changeKind": "set_column_header",
                    "capabilityPath": "columns[].header",
                    "field": "status",
                    "value": "Situação"
                  },
                  "explanation": "Renomeei a coluna."
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                request,
                List.of("authoring-contract-used"));

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("patch");
        assertThat(response.getComponentEditPlan().path("changeKind").asText())
                .isEqualTo("set_column_header");
        assertThat(response.getPatch()).isNull();
        assertThat(response.getWarnings()).containsExactly("authoring-contract-used");
    }

    @Test
    void resolveAuthoringContractPrefersProjectedManifestOverLegacyHint() throws Exception {
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "authoringContract": {
                    "kind": "legacy-contract",
                    "preferredResponse": "patch"
                  }
                }
                """);
        JsonNode componentDefinition = objectMapper.readTree("""
                {
                  "jsonSchema": {
                    "authoringManifest": {
                      "manifestVersion": "1.0.0",
                      "editableTargets": [
                        { "kind": "column", "resolver": "column-by-field" }
                      ],
                      "validators": [
                        { "validatorId": "column-exists" }
                      ],
                      "operations": [
                        {
                          "operationId": "column.header.set",
                          "target": {
                            "kind": "column",
                            "resolver": "column-by-field",
                            "required": true
                          },
                          "inputSchema": {
                            "type": "object",
                            "required": ["header"]
                          },
                          "validators": ["column-exists"],
                          "submissionImpact": { "kind": "none" }
                        }
                      ]
                    }
                  }
                }
                """);
        JsonNode manifest = ReflectionTestUtils.invokeMethod(
                service,
                "extractAuthoringManifest",
                componentDefinition);
        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentType("table")
                .build();

        JsonNode contract = ReflectionTestUtils.invokeMethod(
                service,
                "resolveAuthoringContract",
                contextHints,
                context,
                manifest);

        assertThat(contract).isNotNull();
        assertThat(contract.path("source").asText()).isEqualTo("ai_registry.authoringManifest");
        assertThat(contract.path("preferredResponse").asText()).isEqualTo("componentEditPlan");
        assertThat(contract.path("operations").get(0).path("operationId").asText())
                .isEqualTo("column.header.set");
    }

    @Test
    void componentEditPlanResponseRejectsPlanThatDoesNotMatchManifest() throws Exception {
        JsonNode manifest = minimalAuthoringManifest();
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "operations": [
                      {
                        "operationId": "column.unknown.set",
                        "target": { "kind": "column", "field": "status" },
                        "input": { "header": "SituaÃ§Ã£o" }
                      }
                    ]
                  }
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                request,
                List.of("authoring-manifest-contract-used"),
                manifest);

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("error");
        assertThat(response.getMessage()).contains("operationId nao declarado");
        assertThat(response.getWarnings()).contains("component-edit-plan-rejected-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseAcceptsPlanValidatedByManifest() throws Exception {
        JsonNode manifest = minimalAuthoringManifest();
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "operations": [
                      {
                        "operationId": "column.header.set",
                        "target": { "kind": "column", "field": "status" },
                        "input": { "header": "SituaÃ§Ã£o" }
                      }
                    ]
                  },
                  "explanation": "Renomeei a coluna."
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                request,
                List.of("authoring-manifest-contract-used"),
                manifest);

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("patch");
        assertThat(response.getComponentEditPlan().path("operations").get(0).path("operationId").asText())
                .isEqualTo("column.header.set");
        assertThat(response.getWarnings())
                .contains("component-edit-plan-validated-by-authoring-manifest");
    }

    private JsonNode minimalAuthoringManifest() throws Exception {
        return objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "column", "resolver": "column-by-field" }
                  ],
                  "validators": [
                    { "validatorId": "column-exists" }
                  ],
                  "operations": [
                    {
                      "operationId": "column.header.set",
                      "target": {
                        "kind": "column",
                        "resolver": "column-by-field",
                        "required": true
                      },
                      "inputSchema": {
                        "type": "object",
                        "required": ["header"]
                      },
                      "validators": ["column-exists"],
                      "preconditions": ["config-initialized"],
                      "affectedPaths": ["columns[].header"],
                      "effects": [
                        { "type": "merge-array-item", "path": "columns[]", "key": "field" }
                      ],
                      "submissionImpact": { "kind": "none" }
                    }
                  ]
                }
                """);
    }
}
