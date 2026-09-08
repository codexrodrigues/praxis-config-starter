package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** Deterministic reconciliation regressions, not a replay of a private live LLM response. */
@Tag("unit")
class AgenticAuthoringResourceFocusReconciliationTest {
    private static final String PATH = "/api/operations/missoes";
    private static final String CATALOG = "domain-catalog-grounding";
    private static final String FOCUS = "llm-resource-focus";
    private static final String BLOCK = "llm-resource-selection-unconfirmed-by-ai-authored-focus";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgenticAuthoringIntentResolverService service = new AgenticAuthoringIntentResolverService(mapper);

    private AgenticAuthoringCandidate candidate(String path, double score, String... evidence) {
        return new AgenticAuthoringCandidate(path, "post",
                "/schemas/filtered?path=" + path + "/filter/cursor&operation=post&schemaType=response",
                path + "/filter/cursor", "POST", score, "Constructed governed candidate.", List.of(evidence));
    }

    private AgenticAuthoringIntentResolutionRequest request(String identity, List<AgenticAuthoringCandidate> candidates) {
        ObjectNode hints = mapper.createObjectNode();
        ObjectNode discovery = hints.putObject("resourceDiscovery");
        discovery.put("tool", AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
        discovery.put("artifactKind", "table");
        discovery.put("retrievalQuery", "LLM-authored resource scope");
        discovery.set("candidates", mapper.valueToTree(candidates));
        discovery.putObject("resourceSearchFocus")
                .put("primaryBusinessEntity", identity)
                .put("uncertainty", "Confirm the governed source.")
                .put("desiredSurface", "operational table")
                .put("rationale", "AI-authored focus fixture");
        return new AgenticAuthoringIntentResolutionRequest(
                "Crie uma tabela operacional de missões.", "praxis-ui-angular", "praxis-dynamic-page-builder",
                "/page-builder-ia", mapper.createObjectNode(), null, "deterministic-smoke-disabled",
                null, null, null, null, null, null, null, hints);
    }

    private boolean rejected(AgenticAuthoringCandidate candidate) {
        Boolean result = ReflectionTestUtils.invokeMethod(service, "hasUnconfirmedAiAuthoredResourceFocus",
                request("operations.missoes", List.of()), candidate);
        return Boolean.TRUE.equals(result);
    }

    private List<AgenticAuthoringCandidate> deduplicate(AgenticAuthoringCandidate... candidates) {
        return ReflectionTestUtils.invokeMethod(service, "deduplicateCandidates", List.of(candidates));
    }

    @Test
    void exactFocusNeedsItsConfirmingEvidenceWhenUncertaintyRemains() {
        assertThat(rejected(candidate(PATH, .9, CATALOG))).isTrue();
        assertThat(rejected(candidate(PATH, .9, CATALOG, FOCUS))).isFalse();
        assertThat(rejected(candidate(PATH, .9, "domain-binding"))).isFalse();
    }

    @Test
    void absentCandidateAndMismatchedIdentityRemainBlocked() {
        assertThat(rejected(null)).isTrue();
        assertThat(rejected(candidate("/api/operations/incidentes", 1, CATALOG, FOCUS, "domain-binding"))).isTrue();
    }

    @Test
    void sameSourceDuplicatesRetainTheFocusRegardlessOfInputOrder() {
        var catalog = candidate(PATH, .99, CATALOG);
        var focusedCatalog = candidate(PATH, .9, CATALOG, FOCUS);
        for (var candidates : List.of(List.of(catalog, focusedCatalog), List.of(focusedCatalog, catalog))) {
            var selected = deduplicate(candidates.toArray(AgenticAuthoringCandidate[]::new));
            assertThat(selected).hasSize(1);
            assertThat(selected.get(0).evidence()).contains(CATALOG, FOCUS);
            assertThat(rejected(selected.get(0))).isFalse();
        }
    }

    @Test
    void documentaryCatalogCannotDiscardAnAlreadyVerifiedCompatibleBinding() {
        var binding = candidate(PATH, 1, "domain-binding", "schema-grounding-verified", "resource-capabilities-verified");
        var catalog = candidate(PATH, .9, CATALOG);
        assertThat(rejected(binding)).isFalse();
        for (var candidates : List.of(List.of(binding, catalog), List.of(catalog, binding))) {
            var selected = deduplicate(candidates.toArray(AgenticAuthoringCandidate[]::new));
            assertThat(selected).hasSize(1);
            assertThat(selected.get(0)).isSameAs(binding);
            assertThat(selected.get(0).evidence()).contains("domain-binding").doesNotContain(CATALOG);
            assertThat(rejected(selected.get(0))).isFalse();
        }
    }

    @Test
    void lexicalDuplicatesDoNotInjectFocusIntoAGovernedCandidate() {
        var catalog = candidate(PATH, .9, CATALOG);
        var lexical = candidate(PATH, 1, "lexical-fallback", "weak-evidence", FOCUS);
        var selected = deduplicate(catalog, lexical).get(0);
        assertThat(selected.evidence()).doesNotContain(FOCUS, "lexical-fallback", "weak-evidence");
        assertThat(rejected(selected)).isTrue();
    }

    @Test
    void differentResourcePathsNeverShareEvidence() {
        var selected = deduplicate(candidate(PATH, .9, CATALOG),
                candidate("/api/operations/incidentes", 1, CATALOG, FOCUS, "domain-binding"));
        assertThat(selected).hasSize(2);
        assertThat(selected.stream().filter(item -> item.resourcePath().equals(PATH)).findFirst().orElseThrow().evidence())
                .doesNotContain(FOCUS, "domain-binding");
    }

    private AgenticAuthoringIntentResolutionResult resolve(List<AgenticAuthoringCandidate> candidates) {
        var model = Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(model.resolve(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true, "create", "table", "create_artifact", PATH, null, "none",
                        "Governed resolution fixture.", List.of(), List.of(), List.of())));
        var resolver = new AgenticAuthoringIntentResolverService(mapper,
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class), model, null,
                AgenticAuthoringDomainCatalogHints.DEFAULT_SERVICE_KEY, null, false);
        ObjectNode constraints = mapper.createObjectNode().put("appliesToDataSelection", false);
        constraints.putArray("filters");
        var orientation = new AgenticAuthoringPreIntentToolPlan(
                "praxis-agentic-authoring-pre-intent-tool-plan.v3", "Governed table intent.", List.of(),
                "authoring_or_other", "", true, constraints, "table", "praxis-table");
        var result = resolver.resolve(request("operations.missoes", candidates), "tenant", "user", "local", orientation);
        Mockito.verify(model).resolve(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        return result;
    }

