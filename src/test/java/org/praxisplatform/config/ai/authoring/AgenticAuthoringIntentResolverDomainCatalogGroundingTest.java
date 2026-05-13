package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.service.DomainCatalogIngestionService;

@Tag("unit")
class AgenticAuthoringIntentResolverDomainCatalogGroundingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolverTreatsDomainCatalogResourceKeyHintAsGovernedResourceSelection() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(0, String.class);
                    if (query.contains("operations") && query.contains("missoes")) {
                        return List.of(verifiedCandidate(
                                "/api/operations/missoes",
                                "/api/operations/missoes/filter/cursor",
                                "operations missoes"));
                    }
                    return List.of(lexicalCandidate(
                            "/api/human-resources/vw-resumo-missoes",
                            "/api/human-resources/vw-resumo-missoes/filter/cursor",
                            "missoes",
                            0.47d));
                });
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                Mockito.mock(AgenticAuthoringDomainCatalogCandidateEnhancer.class);
        Mockito.when(enhancer.hasResourceKey("operations.missoes", "default", "dev")).thenReturn(true);
        AgenticAuthoringIntentResolverService resolver = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                new AgenticAuthoringComponentCapabilitiesService(),
                "praxis-service",
                enhancer);

        AgenticAuthoringIntentResolutionResult result = resolver.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "monte uma tabela de missoes operacionais",
                        "praxis-ui-angular",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        domainCatalogHint("operations.missoes", "table")),
                "default",
                "user",
                "dev");

        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/operations/missoes");
        assertThat(result.selectedCandidate().submitUrl()).isEqualTo("/api/operations/missoes/filter/cursor");
        assertThat(result.selectedCandidate().evidence())
                .contains(
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING,
                        "domain-catalog-context")
                .doesNotContain("lexical-fallback", "weak-evidence");
        assertThat(result.selectedCandidate().evidenceBundle().retrievalSource()).isEqualTo("domain_catalog");
        assertThat(result.llmDiagnostics().path("resolutionTelemetry")
                .path("selectedCandidateUsesDomainCatalogGrounding").asBoolean()).isTrue();
    }

    @Test
    void resolverKeepsGovernanceInquiryConsultativeInsteadOfRoutingToRuleAuthoring() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(List.of(lexicalCandidate()));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                Mockito.mock(AgenticAuthoringDomainCatalogCandidateEnhancer.class);
        Mockito.when(enhancer.hasResourceKey("human-resources.funcionarios", "default", "dev")).thenReturn(true);
        AgenticAuthoringIntentResolverService resolver = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                new AgenticAuthoringComponentCapabilitiesService(),
                "praxis-service",
                enhancer);

        AgenticAuthoringIntentResolutionResult result = resolver.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Quais campos de funcionarios tem governanca LGPD ou devem ser mascarados?",
                        "praxis-ui-angular",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        domainCatalogHint("human-resources.funcionarios", "table")),
                "default",
                "user",
                "dev");

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.gate()).isNotNull();
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.gate().messages()).doesNotContain("shared-rule-authoring-required");
        assertThat(result.assistantMessage()).contains("fonte de negocio");
        assertThat(result.assistantMessage()).doesNotContain("/schemas/", "/api/");
        assertThat(result.assistantMessage()).doesNotContain("Nao consegui concluir", "grounding estruturado", "LLM/RAG");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void resolverDoesNotMintTrustedCandidateFromUnverifiedClientDomainCatalogHint() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(List.of());
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                Mockito.mock(AgenticAuthoringDomainCatalogCandidateEnhancer.class);
        Mockito.when(enhancer.hasResourceKey("procurement.suppliers", "default", "dev")).thenReturn(false);
        AgenticAuthoringIntentResolverService resolver = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                new AgenticAuthoringComponentCapabilitiesService(),
                "praxis-service",
                enhancer);

        AgenticAuthoringIntentResolutionResult result = resolver.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "monte uma tabela de fornecedores",
                        "praxis-ui-angular",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        domainCatalogHint("procurement.suppliers", "table")),
                "default",
                "user",
                "dev");

        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates()).noneMatch(candidate ->
                candidate.evidence() != null
                        && candidate.evidence().contains(
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING));
    }

    @Test
    void resolverPromotesPersistedDomainCatalogGroundingBeforeLexicalFallbackSelection() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(List.of(lexicalCandidate()));
        DomainCatalogIngestionService domainCatalogIngestionService = Mockito.mock(DomainCatalogIngestionService.class);
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.eq("praxis-service"),
                        Mockito.eq("human-resources.funcionarios"),
                        Mockito.eq("default"),
                        Mockito.eq("dev"),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.eq(8)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "crie uma tabela de funcionarios",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-service:human-resources.funcionarios:test",
                                "node",
                                "human-resources.funcionarios",
                                "human-resources",
                                "resource",
                                null,
                                null,
                                objectMapper.createObjectNode().put("label", "Funcionarios")))));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                new AgenticAuthoringDomainCatalogCandidateEnhancer(domainCatalogIngestionService, "praxis-service");
        AgenticAuthoringIntentResolverService resolver = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                new AgenticAuthoringComponentCapabilitiesService(),
                "praxis-service",
                enhancer);

        AgenticAuthoringIntentResolutionResult result = resolver.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "crie uma tabela de funcionarios",
                        "praxis-ui-angular",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        null,
                        null),
                "default",
                "user",
                "dev");

        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().evidence())
                .contains(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                .doesNotContain("lexical-fallback", "weak-evidence");
        assertThat(result.selectedCandidate().evidenceBundle().retrievalSource()).isEqualTo("domain_catalog");
        assertThat(result.warnings())
                .contains("resource-selection-domain-catalog-grounding-selected")
                .doesNotContain("resource-selection-lexical-fallback-selected");
        assertThat(result.llmDiagnostics().path("resolutionTelemetry")
                .path("selectedCandidateUsesDomainCatalogGrounding").asBoolean()).isTrue();
        assertThat(result.semanticDecision().reviewRequired()).isFalse();
    }

    private AgenticAuthoringCandidate lexicalCandidate() {
        return lexicalCandidate(
                "/api/human-resources/funcionarios",
                "/api/human-resources/funcionarios/filter",
                "funcionarios",
                0.48d);
    }

    private AgenticAuthoringCandidate lexicalCandidate(
            String resourcePath,
            String submitUrl,
            String matchedTerm,
            double score) {
        return new AgenticAuthoringCandidate(
                resourcePath,
                "post",
                "/schemas/filtered?path=" + submitUrl + "&operation=post&schemaType=response",
                submitUrl,
                "post",
                score,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"),
                AgenticAuthoringEvidenceBundle.of(
                        "lexical_fallback",
                        List.of(
                                new AgenticAuthoringEvidenceBundle.Evidence(
                                        "api_metadata",
                                        "weak_lexical_match",
                                        resourcePath,
                                        matchedTerm,
                                        score,
                                        List.of(matchedTerm),
                                        "default",
                                        "dev",
                                        ""),
                                new AgenticAuthoringEvidenceBundle.Evidence(
                                        "/schemas/filtered",
                                        "schema_probe_pending",
                                        "/schemas/filtered?path=" + submitUrl + "&operation=post&schemaType=response",
                                        "Canonical filtered schema for the selected operation.",
                                        0.35d,
                                        List.of(matchedTerm),
                                        "default",
                                        "dev",
                                        ""))));
    }

    private AgenticAuthoringCandidate verifiedCandidate(
            String resourcePath,
            String submitUrl,
            String matchedTerm) {
        return new AgenticAuthoringCandidate(
                resourcePath,
                "post",
                "/schemas/filtered?path=" + submitUrl + "&operation=post&schemaType=response",
                submitUrl,
                "post",
                0.78d,
                "api_metadata verified resource candidate",
                List.of("api-metadata", "semantic-retrieval", "schema-available", "actions-probe-pending"),
                AgenticAuthoringEvidenceBundle.of(
                        "semantic_retrieval",
                        List.of(new AgenticAuthoringEvidenceBundle.Evidence(
                                "api_metadata",
                                "retrieved_candidate",
                                resourcePath,
                                matchedTerm,
                                0.78d,
                                List.of(matchedTerm),
                                "default",
                                "dev",
                                ""))));
    }

    private ObjectNode domainCatalogHint(String resourceKey, String artifactKind) {
        ObjectNode hints = objectMapper.createObjectNode();
        hints.put("artifactKind", artifactKind);
        ObjectNode domainCatalog = hints.putObject("domainCatalog");
        domainCatalog.put("resourceKey", resourceKey);
        return hints;
    }
}
