package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.service.DomainCatalogIngestionService;
import org.praxisplatform.config.service.DomainRuleAssistantSearchProjection;
import org.praxisplatform.config.service.DomainRuleAssistantSearchService;
import org.praxisplatform.config.service.SchemaRetrievalService;

@Tag("unit")
class AgenticAuthoringToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchesDomainRulesOnlyWithServerResolvedReaderAuthority() {
        DomainRuleAssistantSearchService searchService = Mockito.mock(DomainRuleAssistantSearchService.class);
        DomainRuleAssistantSearchProjection projection = new DomainRuleAssistantSearchProjection(
                DomainRuleAssistantSearchProjection.SCHEMA_VERSION,
                List.of(new DomainRuleAssistantSearchProjection.Candidate(
                        UUID.randomUUID(),
                        "human-resources.payroll.net-salary",
                        3,
                        "calculation",
                        "approved",
                        "human-resources.payroll",
                        "human-resources.folhas-pagamento",
                        "payroll",
                        "people-operations",
                        Instant.parse("2026-08-16T12:00:00Z"))),
                0,
                6,
                false);
        when(searchService.search(
                eq("salary"), eq("calculation"), eq("approved"), eq(""), eq(0), eq(6),
                Mockito.any()))
                .thenReturn(projection);
        AgenticAuthoringToolRegistry registry = registryWithDomainRuleSearch(searchService);
        JsonNode payload = objectMapper.createObjectNode()
                .put("query", "salary")
                .put("ruleType", "calculation")
                .put("status", "approved")
                .put("page", 0)
                .put("limit", 6);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.SEARCH_DOMAIN_RULES,
                        "pre_intent_resource_discovery",
                        payload),
                new AiPrincipalContext(
                        "tenant-a", "reader", "prod", true, Set.of("RULE_DEFINITION_READER")),
                "retrieveEvidence");

        assertThat(result.valid())
                .as("errorCode=%s errorMessage=%s", result.errorCode(), result.errorMessage())
                .isTrue();
        assertThat(result.payload()).isEqualTo(projection);
        assertThat(result.safeDiagnostics())
                .containsEntry("candidateCount", 1)
                .containsEntry("scopeSource", "server_principal");
    }

    @Test
    void rejectsDomainRuleSearchWithoutReaderAuthority() {
        DomainRuleAssistantSearchService searchService = Mockito.mock(DomainRuleAssistantSearchService.class);
        AgenticAuthoringToolResult result = registryWithDomainRuleSearch(searchService).execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.SEARCH_DOMAIN_RULES,
                        "advisory_authoring",
                        objectMapper.createObjectNode().put("query", "payroll")),
                new AiPrincipalContext("tenant-a", "authenticated-user", "prod", true),
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("domain-rule-reader-required");
        Mockito.verifyNoInteractions(searchService);
    }

    @Test
    void acceptsBackendIssuedReadAuthorityInLocalGovernanceMode() {
        DomainRuleAssistantSearchService searchService = Mockito.mock(DomainRuleAssistantSearchService.class);
        DomainRuleAssistantSearchProjection projection = new DomainRuleAssistantSearchProjection(
                DomainRuleAssistantSearchProjection.SCHEMA_VERSION,
                List.of(),
                0,
                6,
                false);
        when(searchService.search(
                eq("benefit"), eq(""), eq(""), eq(""), eq(0), eq(6), Mockito.any()))
                .thenReturn(projection);

        AgenticAuthoringToolResult result = registryWithDomainRuleSearch(searchService).execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.SEARCH_DOMAIN_RULES,
                        "pre_intent_resource_discovery",
                        objectMapper.createObjectNode()
                                .put("query", "benefit")
                                .put("page", 0)
                                .put("limit", 6)),
                new AiPrincipalContext(
                        "demo", "demo", "local", false, Set.of("RULE_DEFINITION_READER")),
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics()).containsEntry("scopeSource", "local_governance");
    }

    private AgenticAuthoringToolRegistry registryWithDomainRuleSearch(
            DomainRuleAssistantSearchService searchService) {
        return new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                null,
                null,
                objectMapper,
                null,
                null,
                null,
                null,
                null,
                "praxis-service",
                null,
                null,
                searchService);
    }

    @Test
    void exposesSearchApiResourcesAsInternalRouteScopedTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        assertThat(registry.definitions())
                .hasSize(15)
                .anySatisfy(definition -> {
                    assertThat(definition.name()).isEqualTo("searchApiResources");
                    assertThat(definition.allowedRoutes())
                            .containsExactlyInAnyOrder(
                                    "component_authoring",
                                    "shared_rule_authoring",
                                    "mixed",
                                    "needs_clarification",
                                    "advisory_authoring",
                                    "pre_intent_resource_discovery");
                    assertThat(definition.ownerSurface())
                            .isEqualTo("praxis-config-starter:/api/praxis/config/ai/authoring/resource-candidates");
                    assertThat(definition.allowedPhases())
                            .containsExactlyInAnyOrder("retrieveEvidence", "repairOrAsk");
                    assertThat(definition.sideEffectClass()).isEqualTo("read_only");
                    assertThat(definition.governanceLevel()).isEqualTo("safe_grounding");
                    assertThat(definition.auditRedactionPolicy()).isEqualTo("safe_event_projection_only");
                })
                .anySatisfy(definition -> {
                    assertThat(definition.name()).isEqualTo("resolveRuntimeRelatedSurface");
                    assertThat(definition.allowedRoutes())
                            .containsExactlyInAnyOrder(
                                    "component_authoring",
                                    "mixed",
                                    "needs_clarification",
                                    "advisory_authoring");
                    assertThat(definition.ownerSurface())
                            .isEqualTo("praxis-config-starter:runtime-related-surface-read");
                    assertThat(definition.allowedPhases())
                            .containsExactly("retrieveEvidence");
                    assertThat(definition.sideEffectClass()).isEqualTo("read_only");
                    assertThat(definition.governanceLevel()).isEqualTo("governed_runtime_context_reconciliation");
                    assertThat(definition.auditRedactionPolicy()).isEqualTo("safe_event_projection_only");
                })
                .extracting(AgenticAuthoringToolDefinition::name)
                .containsExactlyInAnyOrder(
                        "searchApiResources",
                        "searchComponentCorpus",
                        "getComponentAuthoringContext",
                        "getManifestSlice",
                        "searchConfigPathDocs",
                        "searchExamples",
                        "searchSchemaFields",
                        "presentationAffordanceDiscovery",
                        "resolveRuntimeRelatedSurface",
                        "discoverDomainContexts",
                        "discoverDomainCapabilities",
                        "discoverDomainConcepts",
                        "inspectDomainBindings",
                        "verifyDomainOperation",
                        "searchOptionSourceValues");
    }

    @Test
    void keepsCanonicalApiDiscoveryAvailableWhileGovernedBindingIsNotYetAuthored() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ApiMetadata employeeMetadata = new ApiMetadata(
                "/api/human-resources/funcionarios",
                "GET",
                "funcionarios,rh,pessoas",
                "Funcionários",
                "Lista funcionários por departamento",
                "listFuncionarios",
                null,
                "{\"type\":\"object\"}",
                "[]",
                "{}",
                null);
        when(repository.findAll()).thenReturn(List.of(employeeMetadata));
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant", "local", "default", "v1"))
                .thenReturn(List.of(employeeMetadata));
        AgenticAuthoringDomainBindingService bindingService =
                Mockito.mock(AgenticAuthoringDomainBindingService.class);
        when(bindingService.resolve("tenant", "local", "human-resources.funcionarios", 6))
                .thenReturn(List.of());
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper),
                null,
                null,
                null,
                objectMapper,
                null,
                null,
                bindingService);
        AgenticAuthoringResourceSearchFocus focus = new AgenticAuthoringResourceSearchFocus(
                "human-resources.funcionarios", List.of("departamento"), "table", "", "governed subject");

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                        "pre_intent_resource_discovery",
                        new AgenticAuthoringResourceCandidatesRequest(
                                "funcionarios departamento", "listar funcionarios por departamento", "table", 6, focus)),
                new AiPrincipalContext("tenant", "user", "local", true),
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics())
                .containsEntry("candidateCount", 1)
                .doesNotContainEntry("retrievalSource", "none");
    }

    @Test
    void discoversApiResourceSemanticallyWhenCanonicalResourceKeyIsNotResolvedYet() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ApiMetadata employeeMetadata = new ApiMetadata(
                "/api/human-resources/funcionarios",
                "GET",
                "funcionarios,rh,pessoas",
                "Funcionários",
                "Lista funcionários por departamento",
                "listFuncionarios",
                null,
                "{\"type\":\"object\"}",
                "[]",
                "{}",
                null);
        when(repository.findAll()).thenReturn(List.of(employeeMetadata));
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant", "local", "default", "v1"))
                .thenReturn(List.of(employeeMetadata));
        AgenticAuthoringDomainBindingService bindingService =
                Mockito.mock(AgenticAuthoringDomainBindingService.class);
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper),
                null,
                null,
                null,
                objectMapper,
                null,
                null,
                bindingService);
        AgenticAuthoringResourceSearchFocus unresolvedFocus = new AgenticAuthoringResourceSearchFocus(
                "", List.of("departamentos de engenharia"), "table", "", "canonical entity pending discovery");

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                        "pre_intent_resource_discovery",
                        new AgenticAuthoringResourceCandidatesRequest(
                                "registros de funcionários por departamento",
                                "tabela da equipe de engenharia",
                                "table",
                                6,
                                unresolvedFocus)),
                new AiPrincipalContext("tenant", "user", "local", true),
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics()).containsEntry("candidateCount", 1);
        AgenticAuthoringResourceCandidatesResult payload =
                (AgenticAuthoringResourceCandidatesResult) result.payload();
        assertThat(payload.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly("/api/human-resources/funcionarios");
        Mockito.verifyNoInteractions(bindingService);
    }

    @Test
    void usesUniqueOperationallyVerifiedDomainBindingBeforeVectorResourceSearch() {
        AgenticAuthoringResourceDiscoveryService resourceDiscoveryService =
                Mockito.mock(AgenticAuthoringResourceDiscoveryService.class);
        AgenticAuthoringDomainBindingService bindingService =
                Mockito.mock(AgenticAuthoringDomainBindingService.class);
        AgenticAuthoringOperationalBindingVerificationService verificationService =
                Mockito.mock(AgenticAuthoringOperationalBindingVerificationService.class);
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "hr:employee-management",
                        "api_resource",
                        "resource:human-resources.funcionarios",
                        "human-resources.funcionarios",
                        "/api/human-resources/funcionarios",
                        "GET",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=get&schemaType=response",
                        1d,
                        "hr-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "local", "human-resources.funcionarios", 6))
                .thenReturn(List.of(binding));
        when(verificationService.verify(
                eq("human-resources.funcionarios"),
                eq("http://localhost:8088"),
                Mockito.any(AiPrincipalContext.class)))
                .thenReturn(new AgenticAuthoringOperationalBindingVerificationService.VerificationResult(
                        true,
                        "human-resources.funcionarios",
                        List.of(
                                new AgenticAuthoringOperationalBindingVerificationService.OperationProjection(
                                        "hr:employee-management",
                                        "resource:human-resources.funcionarios",
                                        "human-resources.funcionarios",
                                        "/api/human-resources/funcionarios",
                                        "/api/human-resources/funcionarios/filter/cursor",
                                        "post",
                                        "request",
                                        "http://localhost/schemas/filtered?path=%2Fapi%2Fhuman-resources%2Ffuncionarios%2Ffilter%2Fcursor&operation=post&schemaType=request",
                                        "http://localhost/api/human-resources/funcionarios/capabilities",
                                        "cursor",
                                        "hr-v1",
                                        binding.evidence()),
                                new AgenticAuthoringOperationalBindingVerificationService.OperationProjection(
                                        "hr:employee-management",
                                        "update:human-resources.funcionarios",
                                        "human-resources.funcionarios",
                                        "/api/human-resources/funcionarios",
                                        "/api/human-resources/funcionarios/{id}",
                                        "put",
                                        "request",
                                        "http://localhost/schemas/filtered?path=%2Fapi%2Fhuman-resources%2Ffuncionarios%2F%7Bid%7D&operation=put&schemaType=request",
                                        "http://localhost/api/human-resources/funcionarios/capabilities",
                                        "update",
                                        "hr-v1",
                                        binding.evidence())),
                        List.of()));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                resourceDiscoveryService,
                null,
                null,
                null,
                objectMapper,
                null,
                null,
                bindingService,
                verificationService);
        AgenticAuthoringResourceSearchFocus focus = new AgenticAuthoringResourceSearchFocus(
                "human-resources.funcionarios", List.of("departamento"), "table", "", "LLM resolved scope");

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                        "needs_clarification",
                        new AgenticAuthoringResourceCandidatesRequest(
                                "funcionarios de TI", "mostre os funcionarios de TI", "table", 6, focus)),
                new AiPrincipalContext("tenant", "user", "local", true),
                "retrieveEvidence",
                "http://localhost:8088");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics())
                .containsEntry("candidateCount", 2)
                .containsEntry("retrievalSource", "domain_binding")
                .extractingByKey("resourceDiscoveryDiagnostics")
                .isInstanceOfSatisfying(Map.class, diagnostics -> assertThat(diagnostics)
                        .containsEntry("vectorRetrievalSkipped", true)
                        .containsEntry("bindingVerification", "schemas.filtered+resource.capabilities"));
        AgenticAuthoringResourceCandidatesResult payload =
                (AgenticAuthoringResourceCandidatesResult) result.payload();
        assertThat(payload.candidates())
                .extracting(
                        AgenticAuthoringCandidate::resourcePath,
                        AgenticAuthoringCandidate::submitUrl,
                        AgenticAuthoringCandidate::submitMethod)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "/api/human-resources/funcionarios",
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST"),
                        org.assertj.core.groups.Tuple.tuple(
                                "/api/human-resources/funcionarios",
                                "/api/human-resources/funcionarios/{id}",
                                "PUT"));
        assertThat(payload.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.evidenceBundle().retrievalSource()).isEqualTo("domain_binding");
            assertThat(candidate.evidenceBundle().evidence())
                    .extracting(AgenticAuthoringEvidenceBundle.Evidence::kind)
                    .contains("schema_grounding", "operation_grounding");
        });
        Mockito.verifyNoInteractions(resourceDiscoveryService);
    }

    @Test
    void exposesProgressiveDomainKnowledgeToolsWithGovernedReadOnlyScope() {
        AgenticAuthoringProjectKnowledgeService projectKnowledgeService =
                Mockito.mock(AgenticAuthoringProjectKnowledgeService.class);
        when(projectKnowledgeService.retrieve(Mockito.any())).thenReturn(List.of());
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                null,
                null,
                objectMapper,
                null,
                projectKnowledgeService);

        assertThat(registry.definitions())
                .filteredOn(definition -> definition.name().startsWith("discoverDomain"))
                .hasSize(3)
                .allSatisfy(definition -> {
                    assertThat(definition.allowedPhases()).containsExactly("retrieveEvidence");
                    assertThat(definition.sideEffectClass()).isEqualTo("read_only");
                    assertThat(definition.governanceLevel()).isEqualTo("governed_semantic_grounding");
                });
        assertThat(registry.definitions())
                .filteredOn(definition -> definition.name().equals("discoverDomainContexts"))
                .singleElement()
                .satisfies(definition -> assertThat(definition.ownerSurface())
                        .isEqualTo("praxis-config-starter:domain-catalog+domain-knowledge"));
        assertThat(registry.definitions())
                .filteredOn(definition -> definition.name().equals("discoverDomainCapabilities")
                        || definition.name().equals("discoverDomainConcepts"))
                .allSatisfy(definition -> assertThat(definition.ownerSurface())
                        .isEqualTo("praxis-config-starter:domain-knowledge"));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CAPABILITIES,
                        "component_authoring",
                        new DomainKnowledgeToolRequest("human-resources", null, 5)),
                new AiPrincipalContext("tenant-a", "user-a", "dev", true),
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        org.mockito.ArgumentCaptor<AgenticAuthoringProjectKnowledgeQuery> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(AgenticAuthoringProjectKnowledgeQuery.class);
        Mockito.verify(projectKnowledgeService).retrieve(queryCaptor.capture());
        assertThat(queryCaptor.getValue())
                .extracting(
                        AgenticAuthoringProjectKnowledgeQuery::tenantId,
                        AgenticAuthoringProjectKnowledgeQuery::environment,
                        AgenticAuthoringProjectKnowledgeQuery::contextKey,
                        AgenticAuthoringProjectKnowledgeQuery::nodeType,
                        AgenticAuthoringProjectKnowledgeQuery::limit)
                .containsExactly("tenant-a", "dev", "human-resources", "business_capability", 5);
    }

    @Test
    void domainContextDiscoveryEnumeratesGovernedCatalogWithoutUiHintsOrApprovedProjectKnowledge() {
        AgenticAuthoringProjectKnowledgeService projectKnowledgeService =
                Mockito.mock(AgenticAuthoringProjectKnowledgeService.class);
        DomainCatalogIngestionService domainCatalogIngestionService =
                Mockito.mock(DomainCatalogIngestionService.class);
        JsonNode contextPayload = objectMapper.createObjectNode()
                .put("contextKey", "human-resources")
                .put("label", "Recursos Humanos")
                .put("description", "Pessoas, cargos, departamentos e folha de pagamento.")
                .put("lifecycle", "active")
                .put("source", "openapi-group");
        when(domainCatalogIngestionService.contextLatest(
                "praxis-service",
                "tenant-a",
                "dev",
                "context",
                null,
                null,
                null,
                6))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        null,
                        "context",
                        null,
                        null,
                        List.of(),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-service:human-resources.funcionarios:release",
                                "context",
                                "human-resources",
                                "human-resources",
                                null,
                                null,
                                null,
                                contextPayload))));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                null,
                null,
                objectMapper,
                null,
                projectKnowledgeService,
                null,
                null,
                domainCatalogIngestionService,
                "praxis-service");

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CONTEXTS,
                        "advisory_authoring",
                        new DomainKnowledgeToolRequest(null, null, 6)),
                new AiPrincipalContext("tenant-a", "user-a", "dev", true),
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.payload()).isInstanceOf(List.class);
        assertThat((List<?>) result.payload())
                .singleElement()
                .isInstanceOfSatisfying(AgenticAuthoringProjectKnowledgeProjection.class, projection -> {
                    assertThat(projection.conceptKey()).isEqualTo("human-resources");
                    assertThat(projection.kind()).isEqualTo("context");
                    assertThat(projection.summary()).contains("Pessoas", "departamentos");
                    assertThat(projection.evidence()).contains("domain-catalog:context:human-resources");
                });
        Mockito.verifyNoInteractions(projectKnowledgeService);
    }

    @Test
    void domainContextDiscoveryUsesProjectKnowledgeOnlyWhenCanonicalCatalogIsEmpty() {
        AgenticAuthoringProjectKnowledgeService projectKnowledgeService =
                Mockito.mock(AgenticAuthoringProjectKnowledgeService.class);
        DomainCatalogIngestionService domainCatalogIngestionService =
                Mockito.mock(DomainCatalogIngestionService.class);
        when(domainCatalogIngestionService.contextLatest(
                "praxis-service",
                "tenant-a",
                "dev",
                "context",
                "human-resources",
                null,
                null,
                6))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        null,
                        "context",
                        "human-resources",
                        null,
                        List.of(),
                        List.of()));
        when(projectKnowledgeService.retrieve(Mockito.any())).thenReturn(List.of(
                new AgenticAuthoringProjectKnowledgeProjection(
                        "knowledge-1",
                        "human-resources",
                        "context",
                        new AgenticAuthoringProjectKnowledgeProjection.Scope(
                                "tenant-a", "dev", "human-resources", null),
                        new AgenticAuthoringProjectKnowledgeProjection.Status("active", "approved"),
                        "allow",
                        "project-knowledge",
                        "grounding",
                        "Contexto de recursos humanos.",
                        List.of("project-knowledge:human-resources"))));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                null,
                null,
                objectMapper,
                null,
                projectKnowledgeService,
                null,
                null,
                domainCatalogIngestionService,
                "praxis-service");

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CONTEXTS,
                        "advisory_authoring",
                        new DomainKnowledgeToolRequest("human-resources", null, 6)),
                new AiPrincipalContext("tenant-a", "user-a", "dev", true),
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat((List<?>) result.payload()).hasSize(1);
        org.mockito.ArgumentCaptor<AgenticAuthoringProjectKnowledgeQuery> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(AgenticAuthoringProjectKnowledgeQuery.class);
        Mockito.verify(projectKnowledgeService).retrieve(queryCaptor.capture());
        assertThat(queryCaptor.getValue().limit()).isEqualTo(6);
    }

    @Test
    void domainKnowledgeToolsFailClosedWithoutAuthenticatedScope() {
        AgenticAuthoringProjectKnowledgeService projectKnowledgeService =
                Mockito.mock(AgenticAuthoringProjectKnowledgeService.class);
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                null,
                null,
                objectMapper,
                null,
                projectKnowledgeService);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CONTEXTS,
                        "advisory_authoring",
                        new DomainKnowledgeToolRequest(null, null, 4)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-principal-scope-required");
        Mockito.verifyNoInteractions(projectKnowledgeService);
    }

    @Test
    void exposesPresentationAffordanceDiscoveryAsReadOnlyGroundingTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        assertThat(registry.definitions())
                .anySatisfy(definition -> {
                    assertThat(definition.name()).isEqualTo("presentationAffordanceDiscovery");
                    assertThat(definition.allowedRoutes())
                            .containsExactlyInAnyOrder(
                                    "component_authoring",
                                    "mixed",
                                    "needs_clarification",
                                    "advisory_authoring");
                    assertThat(definition.ownerSurface())
                            .isEqualTo("praxis-config-starter:ai-authoring/presentation-affordances");
                    assertThat(definition.allowedPhases())
                            .containsExactlyInAnyOrder("retrieveEvidence", "repairOrAsk");
                    assertThat(definition.sideEffectClass()).isEqualTo("read_only");
                    assertThat(definition.governanceLevel()).isEqualTo("safe_grounding");
                    assertThat(definition.auditRedactionPolicy()).isEqualTo("safe_event_projection_only");
                });
    }

    @Test
    void discoversStringTableColumnAffordancesWithoutDateFormats() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "presentationAffordanceDiscovery",
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                "praxis-table",
                                "column",
                                "statusPriority",
                                null,
                                "string",
                                null,
                                null,
                                "opcoes de formatacao para coluna calculada textual",
                                20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics())
                .containsEntry("componentId", "praxis-table")
                .containsEntry("targetKind", "column")
                .containsEntry("dataType", "string")
                .containsEntry("sourceRef", "@praxisui/core:ColumnDefinition");
        JsonNode payload = (JsonNode) result.payload();
        assertThat(payload.path("targetField").asText()).isEqualTo("statusPriority");
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains(
                        "column.align",
                        "column.renderer.badge",
                        "column.renderer.chip",
                        "column.renderer.icon",
                        "column.renderer.compose",
                        "column.conditionalRenderers")
                .doesNotContain("column.format.date");
    }

    @Test
    void discoversGenericTableColumnAffordancesWhenTypeIsUnknown() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "presentationAffordanceDiscovery",
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                "praxis-table",
                                "column",
                                "",
                                null,
                                null,
                                null,
                                null,
                                "quais recursos de apresentacao existem para colunas",
                                20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics())
                .containsEntry("dataType", "unknown")
                .containsEntry("requiresTypeConfirmation", true);
        JsonNode payload = (JsonNode) result.payload();
        assertThat(payload.path("requiresTypeConfirmation").asBoolean()).isTrue();
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains(
                        "column.align",
                        "column.renderer.badge",
                        "column.renderer.chip",
                        "column.renderer.compose")
                .doesNotContain("column.format.date", "column.format.numeric");
    }

    @Test
    void rejectsPresentationAffordanceDiscoveryWhenTargetHasNoProvider() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "presentationAffordanceDiscovery",
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                "praxis-unknown",
                                "field",
                                "statusPriority",
                                null,
                                "string",
                                null,
                                null,
                                "opcoes de formatacao para campo",
                                20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("presentation-affordance-target-unsupported");
    }

    @Test
    void discoversDynamicFormAffordancesThroughSameTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "presentationAffordanceDiscovery",
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                "praxis-dynamic-form",
                                null,
                                "observacaoInterna",
                                null,
                                null,
                                null,
                                null,
                                "quais opcoes visuais existem para campo",
                                20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        JsonNode payload = (JsonNode) result.payload();
        assertThat(payload.path("componentId").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(payload.path("targetKind").asText()).isEqualTo("field");
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains("field.label", "field.helperText", "field.layout");
    }

    @Test
    void getManifestSliceCanReturnPresentationAffordances() throws Exception {
        AgenticAuthoringManifestService manifestService = Mockito.mock(AgenticAuthoringManifestService.class);
        when(manifestService.getManifest("praxis-table")).thenReturn(objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "1.0.0",
                  "editableTargets": [],
                  "operations": [],
                  "validators": []
                }
                """));
        when(manifestService.listPresentationAffordances("praxis-table")).thenReturn(objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "defaultTargetKind": "column",
                  "affordances": [
                    { "id": "column.renderer.badge" }
                  ]
                }
                """));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                manifestService,
                null,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "getManifestSlice",
                        "component_authoring",
                        new ManifestSliceToolRequest("praxis-table", null, "presentationAffordances", 20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        JsonNode payload = (JsonNode) result.payload();
        assertThat(payload.path("sliceKind").asText()).isEqualTo("presentationAffordances");
        assertThat(payload.path("evidence").path("affordances").get(0).path("id").asText())
                .isEqualTo("column.renderer.badge");
    }

    @Test
    void executesSearchComponentCorpusThroughContextRetrievalService() {
        ContextRetrievalService contextRetrievalService = Mockito.mock(ContextRetrievalService.class);
        when(contextRetrievalService.searchComponentCorpus(
                eq("toolbar"),
                eq("praxis-table"),
                eq("capabilities"),
                eq(3),
                eq("tenant-a"),
                eq("prod"),
                eq("release-1")))
                .thenReturn(List.of(new ContextRetrievalService.ComponentCorpusEvidence(
                        "doc-1",
                        "praxis-table",
                        "component_definition",
                        "capabilities",
                        "praxis-ui-angular/projects/praxis-table/table.metadata.ts",
                        "release-1",
                        "tenant-a",
                        "prod",
                        "allow",
                        "hash-1",
                        "1.0.0",
                        "Toolbar capability docs",
                        0.91d)));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                contextRetrievalService,
                null,
                null,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchComponentCorpus",
                        "component_authoring",
                        new CorpusToolRequest(
                                "toolbar",
                                "praxis-table",
                                "capabilities",
                                null,
                                "tenant-a",
                                "prod",
                                "release-1",
                                3)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics())
                .containsEntry("evidenceCount", 1)
                .containsEntry("componentId", "praxis-table")
                .containsEntry("chunkKind", "capabilities")
                .containsEntry("releaseId", "release-1");
        assertThat((List<?>) result.payload()).hasSize(1);
    }

    @Test
    void executesSearchExamplesAsRecipeOnlyCorpusSearch() {
        ContextRetrievalService contextRetrievalService = Mockito.mock(ContextRetrievalService.class);
        when(contextRetrievalService.searchComponentCorpus(
                eq("component examples recipes"),
                eq("praxis-table"),
                eq("recipe"),
                eq(5),
                eq(null),
                eq(null),
                eq("release-1")))
                .thenReturn(List.of());
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                contextRetrievalService,
                null,
                null,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchExamples",
                        "component_authoring",
                        new CorpusToolRequest(
                                null,
                                "praxis-table",
                                null,
                                null,
                                null,
                                null,
                                "release-1",
                                5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics()).containsEntry("chunkKind", "recipe");
    }

    @Test
    void executesSearchSchemaFieldsWithoutApplyingPatch() throws Exception {
        SchemaRetrievalService schemaRetrievalService = Mockito.mock(SchemaRetrievalService.class);
        JsonNode schema = objectMapper.readTree("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        when(schemaRetrievalService.fetchSchemaResult(
                Mockito.any(org.praxisplatform.config.dto.AiSchemaContext.class),
                anyString(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(org.praxisplatform.config.service.SchemaFetchResult.success(
                        schema,
                        "http://localhost:8080/schemas/filtered"));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                null,
                schemaRetrievalService,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchSchemaFields",
                        "component_authoring",
                        new SchemaFieldsToolRequest(
                                "/employees",
                                "POST",
                                "request",
                                "name",
                                "http://localhost:8080",
                                5)),
                new AiPrincipalContext("tenant", "user", "local", true),
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics()).containsEntry("schemaFound", true);
        assertThat(((JsonNode) result.payload()).path("schema").path("properties").has("name")).isTrue();
    }

    @Test
    void rejectsReadOnlyCorpusToolWhenServiceIsUnavailable() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchComponentCorpus",
                        "component_authoring",
                        new CorpusToolRequest("toolbar", "praxis-table", null, null, null, null, "release-1", 5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-service-unavailable");
    }

    @Test
    void readOnlyCorpusToolsAreBlockedOutsideRetrievalPhases() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                Mockito.mock(ContextRetrievalService.class),
                null,
                null,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchComponentCorpus",
                        "component_authoring",
                        new CorpusToolRequest("toolbar", "praxis-table", null, null, null, null, "release-1", 5)),
                null,
                "applyPatch");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-phase-not-allowed");
    }
    @Test
    void executesSearchApiResourcesThroughRegistry() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "component_authoring",
                        new AgenticAuthoringResourceCandidatesRequest(
                                "graficos de folha de pagamento",
                                null,
                                "dashboard",
                                5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.tool()).isEqualTo("searchApiResources");
        assertThat(result.payload()).isInstanceOf(AgenticAuthoringResourceCandidatesResult.class);
        AgenticAuthoringResourceCandidatesResult payload =
                (AgenticAuthoringResourceCandidatesResult) result.payload();
        assertThat(payload.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.safeDiagnostics())
                .containsEntry("candidateCount", 1)
                .containsEntry("artifactKind", "dashboard")
                .containsEntry("retrievalQuery", "graficos de folha de pagamento")
                .containsEntry("retrievalSource", "lexical_fallback");
        assertThat(result.safeDiagnostics().get("resourceDiscoveryDiagnostics"))
                .isInstanceOfSatisfying(Map.class, diagnostics -> assertThat(diagnostics)
                        .containsKeys(
                                "catalogDiscoveryElapsedMs",
                                "groundingElapsedMs",
                                "consultativeProjectionElapsedMs",
                                "quickReplyElapsedMs",
                                "totalElapsedMs")
                        .containsEntry("catalogCandidateCount", 1)
                        .containsEntry("groundedCandidateCount", 1)
                        .containsEntry("limitedCandidateCount", 1));
    }

    @Test
    void rejectsToolExecutionOutsideDeclaredRouteScope() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "unsupported_route",
                        new AgenticAuthoringResourceCandidatesRequest("funcionarios", null, "form", 5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-route-not-allowed");
        assertThat(result.errorMessage()).contains("unsupported_route");
    }

    @Test
    void allowsPreIntentResourceDiscoveryRouteUsedByTurnEngine() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(new ApiMetadata(
                "/api/human-resources/funcionarios",
                "GET",
                "funcionarios,rh,pessoas",
                "Funcionários",
                "Cadastro e perfil de funcionarios",
                "listFuncionarios",
                null,
                "{\"type\":\"object\"}",
                "[]",
                "{}",
                null)));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "pre_intent_resource_discovery",
                        new AgenticAuthoringResourceCandidatesRequest(
                                "quero criar algo que mostre informacoes dos empregados",
                                "quero criar algo que mostre informacoes dos empregados",
                                "page",
                                6)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.tool()).isEqualTo("searchApiResources");
        assertThat(result.safeDiagnostics())
                .containsEntry("artifactKind", "page")
                .containsEntry("retrievalQuery", "quero criar algo que mostre informacoes dos empregados");
    }

    @Test
    void forwardsRequestBaseUrlToPreIntentResourceCapabilityGrounding() {
        AgenticAuthoringResourceDiscoveryService discoveryService =
                Mockito.mock(AgenticAuthoringResourceDiscoveryService.class);
        AgenticAuthoringResourceCandidatesResult discovered = new AgenticAuthoringResourceCandidatesResult(
                true,
                "searchApiResources",
                "funcionarios",
                "dashboard",
                "",
                List.of(),
                List.of(),
                List.of());
        AiPrincipalContext principal = new AiPrincipalContext("tenant", "user", "local", true);
        Mockito.when(discoveryService.search(
                        Mockito.any(),
                        Mockito.same(principal),
                        Mockito.eq("http://localhost:8088")))
                .thenReturn(discovered);
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(discoveryService);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "pre_intent_resource_discovery",
                        new AgenticAuthoringResourceCandidatesRequest(
                                "funcionarios",
                                "crie um painel de funcionarios",
                                "dashboard",
                                6)),
                principal,
                "retrieveEvidence",
                "http://localhost:8088");

        assertThat(result.valid()).isTrue();
        Mockito.verify(discoveryService).search(
                Mockito.any(),
                Mockito.same(principal),
                Mockito.eq("http://localhost:8088"));
    }

    @Test
    void rejectsToolExecutionWithoutExplicitPhase() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(new AgenticAuthoringToolCall(
                "searchApiResources",
                "component_authoring",
                new AgenticAuthoringResourceCandidatesRequest("funcionarios", null, "form", 5)));

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-phase-required");
    }

    @Test
    void rejectsToolExecutionOutsideDeclaredPhaseScope() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "component_authoring",
                        new AgenticAuthoringResourceCandidatesRequest("funcionarios", null, "form", 5)),
                null,
                "proposeDecision");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-phase-not-allowed");
        assertThat(result.errorMessage()).contains("proposeDecision");
    }

    @Test
    void resolvesRuntimeRelatedSurfaceResourceFromTargetWidgetComponent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/api/operations/missao-participantes/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": 10,
                            "funcionarioNome": "Ana Torres",
                            "papel": "LIDER",
                            "principal": true,
                            "missaoTitulo": "Operacao Aurora",
                            "cpf": "123.456.789-00",
                            "email": "ana@example.test",
                            "salario": 999999
                          }
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                    new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
            JsonNode runtimeContext = objectMapper.readTree("""
                    {
                      "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                      "availableSurfaces": ["missionTeam"],
                      "components": [
                        {
                          "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                          "refs": {"pageId": "page-builder-ia"},
                          "snapshot": {
                            "selectionDigest": {},
                            "relationSurfaceRefs": [
                              {
                                "id": "missionTeam",
                                "sourceWidget": "missionSummary",
                                "targetWidget": "missionTeam",
                                "targetResourcePath": "operations/missao-participantes",
                                "statePath": "selection.missionId",
                                "queryMapping": {
                                  "sourceField": "missaoId",
                                  "targetFilterField": "missaoId",
                                  "targetPath": "filters.missaoId",
                                  "valueSource": "selectionDigest.selectedIds[0]"
                                },
                                "operationId": "dynamicPage.surface.open"
                              }
                            ]
                          },
                          "affordances": {
                            "activeSurfaceRefs": ["missionTeam"],
                            "activeActionRefs": ["dynamicPage.surface.open"]
                          }
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                          "refs": {"resourcePath": "operations/vw-resumo-missoes"},
                          "snapshot": {
                            "selectionDigest": {
                              "selectedCount": 1,
                              "selectedIds": ["1"],
                              "idField": "missaoId"
                            }
                          },
                          "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "missionTeam"},
                          "refs": {"resourcePath": "operations/missao-participantes"},
                          "snapshot": {"schemaFieldRefs": ["funcionarioNome", "papel", "resultado"]}
                        }
                      ]
                    }
                    """);

            AgenticAuthoringToolResult result = registry.execute(
                    new AgenticAuthoringToolCall(
                            AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                            "advisory_authoring",
                            new RuntimeRelatedSurfaceReadToolRequest(
                                    runtimeContext,
                                    "missionTeam",
                                    null,
                                    null,
                                    "http://localhost:" + server.getAddress().getPort(),
                                    8)),
                    new AiPrincipalContext("tenant", "user", "local", true),
                    "retrieveEvidence");

            assertThat(result.valid())
                    .as("errorCode=%s errorMessage=%s", result.errorCode(), result.errorMessage())
                    .isTrue();
            assertThat(requestBody.get()).contains("\"missaoId\":1");
            JsonNode payload = (JsonNode) result.payload();
            assertThat(payload.path("resourcePath").asText()).isEqualTo("operations/missao-participantes");
            assertThat(payload.path("recordCount").asInt()).isEqualTo(1);
            assertThat(payload.path("queryMapping").path("targetPath").asText()).isEqualTo("filters.missaoId");
            assertThat(payload.path("projectionFields").toString()).contains("funcionarioNome", "papel");
            assertThat(payload.path("redactionApplied").asBoolean()).isTrue();
            assertThat(payload.path("records").toString())
                    .contains("Ana Torres")
                    .doesNotContain("Operacao Aurora")
                    .doesNotContain("123.456.789-00")
                    .doesNotContain("ana@example.test")
                    .doesNotContain("salario");
            assertThat(payload.path("rawRuntimeValuesCopied").asBoolean()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsGenericRuntimeRelatedSurfaceThroughDeclaredQueryMapping() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/api/sales/order-items/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": 301,
                            "orderId": 42,
                            "sku": "SKU-42-A",
                            "quantity": 3,
                            "email": "buyer@example.test",
                            "token": "hidden"
                          }
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                    new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
            JsonNode runtimeContext = objectMapper.readTree("""
                    {
                      "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                      "availableSurfaces": ["orderItems"],
                      "components": [
                        {
                          "identity": {"componentId": "praxis-dynamic-page", "instanceId": "orders-page"},
                          "snapshot": {
                            "relationSurfaceRefs": [
                              {
                                "id": "orderItems",
                                "sourceWidget": "ordersTable",
                                "targetWidget": "orderItems",
                                "targetResourcePath": "sales/order-items",
                                "queryMapping": {
                                  "sourceField": "orderId",
                                  "targetFilterField": "orderId",
                                  "targetPath": "filters.orderId",
                                  "valueSource": "selectionDigest.selectedIds[0]"
                                },
                                "operationId": "dynamicPage.surface.open"
                              }
                            ]
                          },
                          "affordances": {
                            "activeSurfaceRefs": ["orderItems"],
                            "activeActionRefs": ["dynamicPage.surface.open"]
                          }
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "ordersTable"},
                          "refs": {"resourcePath": "sales/orders"},
                          "snapshot": {
                            "selectionDigest": {
                              "selectedCount": 1,
                              "selectedIds": ["42"],
                              "idField": "orderId"
                            }
                          },
                          "affordances": {"activeSurfaceRefs": ["orderItems"]}
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "orderItems"},
                          "refs": {"resourcePath": "sales/order-items"},
                          "snapshot": {"schemaFieldRefs": ["orderId", "sku", "quantity"]}
                        }
                      ]
                    }
                    """);

            AgenticAuthoringToolResult result = registry.execute(
                    new AgenticAuthoringToolCall(
                            AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                            "advisory_authoring",
                            new RuntimeRelatedSurfaceReadToolRequest(
                                    runtimeContext,
                                    "orderItems",
                                    null,
                                    null,
                                    "http://localhost:" + server.getAddress().getPort(),
                                    8)),
                    new AiPrincipalContext("tenant", "user", "local", true),
                    "retrieveEvidence");

            assertThat(result.valid()).isTrue();
            assertThat(requestBody.get()).contains("\"orderId\":42");
            JsonNode payload = (JsonNode) result.payload();
            assertThat(payload.path("resourcePath").asText()).isEqualTo("sales/order-items");
            assertThat(payload.path("records").toString())
                    .contains("SKU-42-A")
                    .contains("quantity")
                    .doesNotContain("buyer@example.test")
                    .doesNotContain("hidden");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenRelationIsForged() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["payroll"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId",
                              "valueSource": "selectionDigest.selectedIds[0]"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["payroll"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    }
                  ]
                }
                """, "payroll", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-relation-not-declared");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWithoutSelection() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId",
                              "valueSource": "selectionDigest.selectedIds[0]"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 0, "selectedIds": [], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-selection-required");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWithoutDeclaredQueryMapping() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, "missaoId");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-query-mapping-required");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenSelectionIdFieldIsMissing() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"]}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, "missaoId");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-selection-id-required");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenSelectionHasMultipleIds() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 2, "selectedIds": ["1", "2"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-multiple-selection-unsupported");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenQueryMappingSourceFieldDiffersFromSelectionIdField() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missionId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-source-field-mismatch");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWithoutGovernedTargetResourcePathEvenWhenRequestSuggestsOne() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", "operations/missao-participantes", null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-resource-not-declared");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenDeclaredTargetWidgetIsMissing() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetWidget": "missingMissionTeam",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-target-widget-not-found");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenTargetFilterFieldIsNotDeclaredByTargetSurface() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetWidget": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "cpf"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionTeam"},
                      "refs": {"resourcePath": "operations/missao-participantes"},
                      "snapshot": {"schemaFieldRefs": ["missaoId", "funcionarioNome", "papel"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-target-filter-field-not-declared");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenTargetPathIsNotSafeFilterIdentifier() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missao-id"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-target-path-invalid");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenTargetPathDiffersFromTargetFilterField() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.outraCoisa"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-target-path-filter-mismatch");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenTargetWidgetIsMissingEvenWithResourcePathAndQueryMappingDeclared() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/api/operations/missao-participantes/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {"id": 10, "missaoId": 1, "funcionarioNome": "Ana Torres", "papel": "LIDER"}
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                    new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
            JsonNode runtimeContext = objectMapper.readTree("""
                    {
                      "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                      "availableSurfaces": ["missionTeam"],
                      "components": [
                        {
                          "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                          "snapshot": {
                            "relationSurfaceRefs": [
                              {
                                "id": "missionTeam",
                                "sourceWidget": "missionSummary",
                                "targetWidget": "missingMissionTeam",
                                "targetResourcePath": "operations/missao-participantes",
                                "queryMapping": {
                                  "sourceField": "missaoId",
                                  "targetFilterField": "missaoId",
                                  "targetPath": "filters.missaoId"
                                },
                                "operationId": "dynamicPage.surface.open"
                              }
                            ]
                          },
                          "affordances": {
                            "activeSurfaceRefs": ["missionTeam"],
                            "activeActionRefs": ["dynamicPage.surface.open"]
                          }
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                          "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                          "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                        }
                      ]
                    }
                    """);

            AgenticAuthoringToolResult result = registry.execute(
                    new AgenticAuthoringToolCall(
                            AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                            "advisory_authoring",
                            new RuntimeRelatedSurfaceReadToolRequest(
                                    runtimeContext,
                                    "missionTeam",
                                    null,
                                    null,
                                    "http://localhost:" + server.getAddress().getPort(),
                                    8)),
                    new AiPrincipalContext("tenant", "user", "local", true),
                    "retrieveEvidence");

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("runtime-surface-target-widget-not-found");
            assertThat(requestBody.get()).isBlank();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenSurfaceCapabilityIsNotActive() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {"activeActionRefs": ["dynamicPage.surface.open"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-not-active");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenOperationCapabilityIsNotActive() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-operation-not-active");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenResourcePathMatchesMultipleRuntimeSurfacesWithoutCanonicalIdentity() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionTeamA"},
                      "refs": {"resourcePath": "operations/missao-participantes"},
                      "snapshot": {"schemaFieldRefs": ["funcionarioNome", "papel"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionTeamB"},
                      "refs": {"resourcePath": "operations/missao-participantes"},
                      "snapshot": {"schemaFieldRefs": ["funcionarioNome", "papel"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-resource-path-ambiguous");
    }

    private AgenticAuthoringToolResult executeRuntimeRelatedSurfaceRead(
            String runtimeContextJson,
            String surfaceRef,
            String requestResourcePath,
            String filterField) throws Exception {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
        return registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                        "advisory_authoring",
                        new RuntimeRelatedSurfaceReadToolRequest(
                                objectMapper.readTree(runtimeContextJson),
                                surfaceRef,
                                requestResourcePath,
                                filterField,
                                "http://localhost:1",
                                8)),
                new AiPrincipalContext("tenant", "user", "local", true),
                "retrieveEvidence");
    }

    @Test
    void returnsStructuredFailureForInvalidPayload() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "component_authoring",
                        "funcionarios"),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-payload-invalid");
        assertThat(result.errorMessage()).contains("AgenticAuthoringResourceCandidatesRequest");
    }

    @Test
    void returnsStructuredFailureForUnknownTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "unknownTool",
                        "component_authoring",
                        null),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-not-found");
    }
}