    @Test
    void publicResolverAcceptsTheVerifiedBindingControl() {
        var result = resolve(List.of(candidate(PATH, 1, "domain-binding", "schema-grounding-verified", "resource-capabilities-verified")));
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).doesNotContain(BLOCK);
    }

    @Test
    void publicResolverRejectsCatalogWithoutFocusAtTheEarlyGate() {
        var result = resolve(List.of(candidate(PATH, .9, CATALOG)));
        assertThat(result.valid()).isFalse();
        // The early focus gate resets resolved=false. This is not the live smoke's late-gate signature.
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("llmResolved").asBoolean()).isFalse();
        assertThat(result.warnings()).contains(BLOCK);
        assertThat(result.operationKind()).isEqualTo("unknown");
        assertThat(result.artifactKind()).isEqualTo("unknown");
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void publicResolverRetainsVerifiedBindingWhenCompatibleCatalogIsAdded() {
        var result = resolve(List.of(
                candidate(PATH, 1, "domain-binding", "schema-grounding-verified", "resource-capabilities-verified"),
                candidate(PATH, .9, CATALOG)));
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).doesNotContain(BLOCK);
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("llmResolved").asBoolean()).isTrue();
        assertThat(result.selectedCandidate().evidence()).contains("domain-binding").doesNotContain(CATALOG);
    }

    private AgenticAuthoringCandidate binding() {
        return candidate(PATH, 1, "domain-binding", "schema-grounding-verified", "resource-capabilities-verified");
    }

    private AgenticAuthoringCandidate withBundle(AgenticAuthoringCandidate candidate, String source,
            String tenant, String environment, String release) {
        return new AgenticAuthoringCandidate(candidate.resourcePath(), candidate.operation(), candidate.schemaUrl(),
                candidate.submitUrl(), candidate.submitMethod(), candidate.score(), candidate.reason(), candidate.evidence(),
                AgenticAuthoringEvidenceBundle.of(source, List.of(
                        new AgenticAuthoringEvidenceBundle.Evidence(source, "retrieved_candidate", "fixture-ref",
                                "Constructed evidence, not an HTTP authorization proof.", 1, List.of(),
                                tenant, environment, release))));
    }

    @Test
    void scopedCompatibleBindingPreservesItsOwnBundleAndProvenance() {
        var binding = withBundle(binding(), "domain_binding", "tenant-a", "local", "release-a");
        var catalog = withBundle(candidate(PATH, .9, CATALOG), "domain_catalog", "tenant-a", "local", "release-a");
        assertThat(deduplicate(binding, catalog)).containsExactly(binding);
        assertThat(deduplicate(catalog, binding).get(0)).isSameAs(binding);
        assertThat(resolve(List.of(binding, catalog)).valid()).isTrue();
    }

    @Test
    void differentTenantEnvironmentOrReleaseCannotTransferConfirmation() {
        var binding = withBundle(binding(), "domain_binding", "tenant-a", "local", "release-a");
        for (var scope : List.of(List.of("tenant-b", "local", "release-a"),
                List.of("tenant-a", "production", "release-a"), List.of("tenant-a", "local", "release-b"))) {
            var catalog = withBundle(candidate(PATH, .9, CATALOG), "domain_catalog", scope.get(0), scope.get(1), scope.get(2));
            assertThat(deduplicate(binding, catalog)).containsExactly(catalog);
            assertThat(deduplicate(catalog, binding)).containsExactly(catalog);
            assertThat(rejected(deduplicate(binding, catalog).get(0))).isTrue();
        }
    }

    @Test
    void scopedAndUnscopedCandidatesCannotTransferConfirmation() {
        var scopedBinding = withBundle(binding(), "domain_binding", "tenant-a", "local", "release-a");
        var catalog = candidate(PATH, .9, CATALOG);
        assertThat(deduplicate(scopedBinding, catalog)).containsExactly(catalog);
        var scopedCatalog = withBundle(catalog, "domain_catalog", "tenant-a", "local", "release-a");
        assertThat(deduplicate(binding(), scopedCatalog)).containsExactly(scopedCatalog);
    }

    @Test
    void conflictingBundleSourceDoesNotPromoteBindingMarkers() {
        var catalog = candidate(PATH, .9, CATALOG);
        var contradictory = withBundle(binding(), "lexical_fallback", "", "", "");
        assertThat(deduplicate(contradictory, catalog)).containsExactly(catalog);
    }

    @Test
    void incompleteOrWeakBindingDoesNotOutrankCatalog() {
        var catalog = candidate(PATH, .9, CATALOG);
        for (var incomplete : List.of(candidate(PATH, 1, "domain-binding"),
                candidate(PATH, 1, "domain-binding", "schema-grounding-verified"),
                candidate(PATH, 1, "domain-binding", "resource-capabilities-verified"),
                candidate(PATH, 1, "domain-binding", "schema-grounding-verified", "resource-capabilities-verified", "weak-evidence"))) {
            assertThat(deduplicate(incomplete, catalog)).containsExactly(catalog);
        }
    }

    @Test
    void differentOperationSchemaEndpointOrMethodCannotTransferConfirmation() {
        var binding = binding();
        var catalog = candidate(PATH, .9, CATALOG);
        for (var incompatible : List.of(
                new AgenticAuthoringCandidate(PATH, "get", catalog.schemaUrl(), catalog.submitUrl(), "POST", .9, "fixture", catalog.evidence()),
                new AgenticAuthoringCandidate(PATH, "post", catalog.schemaUrl() + "&variant=other", catalog.submitUrl(), "POST", .9, "fixture", catalog.evidence()),
                new AgenticAuthoringCandidate(PATH, "post", catalog.schemaUrl(), PATH + "/other", "POST", .9, "fixture", catalog.evidence()),
                new AgenticAuthoringCandidate(PATH, "post", catalog.schemaUrl(), catalog.submitUrl(), "PUT", .9, "fixture", catalog.evidence()))) {
            assertThat(deduplicate(binding, incompatible)).containsExactly(incompatible);
            assertThat(deduplicate(incompatible, binding)).containsExactly(incompatible);
            assertThat(rejected(incompatible)).isTrue();
        }
    }

    @Test
    void sameSourceCannotMergeFocusAcrossDifferentOperations() {
        var catalog = candidate(PATH, .99, CATALOG);
        var focusedOtherOperation = new AgenticAuthoringCandidate(PATH, "get", catalog.schemaUrl(),
                catalog.submitUrl(), "GET", .9, "fixture", List.of(CATALOG, FOCUS));
        assertThat(deduplicate(catalog, focusedOtherOperation)).containsExactly(catalog);
        assertThat(rejected(deduplicate(catalog, focusedOtherOperation).get(0))).isTrue();
    }

    @Test
    void sameSourceCannotMergeFocusAcrossDifferentScopes() {
        var catalog = withBundle(candidate(PATH, .99, CATALOG), "domain_catalog", "tenant-a", "local", "r1");
        var focusedOtherTenant = withBundle(candidate(PATH, .9, CATALOG, FOCUS), "domain_catalog", "tenant-b", "local", "r1");
        assertThat(deduplicate(catalog, focusedOtherTenant)).containsExactly(catalog);
        assertThat(rejected(deduplicate(catalog, focusedOtherTenant).get(0))).isTrue();
    }

    @Test
    void mixedScopeBundleCannotPromoteBinding() {
        var scoped = withBundle(binding(), "domain_binding", "tenant-a", "local", "r1");
        var other = withBundle(binding(), "domain_binding", "tenant-b", "local", "r1");
        var mixed = new AgenticAuthoringCandidate(scoped.resourcePath(), scoped.operation(), scoped.schemaUrl(),
                scoped.submitUrl(), scoped.submitMethod(), scoped.score(), scoped.reason(), scoped.evidence(),
                AgenticAuthoringEvidenceBundle.of("domain_binding", List.of(
                        scoped.evidenceBundle().evidence().get(0), other.evidenceBundle().evidence().get(0))));
        var catalog = withBundle(candidate(PATH, .9, CATALOG), "domain_catalog", "tenant-a", "local", "r1");
        assertThat(deduplicate(mixed, catalog)).containsExactly(catalog);
    }

    @Test
    void absentStructuralIdentityCannotPromoteBinding() {
        var binding = binding();
        for (String absent : new String[] {null, "", " "}) {
            var incompleteBinding = new AgenticAuthoringCandidate(PATH, "post", absent,
                    binding.submitUrl(), binding.submitMethod(), 1, "fixture", binding.evidence());
            var incompleteCatalog = new AgenticAuthoringCandidate(PATH, "post", absent,
                    binding.submitUrl(), binding.submitMethod(), .9, "fixture", List.of(CATALOG));
            assertThat(deduplicate(incompleteBinding, incompleteCatalog)).containsExactly(incompleteCatalog);
        }
    }

    @Test
    void matchingReadCandidatesDoNotRequireAMutationEndpoint() {
        var binding = new AgenticAuthoringCandidate(PATH, "get", "/schemas/filtered?path=" + PATH + "&operation=get",
                null, null, 1, "read fixture", binding().evidence());
        var catalog = new AgenticAuthoringCandidate(PATH, "get", binding.schemaUrl(),
                null, null, .9, "read fixture", List.of(CATALOG));
        assertThat(deduplicate(catalog, binding)).containsExactly(binding);
    }

    @Test
    void httpMethodCasingDoesNotCreateADifferentOperation() {
        var binding = binding();
        var catalog = new AgenticAuthoringCandidate(PATH, "POST", binding.schemaUrl(),
                binding.submitUrl(), "post", .9, "fixture", List.of(CATALOG));
        assertThat(deduplicate(catalog, binding)).containsExactly(binding);
    }

    @Test
    void addingWeakThirdCandidateDoesNotContaminateCompatibleBinding() {
        var binding = binding();
        var catalog = candidate(PATH, .9, CATALOG);
        var weak = candidate(PATH, 1, "lexical-fallback", "weak-evidence", FOCUS);
        for (var permutation : List.of(List.of(binding, catalog, weak), List.of(binding, weak, catalog),
                List.of(catalog, binding, weak), List.of(catalog, weak, binding),
                List.of(weak, binding, catalog), List.of(weak, catalog, binding))) {
            assertThat(deduplicate(permutation.toArray(AgenticAuthoringCandidate[]::new))).containsExactly(binding);
        }
    }
}
