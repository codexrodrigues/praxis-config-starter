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
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringReferenceUiCompositionPlanProvider;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServiceContextHintsTest {

    private AiOrchestratorService service;
    private SchemaRetrievalService schemaRetrievalService;
    private AiRegistryTemplateService templateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        schemaRetrievalService = mock(SchemaRetrievalService.class);
        templateService = mock(AiRegistryTemplateService.class);
        service = new AiOrchestratorService(
                mock(AiContextService.class),
                mock(AiProvider.class),
                mock(AiInteractionLogger.class),
                mock(ContextRetrievalService.class),
                schemaRetrievalService,
                templateService,
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
    void resolveTemplateVariantFallsBackToBaseTemplateWhenEmbeddingSearchIsUnavailable() throws Exception {
        AiRegistryTemplateRecord baseTemplate = AiRegistryTemplateRecord.builder()
                .componentId("praxis-table")
                .templateMeta(objectMapper.readTree("""
                        {
                          "variants": [
                            { "variantId": "compact" }
                          ],
                          "defaultVariantId": "default"
                        }
                        """))
                .build();
        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentType("table")
                .template(baseTemplate)
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .userPrompt("Ajude uma pessoa comum a encontrar registros pelos dados principais.")
                .build();
        when(templateService.searchTemplatesByPrefix(anyString(), anyString(), any(Integer.class), any()))
                .thenThrow(new IllegalStateException(
                        "spring.ai.openai.api-key is required when spring.ai.embedding.provider=openai."));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "resolveTemplateVariant",
                request,
                context,
                null);

        assertThat(selection).isNotNull();
        AiRegistryTemplateRecord selectedTemplate =
                (AiRegistryTemplateRecord) ReflectionTestUtils.getField(selection, "template");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) ReflectionTestUtils.getField(selection, "warnings");
        assertThat(selectedTemplate).isSameAs(baseTemplate);
        assertThat(warnings).contains("template-variant-search-degraded: embeddings unavailable");
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
    void componentEditPlanResponseNormalizesUniqueResolverAliasesFromManifest() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "primaryText", "resolver": "templating-primary" },
                    { "kind": "secondaryText", "resolver": "templating-secondary" }
                  ],
                  "operations": [
                    {
                      "operationId": "item.primaryText.set",
                      "target": {
                        "kind": "primaryText",
                        "resolver": "templating-primary",
                        "required": false
                      },
                      "inputSchema": {
                        "type": "object",
                        "required": ["type", "expr"]
                      },
                      "submissionImpact": { "kind": "none" }
                    },
                    {
                      "operationId": "item.secondaryText.set",
                      "target": {
                        "kind": "secondaryText",
                        "resolver": "templating-secondary",
                        "required": false
                      },
                      "inputSchema": {
                        "type": "object",
                        "required": ["type", "expr"]
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
                        "operationId": "templating-primary-set",
                        "input": { "type": "text", "expr": "${item.title}" }
                      },
                      {
                        "operationId": "templating-secondary-set",
                        "input": { "type": "text", "expr": "${item.subtitle}" }
                      }
                    ]
                  },
                  "explanation": "Configurei titulo e subtitulo."
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-list")
                .componentType("list")
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
                .isEqualTo("item.primaryText.set");
        assertThat(response.getComponentEditPlan().path("operations").get(1).path("operationId").asText())
                .isEqualTo("item.secondaryText.set");
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-operation-id-normalized:templating-primary-set:item.primaryText.set",
                        "component-edit-plan-operation-id-normalized:templating-secondary-set:item.secondaryText.set",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseNormalizesBareResolverAliasesAndDropsUnknownListSlots() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "layout", "resolver": "layout-config" },
                    { "kind": "primaryText", "resolver": "templating-primary" },
                    { "kind": "secondaryText", "resolver": "templating-secondary" }
                  ],
                  "operations": [
                    {
                      "operationId": "layout.density.set",
                      "target": { "kind": "layout", "resolver": "layout-config", "required": false },
                      "inputSchema": { "type": "object" },
                      "submissionImpact": { "kind": "none" }
                    },
                    {
                      "operationId": "item.primaryText.set",
                      "target": { "kind": "primaryText", "resolver": "templating-primary", "required": false },
                      "inputSchema": { "type": "object", "required": ["type", "expr"] },
                      "submissionImpact": { "kind": "none" }
                    },
                    {
                      "operationId": "item.secondaryText.set",
                      "target": { "kind": "secondaryText", "resolver": "templating-secondary", "required": false },
                      "inputSchema": { "type": "object", "required": ["type", "expr"] },
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
                        "operationId": "layout-config",
                        "input": { "variant": "cards" }
                      },
                      {
                        "operationId": "templating-primary",
                        "input": { "type": "text", "expr": "${item.name}" }
                      },
                      {
                        "operationId": "templating-secondary",
                        "input": { "type": "text", "expr": "${item.subtitle}" }
                      },
                      {
                        "operationId": "templating-features",
                        "input": { "expr": "${item.subtitle}" }
                      }
                    ]
                  },
                  "explanation": "Configurei cards."
                }
                """);

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                AiOrchestratorRequest.builder().componentId("praxis-list").componentType("list").build(),
                List.of("authoring-manifest-contract-used"),
                manifest);

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("patch");
        JsonNode operations = response.getComponentEditPlan().path("operations");
        assertThat(operations).hasSize(3);
        assertThat(operations.get(0).path("operationId").asText()).isEqualTo("layout.density.set");
        assertThat(operations.get(1).path("operationId").asText()).isEqualTo("item.primaryText.set");
        assertThat(operations.get(2).path("operationId").asText()).isEqualTo("item.secondaryText.set");
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-operation-id-normalized:layout-config:layout.density.set",
                        "component-edit-plan-operation-id-normalized:templating-primary:item.primaryText.set",
                        "component-edit-plan-operation-id-normalized:templating-secondary:item.secondaryText.set",
                        "component-edit-plan-operation-dropped:not-declared:templating-features",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseDisambiguatesDataSourceResolverAliasByInputShape() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "dataBinding", "resolver": "data-source-config" }
                  ],
                  "operations": [
                    {
                      "operationId": "data.resource.bind",
                      "target": { "kind": "dataBinding", "resolver": "data-source-config", "required": false },
                      "inputSchema": { "type": "object", "required": ["resourcePath"] },
                      "submissionImpact": { "kind": "none" }
                    },
                    {
                      "operationId": "data.local.set",
                      "target": { "kind": "dataBinding", "resolver": "data-source-config", "required": false },
                      "inputSchema": { "type": "object", "required": ["data"] },
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
                        "operationId": "data-source-config",
                        "input": {
                          "data": [
                            { "name": "Integração", "subtitle": "Onboarding" },
                            { "name": "LGPD", "subtitle": "Privacidade" }
                          ]
                        }
                      }
                    ]
                  },
                  "explanation": "Configurei dados locais."
                }
                """);

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                AiOrchestratorRequest.builder().componentId("praxis-list").componentType("list").build(),
                List.of("authoring-manifest-contract-used"),
                manifest);

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isNull();
        JsonNode operations = response.getComponentEditPlan().path("operations");
        assertThat(operations).hasSize(1);
        assertThat(operations.get(0).path("operationId").asText()).isEqualTo("data.local.set");
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-operation-id-normalized:data-source-config:data.local.set",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseNormalizesImperativeListOperationNamesByIntentAndInputAliases() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "layout", "resolver": "layout-config" },
                    { "kind": "dataBinding", "resolver": "data-source-config" }
                  ],
                  "operations": [
                    {
                      "operationId": "layout.density.set",
                      "target": { "kind": "layout", "resolver": "layout-config", "required": false },
                      "inputSchema": { "type": "object" },
                      "submissionImpact": { "kind": "none" }
                    },
                    {
                      "operationId": "data.local.set",
                      "target": { "kind": "dataBinding", "resolver": "data-source-config", "required": false },
                      "inputSchema": { "type": "object", "required": ["data"] },
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
                        "operationId": "set_list_layout_cards",
                        "input": { "variant": "cards" }
                      },
                      {
                        "operationId": "set_example_items",
                        "input": {
                          "items": [
                            { "name": "Integração", "subtitle": "Onboarding" },
                            { "name": "LGPD", "subtitle": "Privacidade" }
                          ]
                        }
                      }
                    ]
                  },
                  "explanation": "Configurei cards editoriais."
                }
                """);

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                AiOrchestratorRequest.builder().componentId("praxis-list").componentType("list").build(),
                List.of("authoring-manifest-contract-used"),
                manifest);

        assertThat(response).isNotNull();
        JsonNode operations = response.getComponentEditPlan().path("operations");
        assertThat(operations).hasSize(2);
        assertThat(operations.get(0).path("operationId").asText()).isEqualTo("layout.density.set");
        assertThat(operations.get(1).path("operationId").asText()).isEqualTo("data.local.set");
        assertThat(operations.get(1).path("input").path("data")).hasSize(2);
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-operation-id-normalized:set_list_layout_cards:layout.density.set",
                        "component-edit-plan-operation-id-normalized:set_example_items:data.local.set",
                        "component-edit-plan-input-normalized:data.local.set",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseWrapsSingleLocalListItemInputForDataLocalSet() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "dataBinding", "resolver": "data-source-config" }
                  ],
                  "operations": [
                    {
                      "operationId": "data.local.set",
                      "target": { "kind": "dataBinding", "resolver": "data-source-config", "required": false },
                      "inputSchema": { "type": "object", "required": ["data"] },
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
                        "operationId": "data.local.set",
                        "input": { "name": "Integração", "subtitle": "Onboarding" }
                      },
                      {
                        "operationId": "data.local.set",
                        "input": { "title": "LGPD", "description": "Privacidade" }
                      }
                    ]
                  },
                  "explanation": "Cards editoriais locais."
                }
                """);

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                AiOrchestratorRequest.builder().componentId("praxis-list").componentType("list").build(),
                List.of("authoring-manifest-contract-used"),
                manifest);

        assertThat(response).isNotNull();
        JsonNode operations = response.getComponentEditPlan().path("operations");
        assertThat(operations).hasSize(2);
        assertThat(operations.get(0).path("input").path("data")).hasSize(1);
        assertThat(operations.get(0).path("input").path("data").get(0).path("name").asText()).isEqualTo("Integração");
        assertThat(operations.get(1).path("input").path("data")).hasSize(1);
        assertThat(operations.get(1).path("input").path("data").get(0).path("title").asText()).isEqualTo("LGPD");
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-input-normalized:data.local.set",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseInfersCardsVariantFromLayoutOperationAlias() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "layout", "resolver": "layout-config" }
                  ],
                  "operations": [
                    {
                      "operationId": "layout.density.set",
                      "target": { "kind": "layout", "resolver": "layout-config", "required": false },
                      "inputSchema": { "type": "object" },
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
                        "operationId": "layout.variant.set.cards",
                        "input": {}
                      }
                    ]
                  },
                  "explanation": "Use cards."
                }
                """);

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                AiOrchestratorRequest.builder().componentId("praxis-list").componentType("list").build(),
                List.of("authoring-manifest-contract-used"),
                manifest);

        assertThat(response).isNotNull();
        JsonNode operation = response.getComponentEditPlan().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("layout.density.set");
        assertThat(operation.path("input").path("variant").asText()).isEqualTo("cards");
        assertThat(operation.path("input").path("lines").asInt()).isEqualTo(2);
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-operation-id-normalized:layout.variant.set.cards:layout.density.set",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseDefaultsSafeTemplateTypeWhenTextExpressionIsPresent() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "primaryText", "resolver": "templating-primary" }
                  ],
                  "operations": [
                    {
                      "operationId": "item.primaryText.set",
                      "target": { "kind": "primaryText", "resolver": "templating-primary", "required": false },
                      "inputSchema": {
                        "type": "object",
                        "required": ["type", "expr"],
                        "properties": {
                          "type": { "enum": ["text", "icon"] },
                          "expr": { "type": "string" }
                        }
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
                        "operationId": "item.primaryText.set",
                        "input": { "expr": "${item.name}" }
                      }
                    ]
                  },
                  "explanation": "Configurei titulo."
                }
                """);

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                AiOrchestratorRequest.builder().componentId("praxis-list").componentType("list").build(),
                List.of("authoring-manifest-contract-used"),
                manifest);

        assertThat(response).isNotNull();
        JsonNode operation = response.getComponentEditPlan().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("item.primaryText.set");
        assertThat(operation.path("input").path("type").asText()).isEqualTo("text");
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-input-normalized:item.primaryText.set",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanFromListPatchMaterializesLocalDataAndTemplates() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-list",
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "dataBinding", "resolver": "data-source-config" },
                    { "kind": "primaryText", "resolver": "templating-primary" },
                    { "kind": "secondaryText", "resolver": "templating-secondary" },
                    { "kind": "layout", "resolver": "layout-config" },
                    { "kind": "toolbarUi", "resolver": "ui-config" }
                  ],
                  "operations": [
                    {
                      "operationId": "data.local.set",
                      "target": { "kind": "dataBinding", "resolver": "data-source-config", "required": false },
                      "inputSchema": { "type": "object", "required": ["data"] }
                    },
                    {
                      "operationId": "item.primaryText.set",
                      "target": { "kind": "primaryText", "resolver": "templating-primary", "required": false },
                      "inputSchema": { "type": "object", "required": ["type", "expr"] }
                    },
                    {
                      "operationId": "item.secondaryText.set",
                      "target": { "kind": "secondaryText", "resolver": "templating-secondary", "required": false },
                      "inputSchema": { "type": "object", "required": ["type", "expr"] }
                    },
                    {
                      "operationId": "layout.density.set",
                      "target": { "kind": "layout", "resolver": "layout-config", "required": false },
                      "inputSchema": { "type": "object" }
                    },
                    {
                      "operationId": "ui.toolbar.configure",
                      "target": { "kind": "toolbarUi", "resolver": "ui-config", "required": false },
                      "inputSchema": { "type": "object" }
                    }
                  ]
                }
                """);
        JsonNode patch = objectMapper.readTree("""
                {
                  "layout": { "variant": "cards" },
                  "ui": { "showSearch": false },
                  "templating": {
                    "primary": { "expr": "${item.name}" },
                    "secondary": { "expr": "${item.subtitle}" }
                  },
                  "dataSource": {
                    "data": [
                      { "name": "Integração", "subtitle": "Boas-vindas e primeiros passos" },
                      { "name": "LGPD", "subtitle": "Privacidade com foco" }
                    ]
                  }
                }
                """);
        List<String> warnings = new java.util.ArrayList<>();

        JsonNode componentEditPlan = ReflectionTestUtils.invokeMethod(
                service,
                "buildComponentEditPlanFromPatch",
                "praxis-list",
                patch,
                manifest,
                warnings);

        assertThat(componentEditPlan).isNotNull();
        JsonNode operations = componentEditPlan.path("operations");
        assertThat(operations).hasSize(5);
        assertThat(operations.get(0).path("operationId").asText()).isEqualTo("data.local.set");
        assertThat(operations.get(0).path("input").path("data")).hasSize(2);
        assertThat(operations.get(1).path("operationId").asText()).isEqualTo("item.primaryText.set");
        assertThat(operations.get(1).path("input").path("type").asText()).isEqualTo("text");
        assertThat(operations.get(2).path("operationId").asText()).isEqualTo("item.secondaryText.set");
        assertThat(warnings)
                .contains("praxis-list patch livre convertido para componentEditPlan manifest-backed.");
    }

    @Test
    void componentEditPlanFromListPatchDropsUnboundTemplateExpressions() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-list",
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "primaryText", "resolver": "templating-primary" },
                    { "kind": "secondaryText", "resolver": "templating-secondary" },
                    { "kind": "layout", "resolver": "layout-config" }
                  ],
                  "operations": [
                    {
                      "operationId": "item.primaryText.set",
                      "target": { "kind": "primaryText", "resolver": "templating-primary", "required": false },
                      "inputSchema": { "type": "object", "required": ["type", "expr"] }
                    },
                    {
                      "operationId": "item.secondaryText.set",
                      "target": { "kind": "secondaryText", "resolver": "templating-secondary", "required": false },
                      "inputSchema": { "type": "object", "required": ["type", "expr"] }
                    },
                    {
                      "operationId": "layout.density.set",
                      "target": { "kind": "layout", "resolver": "layout-config", "required": false },
                      "inputSchema": { "type": "object" }
                    }
                  ]
                }
                """);
        JsonNode patch = objectMapper.readTree("""
                {
                  "layout": { "variant": "cards" },
                  "templating": {
                    "primary": { "expr": "comentário principal" },
                    "secondary": { "expr": "Histórico" }
                  }
                }
                """);
        List<String> warnings = new java.util.ArrayList<>();

        JsonNode componentEditPlan = ReflectionTestUtils.invokeMethod(
                service,
                "buildComponentEditPlanFromPatch",
                "praxis-list",
                patch,
                manifest,
                warnings);

        assertThat(componentEditPlan).isNotNull();
        JsonNode operations = componentEditPlan.path("operations");
        assertThat(operations).hasSize(1);
        assertThat(operations.get(0).path("operationId").asText()).isEqualTo("layout.density.set");
        assertThat(warnings)
                .contains(
                        "list-template-patch-dropped:unbound-expression:item.primaryText.set",
                        "list-template-patch-dropped:unbound-expression:item.secondaryText.set",
                        "praxis-list patch livre convertido para componentEditPlan manifest-backed.");
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
    void componentEditPlanResponseNormalizesDeclaredOperationAndDropsManifestNoise() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "tabContent", "resolver": "tab-or-link-by-id" }
                  ],
                  "operations": [
                    {
                      "operationId": "tab.content.set",
                      "target": {
                        "kind": "tabContent",
                        "resolver": "tab-or-link-by-id",
                        "required": true
                      },
                      "inputSchema": {
                        "type": "object",
                        "properties": {
                          "widgets": { "type": "array", "items": { "type": "object" } }
                        }
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
                        "operationId": "tab.content.set",
                        "target": { "kind": "tab", "id": "buscar-e-listar" },
                        "input": {
                          "widgets": [
                            { "id": "treinamentos-list", "component": "praxis-list" }
                          ]
                        }
                      },
                      {
                        "operationId": "appearance.density.set",
                        "input": { "density": "compact" }
                      }
                    ]
                  },
                  "explanation": "Atualizei a aba."
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-tabs")
                .componentType("tabs")
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
        JsonNode operation = response.getComponentEditPlan().path("operations").get(0);
        assertThat(response.getComponentEditPlan().path("operations")).hasSize(1);
        assertThat(operation.path("operationId").asText()).isEqualTo("tab.content.set");
        assertThat(operation.path("target").path("kind").asText()).isEqualTo("tabContent");
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-target-kind-normalized:tab.content.set:tabContent",
                        "component-edit-plan-operation-dropped:not-declared:appearance.density.set",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseInfersListTemplateSlotTargetFromInputSlot() throws Exception {
        JsonNode manifest = listTemplateSlotManifest();
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "operations": [
                      {
                        "operationId": "template.slot.set",
                        "input": {
                          "slot": "secondary",
                          "template": { "type": "text", "expr": "${item.historySummary}" }
                        }
                      }
                    ]
                  },
                  "explanation": "Atualizei o slot secundário."
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-list")
                .componentType("list")
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
        JsonNode operation = response.getComponentEditPlan().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("template.slot.set");
        assertThat(operation.path("target").path("kind").asText()).isEqualTo("itemTemplate");
        assertThat(operation.path("target").path("slot").asText()).isEqualTo("secondary");
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-target-inferred:template.slot.set:secondary",
                        "component-edit-plan-validated-by-authoring-manifest");
    }

    @Test
    void componentEditPlanResponseDropsIncompleteDeclaredOperationAndKeepsValidManifestOperations() throws Exception {
        JsonNode manifest = listTemplateSlotManifest();
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "operations": [
                      {
                        "operationId": "data.local.set",
                        "input": {
                          "data": [
                            {
                              "title": "Solicitação de acesso",
                              "status": "Novo",
                              "historySummary": "Histórico: Autor: Ana Souza | Data: 2026-05-06 | Status: Novo"
                            }
                          ]
                        }
                      },
                      {
                        "operationId": "template.slot.set",
                        "input": {}
                      }
                    ]
                  },
                  "explanation": "Preservei os cards locais."
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-list")
                .componentType("list")
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
        JsonNode operations = response.getComponentEditPlan().path("operations");
        assertThat(operations).hasSize(1);
        assertThat(operations.get(0).path("operationId").asText()).isEqualTo("data.local.set");
        assertThat(response.getWarnings())
                .contains(
                        "component-edit-plan-operation-dropped:missing-required-contract:template.slot.set",
                        "component-edit-plan-validated-by-authoring-manifest");
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

    @Test
    void routesPageBuilderLocalEditorialPatchThroughAgenticPreviewBeforeTemplateResourceClarification() throws Exception {
        AiContextService contextService = mock(AiContextService.class);
        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-dynamic-page")
                .componentType("page")
                .componentDefinition(objectMapper.createObjectNode())
                .currentState(objectMapper.readTree("{\"page\":{\"widgets\":[]}}"))
                .build();
        when(contextService.buildContext(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(context);
        AiThreadService threadService = mock(AiThreadService.class);
        AiMessageService messageService = mock(AiMessageService.class);
        when(threadService.resolveThread(any(), any(), any(), any(), anyString())).thenReturn(new AiThread());
        when(messageService.prepareTurn(any(), any(), anyString())).thenReturn(null);
        AiOrchestratorService localService = new AiOrchestratorService(
                contextService,
                mock(AiProvider.class),
                mock(AiInteractionLogger.class),
                mock(ContextRetrievalService.class),
                schemaRetrievalService,
                templateService,
                mock(AiRagContextService.class),
                mock(UserConfigService.class),
                objectMapper,
                mock(AiApiKeyCryptoService.class),
                threadService,
                messageService);
        ReflectionTestUtils.setField(localService, "agenticAuthoringPreviewService",
                new AgenticAuthoringPreviewService(
                        mock(AgenticAuthoringPlanService.class),
                        mock(AgenticAuthoringPatchCompilerService.class),
                        objectMapper,
                        List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper))));
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-dynamic-page")
                .componentType("page")
                .currentState(objectMapper.readTree("{\"page\":{\"widgets\":[]}}"))
                .userPrompt("Crie uma pagina operacional com Praxis Tabs para solicitacoes internas. A aba Cadastro deve conter um formulario local editorial com campos Titulo, Responsavel, Prioridade, Prazo, Anexos simulados e Observacoes internas. A aba Registros deve conter um componente Praxis CRUD local editorial com tres solicitacoes ficticias, colunas Titulo, Responsavel, Categoria, SLA e Status, e acoes visiveis Criar, Editar e Excluir. A aba Relacionamentos deve conter cards agrupados por solicitacao relacionada, cada card com comentarios e uma mini lista Historico com Autor, Data e Status. Use apenas conteudo local editorial de demonstracao, sem API real, sem schema externo e sem criar regra de negocio definitiva.")
                .build();

        AiOrchestratorResponse response = localService.generatePatch(request, "http://localhost:8088", "demo", "codex", "local");

        assertThat(response.getType()).isEqualTo("patch");
        assertThat(response.getMessage()).doesNotContain("resourcePath");
        assertThat(response.getComponentEditPlan().path("uiCompositionPlan").path("widgets").path(0)
                .path("inputs").path("config").path("tabs"))
                .extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Cadastro", "Registros", "Relacionamentos");
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

    private JsonNode listTemplateSlotManifest() throws Exception {
        return objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "dataBinding", "resolver": "data-source-config" },
                    { "kind": "itemTemplate", "resolver": "list-template-slot" }
                  ],
                  "operations": [
                    {
                      "operationId": "data.local.set",
                      "target": { "kind": "dataBinding", "resolver": "data-source-config", "required": false },
                      "inputSchema": { "type": "object", "required": ["data"] }
                    },
                    {
                      "operationId": "template.slot.set",
                      "target": {
                        "kind": "itemTemplate",
                        "resolver": "list-template-slot",
                        "required": true
                      },
                      "inputSchema": {
                        "type": "object",
                        "required": ["slot", "template"],
                        "properties": {
                          "slot": {
                            "enum": ["primary", "secondary", "meta", "trailing"]
                          }
                        }
                      }
                    }
                  ]
                }
                """);
    }
}
