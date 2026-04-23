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
    void templateFlowUsesDirectResourcePathFromBackendDrivenQuickReplyHints() throws Exception {
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "tool": "composeDashboard",
                  "resourcePath": "/api/human-resources/vw-analytics-folha-pagamento",
                  "layout": "chart-list",
                  "groupBy": "departamento"
                }
                """);

        String resourcePath = ReflectionTestUtils.invokeMethod(
                service,
                "extractTemplateResourcePath",
                contextHints);

        assertThat(resourcePath)
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
    }

    @Test
    void templateFlowContinuesAfterQuickReplyEvenWhenButtonTextIsNotCreateVerb() throws Exception {
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .userPrompt("Use Grafico + lista abaixo (/api/human-resources/vw-analytics-folha-pagamento) as the data source.")
                .contextHints(objectMapper.readTree("""
                        {
                          "tool": "composeDashboard",
                          "resourcePath": "/api/human-resources/vw-analytics-folha-pagamento"
                        }
                        """))
                .build();
        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-dynamic-page-builder")
                .currentState(objectMapper.readTree("""
                        { "widgets": [] }
                        """))
                .build();
        JsonNode templateConfig = objectMapper.readTree("""
                { "page": { "widgets": [] } }
                """);

        Boolean shouldUseTemplateFlow = ReflectionTestUtils.invokeMethod(
                service,
                "shouldUseTemplateFlow",
                request,
                context,
                templateConfig);

        assertThat(shouldUseTemplateFlow).isTrue();
    }

    @Test
    void templateFlowExtractsResourcePathFromQuickReplyTextWhenHintsAreMissing() {
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .userPrompt("Use Grafico por setor + lista embaixo (/api/human-resources/folhas-pagamento) as the data source.")
                .build();

        String resourcePath = ReflectionTestUtils.invokeMethod(
                service,
                "extractTemplateResourcePath",
                request);

        assertThat(resourcePath)
                .isEqualTo("/api/human-resources/folhas-pagamento");
    }

    @Test
    void templateFlowContinuesFromQuickReplyTextResourcePathWithoutHints() throws Exception {
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .userPrompt("Use Grafico por setor + lista embaixo (/api/human-resources/folhas-pagamento) as the data source.")
                .build();
        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-dynamic-page-builder")
                .currentState(objectMapper.readTree("""
                        { "page": { "widgets": [] } }
                        """))
                .build();
        JsonNode templateConfig = objectMapper.readTree("""
                { "page": { "widgets": [] } }
                """);

        Boolean shouldUseTemplateFlow = ReflectionTestUtils.invokeMethod(
                service,
                "shouldUseTemplateFlow",
                request,
                context,
                templateConfig);

        assertThat(shouldUseTemplateFlow).isTrue();
    }

    @Test
    void buildExecutionPromptPreservesDomainCatalogGovernanceInRagHints() {
        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentType("table")
                .aiMode("assist")
                .componentDefinition(objectMapper.createObjectNode())
                .currentState(objectMapper.createObjectNode())
                .build();

        String domainCatalogContext = """
                DOMAIN_CATALOG_CONTEXT
                schemaVersion: praxis.domain-catalog-context/v0.1
                releaseKey: praxis-service:human-resources.funcionarios:latest
                serviceKey: praxis-service
                query: cpf
                itemType: governance
                guidance:
                - Use governance items to respect privacy, compliance and AI visibility constraints.
                items:
                - [governance/-] governance:human-resources.funcionarios.field.cpf:privacy | classification=confidential | dataCategory=personal | visibility=mask | trainingUse=deny | ruleAuthoring=review_required | complianceTags=LGPD,GDPR
                """;

        String prompt = ReflectionTestUtils.invokeMethod(
                service,
                "buildExecutionPrompt",
                "Crie uma tabela de funcionarios respeitando LGPD.",
                context,
                objectMapper.createObjectNode(),
                List.of(),
                "",
                "",
                null,
                null,
                "N/A",
                null,
                domainCatalogContext,
                "N/A",
                null);

        assertThat(prompt)
                .contains("DOMAIN_CATALOG_CONTEXT")
                .contains("governance:human-resources.funcionarios.field.cpf:privacy")
                .contains("classification=confidential")
                .contains("visibility=mask")
                .contains("trainingUse=deny")
                .contains("ruleAuthoring=review_required")
                .contains("complianceTags=LGPD,GDPR");
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

    @Test
    void componentEditPlanResponseAcceptsOperationInputCollapsedIntoTarget() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "visualBlock", "resolver": "visual-block-by-id" }
                  ],
                  "operations": [
                    {
                      "operationId": "rule.visualBlockGuidance.add",
                      "target": {
                        "kind": "visualBlock",
                        "resolver": "visual-block-by-id",
                        "required": true
                      },
                      "inputSchema": {
                        "type": "object",
                        "required": ["type", "targetType", "targets", "condition", "properties", "metadata"]
                      },
                      "submissionImpact": { "kind": "none" }
                    }
                  ]
                }
                """);
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "operations": [
                      {
                        "operationId": "rule.visualBlockGuidance.add",
                        "target": {
                          "kind": "visualBlock",
                          "id": "lgpd-cpf-guidance",
                          "type": "visualBlockGuidance",
                          "targetType": "visualBlock",
                          "targets": ["lgpd-notice"],
                          "condition": { "!==": [{ "var": "cpf" }, null] },
                          "properties": {
                            "message": "CPF e dado pessoal.",
                            "messageNodeId": "message"
                          }
                        }
                      }
                    ]
                  },
                  "explanation": "Criei orientacao LGPD."
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-dynamic-form")
                .componentType("form")
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
        assertThat(response.getWarnings())
                .contains("component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseAcceptsVisualGuidanceAliasesFromActionPlan() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "visualBlock", "resolver": "visual-block-by-id" }
                  ],
                  "operations": [
                    {
                      "operationId": "rule.visualBlockGuidance.add",
                      "target": {
                        "kind": "visualBlock",
                        "resolver": "visual-block-by-id",
                        "required": true
                      },
                      "inputSchema": {
                        "type": "object",
                        "required": ["id", "type", "targetType", "targets", "condition", "properties", "metadata"]
                      },
                      "submissionImpact": { "kind": "none" }
                    }
                  ]
                }
                """);
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "operations": [
                      {
                        "operationId": "rule.visualBlockGuidance.add",
                        "target": { "kind": "visualBlock" },
                        "targetType": "visualBlock",
                        "targetId": "lgpd-notice",
                        "nodeId": "message",
                        "ruleId": "lgpd-cpf-guidance",
                        "input": {
                          "name": "LGPD guidance for CPF",
                          "description": "Orienta o uso do campo CPF conforme LGPD.",
                          "effect": {
                            "condition": { "==": [1, 1] }
                          }
                        }
                      }
                    ]
                  },
                  "explanation": "Criei orientacao LGPD."
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-dynamic-form")
                .componentType("form")
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
