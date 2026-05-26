package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.service.DomainCatalogIngestionService;

@Tag("unit")
class AgenticAuthoringDomainCatalogCandidateEnhancerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void promotesCandidateWhenGovernedDomainCatalogContextMatchesResource() {
        DomainCatalogIngestionService domainCatalogIngestionService = Mockito.mock(DomainCatalogIngestionService.class);
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.eq("praxis-service"),
                        Mockito.eq("human-resources.funcionarios"),
                        Mockito.eq("default"),
                        Mockito.eq("dev"),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq("funcionarios"),
                        Mockito.eq(8)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "funcionarios",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(item("node", "human-resources.funcionarios", "Funcionarios"))));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                new AgenticAuthoringDomainCatalogCandidateEnhancer(domainCatalogIngestionService, "praxis-service");

        AgenticAuthoringCandidate lexicalCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "/schemas/filtered?resource=/api/human-resources/funcionarios&operation=post",
                "/api/human-resources/funcionarios",
                "post",
                0.48d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"),
                AgenticAuthoringEvidenceBundle.of(
                        "lexical_fallback",
                        List.of(new AgenticAuthoringEvidenceBundle.Evidence(
                                "api_metadata",
                                "weak_lexical_match",
                                "/api/human-resources/funcionarios",
                                "lexical",
                                0.48d,
                                List.of("funcionarios"),
                                "default",
                                "dev",
                                ""))));

        List<AgenticAuthoringCandidate> enhanced =
                enhancer.enhance("funcionarios", List.of(lexicalCandidate), "default", "dev");

        assertThat(enhanced).hasSize(1);
        AgenticAuthoringCandidate selected = enhanced.get(0);
        assertThat(selected.score()).isGreaterThan(0.80d);
        assertThat(selected.evidence())
                .contains(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                .contains("semantic-retrieval")
                .doesNotContain("lexical-fallback", "weak-evidence");
        assertThat(selected.evidenceBundle().retrievalSource()).isEqualTo("domain_catalog");
        assertThat(selected.evidenceBundle().evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.source()).isEqualTo("domain_catalog");
                    assertThat(evidence.kind()).isEqualTo("domain_catalog_grounding");
                    assertThat(evidence.ref()).isEqualTo("human-resources.funcionarios");
                });
    }

    @Test
    void keepsLexicalCandidateWhenDomainCatalogHasNoMatchingContext() {
        DomainCatalogIngestionService domainCatalogIngestionService = Mockito.mock(DomainCatalogIngestionService.class);
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.anyInt()))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "fornecedores",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of()));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                new AgenticAuthoringDomainCatalogCandidateEnhancer(domainCatalogIngestionService, "praxis-service");
        AgenticAuthoringCandidate lexicalCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "",
                "/api/human-resources/funcionarios",
                "post",
                0.48d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"));

        List<AgenticAuthoringCandidate> enhanced =
                enhancer.enhance("fornecedores", List.of(lexicalCandidate), "default", "dev");

        assertThat(enhanced.get(0).evidence())
                .contains("lexical-fallback")
                .doesNotContain(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING);
        assertThat(enhanced.get(0).score()).isEqualTo(0.48d);
    }

    @Test
    void doesNotFanOutToTenantCatalogsWhenConfiguredServiceKeyHasNoContext() {
        DomainCatalogIngestionService domainCatalogIngestionService = Mockito.mock(DomainCatalogIngestionService.class);
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.eq("praxis-service"),
                        Mockito.eq("human-resources.funcionarios"),
                        Mockito.eq("default"),
                        Mockito.eq("dev"),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq("funcionarios"),
                        Mockito.eq(8)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "funcionarios",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of()));
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.eq(""),
                        Mockito.eq("human-resources.funcionarios"),
                        Mockito.eq("default"),
                        Mockito.eq("dev"),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq("funcionarios"),
                        Mockito.eq(8)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "funcionarios",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(item("node", "human-resources.funcionarios", "Funcionarios"))));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                new AgenticAuthoringDomainCatalogCandidateEnhancer(domainCatalogIngestionService, "praxis-service");
        AgenticAuthoringCandidate lexicalCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "",
                "/api/human-resources/funcionarios",
                "post",
                0.48d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"));

        List<AgenticAuthoringCandidate> enhanced =
                enhancer.enhance("funcionarios", List.of(lexicalCandidate), "default", "dev");

        assertThat(enhanced.get(0).evidence())
                .contains("lexical-fallback", "weak-evidence")
                .doesNotContain(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING);
        Mockito.verify(domainCatalogIngestionService, Mockito.never()).contextLatest(
                Mockito.eq(""),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyInt());
    }

    @Test
    void promotesCandidateUsingMeaningfulUserPromptTermsWithoutResourceKeyFallback() {
        DomainCatalogIngestionService domainCatalogIngestionService = Mockito.mock(DomainCatalogIngestionService.class);
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.anyString(),
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
                        "",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of()));
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.eq("praxis-service"),
                        Mockito.eq("human-resources.funcionarios"),
                        Mockito.eq("default"),
                        Mockito.eq("dev"),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq("pessoas"),
                        Mockito.eq(8)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "pessoas",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(item("node", "human-resources.funcionarios.surface.list", "Lista de pessoas"))));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                new AgenticAuthoringDomainCatalogCandidateEnhancer(domainCatalogIngestionService, "praxis-service");
        AgenticAuthoringCandidate lexicalCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "",
                "/api/human-resources/funcionarios",
                "post",
                0.48d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"));

        List<AgenticAuthoringCandidate> enhanced = enhancer.enhance(
                "Antes de criar qualquer coisa, me explique quais dados existem sobre pessoas, cargos e folha.",
                List.of(lexicalCandidate),
                "default",
                "dev");

        assertThat(enhanced.get(0).evidence())
                .contains(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                .doesNotContain("lexical-fallback", "weak-evidence");
        Mockito.verify(domainCatalogIngestionService, Mockito.never()).contextLatest(
                Mockito.anyString(),
                Mockito.eq("human-resources.funcionarios"),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.eq("funcionarios"),
                Mockito.anyInt());
    }

    @Test
    void carriesHostFieldNameFromDomainCatalogBindingEvidence() {
        DomainCatalogIngestionService domainCatalogIngestionService = Mockito.mock(DomainCatalogIngestionService.class);
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.eq("praxis-service"),
                        Mockito.eq("procurement.suppliers"),
                        Mockito.eq("default"),
                        Mockito.eq("dev"),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq("status fornecedores"),
                        Mockito.eq(8)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "status fornecedores",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(itemWithTargetField(
                                "binding",
                                "binding:procurement.suppliers.field.status:dto-field",
                                "Status do fornecedor",
                                "supplierStatus"))));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                new AgenticAuthoringDomainCatalogCandidateEnhancer(domainCatalogIngestionService, "praxis-service");
        AgenticAuthoringCandidate lexicalCandidate = new AgenticAuthoringCandidate(
                "/api/procurement/suppliers",
                "get",
                "",
                "/api/procurement/suppliers/filter",
                "post",
                0.48d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"));

        AgenticAuthoringCandidate enhanced = enhancer.enhance(
                        "status fornecedores",
                        List.of(lexicalCandidate),
                        "default",
                        "dev")
                .get(0);

        assertThat(enhanced.evidenceBundle().evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.summary()).contains("field=supplierStatus");
                    assertThat(evidence.matchedTerms()).contains("supplierstatus");
                });
    }

    @Test
    void doesNotPromoteWeakCandidateUsingOnlyItsOwnResourceKeyFallback() {
        DomainCatalogIngestionService domainCatalogIngestionService = Mockito.mock(DomainCatalogIngestionService.class);
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.anyString(),
                        Mockito.eq("human-resources.funcionarios"),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.eq("fornecedores"),
                        Mockito.anyInt()))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "fornecedores",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of()));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                new AgenticAuthoringDomainCatalogCandidateEnhancer(domainCatalogIngestionService, "praxis-service");
        AgenticAuthoringCandidate unrelatedWeakCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "",
                "/api/human-resources/funcionarios",
                "post",
                0.48d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"));

        List<AgenticAuthoringCandidate> enhanced =
                enhancer.enhance("fornecedores", List.of(unrelatedWeakCandidate), "default", "dev");

        assertThat(enhanced.get(0).evidence())
                .contains("lexical-fallback", "weak-evidence")
                .doesNotContain(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING);
        Mockito.verify(domainCatalogIngestionService, Mockito.never()).contextLatest(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.eq("human resources funcionarios"),
                Mockito.anyInt());
        Mockito.verify(domainCatalogIngestionService, Mockito.never()).contextLatest(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.eq("funcionarios"),
                Mockito.anyInt());
    }

    @Test
    void boundsDomainCatalogGroundingAttemptsForLargeCandidateSets() {
        DomainCatalogIngestionService domainCatalogIngestionService = Mockito.mock(DomainCatalogIngestionService.class);
        Mockito.when(domainCatalogIngestionService.contextLatest(
                        Mockito.anyString(),
                        Mockito.anyString(),
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
                        "",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of()));
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                new AgenticAuthoringDomainCatalogCandidateEnhancer(domainCatalogIngestionService, "praxis-service");
        List<AgenticAuthoringCandidate> candidates = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> new AgenticAuthoringCandidate(
                        "/api/domain/resource-" + index,
                        "get",
                        "",
                        "/api/domain/resource-" + index,
                        "get",
                        0.40d,
                        "api_metadata weak lexical fallback evidence",
                        List.of("api-metadata", "lexical-fallback", "weak-evidence")))
                .toList();

        List<AgenticAuthoringCandidate> enhanced =
                enhancer.enhance("folha pagamento pessoas cargos", candidates, "default", "dev");

        assertThat(enhanced).hasSize(12);
        Mockito.verify(domainCatalogIngestionService, Mockito.times(18)).contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.anyString(),
                Mockito.eq("default"),
                Mockito.eq("dev"),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.eq(8));
    }

    private DomainCatalogItemResponse item(String itemType, String itemKey, String label) {
        return new DomainCatalogItemResponse(
                UUID.randomUUID(),
                "praxis-service:human-resources.funcionarios:test",
                itemType,
                itemKey,
                "human-resources",
                null,
                null,
                null,
                objectMapper.createObjectNode().put("label", label));
    }

    private DomainCatalogItemResponse itemWithTargetField(
            String itemType,
            String itemKey,
            String label,
            String fieldName) {
        var payload = objectMapper.createObjectNode();
        payload.put("label", label);
        payload.putObject("target").put("fieldName", fieldName);
        return new DomainCatalogItemResponse(
                UUID.randomUUID(),
                "praxis-service:procurement.suppliers:test",
                itemType,
                itemKey,
                "procurement",
                null,
                "dto_field",
                null,
                payload);
    }
}
