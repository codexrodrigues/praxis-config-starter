package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        return request(identity, candidates, "Crie uma tabela operacional de missões.");
    }

    private AgenticAuthoringIntentResolutionRequest request(String identity,
            List<AgenticAuthoringCandidate> candidates, String prompt) {
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
                prompt, "praxis-ui-angular", "praxis-dynamic-page-builder",
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
            assertThat(deduplicate(binding, catalog)).containsExactly(catalog, binding);
            assertThat(deduplicate(catalog, binding)).containsExactly(catalog, binding);
            assertThat(binding.evidence()).doesNotContain(CATALOG);
            assertThat(rejected(deduplicate(binding, catalog).get(0))).isTrue();
        }
    }

    @Test
    void scopedAndUnscopedCandidatesCannotTransferConfirmation() {
        var scopedBinding = withBundle(binding(), "domain_binding", "tenant-a", "local", "release-a");
        var catalog = candidate(PATH, .9, CATALOG);
        assertThat(deduplicate(scopedBinding, catalog)).containsExactly(catalog, scopedBinding);
        var scopedCatalog = withBundle(catalog, "domain_catalog", "tenant-a", "local", "release-a");
        assertThat(deduplicate(binding(), scopedCatalog)).containsExactly(scopedCatalog, binding());
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
            assertThat(deduplicate(binding, incompatible)).containsExactly(incompatible, binding);
            assertThat(deduplicate(incompatible, binding)).containsExactly(incompatible, binding);
            assertThat(incompatible.evidence()).doesNotContain("domain-binding");
            assertThat(rejected(incompatible)).isTrue();
        }
    }

    @Test
    void sameSourceCannotMergeFocusAcrossDifferentOperations() {
        var catalog = candidate(PATH, .99, CATALOG);
        var focusedOtherOperation = new AgenticAuthoringCandidate(PATH, "get", catalog.schemaUrl(),
                catalog.submitUrl(), "GET", .9, "fixture", List.of(CATALOG, FOCUS));
        assertThat(deduplicate(catalog, focusedOtherOperation)).containsExactly(catalog, focusedOtherOperation);
        assertThat(rejected(deduplicate(catalog, focusedOtherOperation).get(0))).isTrue();
    }

    @Test
    void sameSourceCannotMergeFocusAcrossDifferentScopes() {
        var catalog = withBundle(candidate(PATH, .99, CATALOG), "domain_catalog", "tenant-a", "local", "r1");
        var focusedOtherTenant = withBundle(candidate(PATH, .9, CATALOG, FOCUS), "domain_catalog", "tenant-b", "local", "r1");
        assertThat(deduplicate(catalog, focusedOtherTenant)).containsExactly(catalog, focusedOtherTenant);
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
        assertThat(deduplicate(mixed, catalog)).containsExactly(catalog, mixed);
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

    private AgenticAuthoringCandidate operationBinding(String role, String method, String endpoint, String operationId) {
        String schema = "http://127.0.0.1:8088/schemas/filtered?path=" + endpoint
                + "&operation=" + method + "&schemaType=" + ("get".equals(method) ? "response" : "request");
        String bindingRef = "binding:operations.missoes.surface." + role + ":ui-surface";
        var bundle = AgenticAuthoringEvidenceBundle.of("domain_binding", List.of(
                new AgenticAuthoringEvidenceBundle.Evidence("domain_knowledge_binding", "retrieved_candidate",
                        bindingRef, "Constructed verified surface.", 1, List.of("operations.missoes"),
                        "tenant", "local", "release-a"),
                new AgenticAuthoringEvidenceBundle.Evidence("/schemas/filtered", "schema_grounding",
                        schema, "Constructed canonical schema proof.", 1, List.of(method),
                        "tenant", "local", "release-a"),
                new AgenticAuthoringEvidenceBundle.Evidence("capabilities", "operation_grounding",
                        PATH + "/capabilities", "Constructed principal capability proof.", 1, List.of(operationId),
                        "tenant", "local", "release-a")));
        return new AgenticAuthoringCandidate(PATH, method, schema,
                "get".equals(method) ? null : endpoint, "get".equals(method) ? null : method.toUpperCase(Locale.ROOT),
                1, "Constructed governed operation.", List.of("domain-knowledge:binding:" + bindingRef,
                        "domain-binding", "schema-grounding-verified", "resource-capabilities-verified"), bundle);
    }

    private List<AgenticAuthoringCandidate> fourSurfaceBindings() {
        return List.of(operationBinding("create", "post", PATH, "create"),
                operationBinding("detail", "get", PATH + "/{id}", "byId"),
                operationBinding("edit", "put", PATH + "/{id}", "update"),
                operationBinding("list", "get", PATH + "/all", "all"));
    }

    private AgenticAuthoringCandidate documentaryOtherRelease() {
        var catalog = candidate(PATH, .9, CATALOG, "api-metadata", "semantic-retrieval", "schema-available",
                "actions-probe-pending", "semantic-role:operational-resource");
        return new AgenticAuthoringCandidate(catalog.resourcePath(), catalog.operation(), catalog.schemaUrl(),
                catalog.submitUrl(), catalog.submitMethod(), catalog.score(), catalog.reason(), catalog.evidence(),
                AgenticAuthoringEvidenceBundle.of("domain_catalog", List.of(
                        new AgenticAuthoringEvidenceBundle.Evidence("domain_catalog", "domain_catalog_grounding",
                                "documentary-resource", "Constructed documentary release.", .9, List.of(),
                                "tenant", "local", "release-b"),
                        new AgenticAuthoringEvidenceBundle.Evidence("api_metadata", "retrieved_candidate",
                                PATH, "Constructed metadata projection.", .9, List.of(), "tenant", "local", ""))));
    }

    private void verifiedOperations(AgenticAuthoringIntentResolutionRequest request,
            List<AgenticAuthoringCandidate> bindings) {
        var operations = bindings.stream().map(binding -> {
            var proof = binding.evidenceBundle().evidence();
            String schema = binding.schemaUrl();
            String endpoint = schema.substring(schema.indexOf("?path=") + 6, schema.indexOf("&operation="));
            return new AgenticAuthoringOperationalBindingVerificationService.OperationProjection(
                    "operations.missoes", proof.get(0).ref(), "resource_operation", "operations.missoes",
                    binding.resourcePath(), endpoint, binding.operation(),
                    "get".equals(binding.operation()) ? "response" : "request", schema, proof.get(2).ref(),
                    proof.get(2).matchedTerms().get(0), "", "", "principal_capability",
                    new AgenticAuthoringOperationalBindingVerificationService.AvailabilityProjection(
                            true, "", "resource_capabilities"), proof.get(0).releaseId(), binding.evidence());
        }).toList();
        var stream = new AgenticAuthoringTurnStreamRequest(request.userPrompt(), request.targetApp(),
                request.targetComponentId(), request.currentRoute(), request.currentPage(), request.selectedWidgetKey(),
                request.provider(), null, null, "fixture-session", "fixture-turn", null, null, null,
                request.contextHints(), null, null);
        var engine = new AgenticAuthoringTurnEngine(null, null, mapper, new AgenticAuthoringCurrentPageAnalyzer(mapper), null);
        AgenticAuthoringTurnStreamRequest enriched = ReflectionTestUtils.invokeMethod(engine,
                "withVerifiedOperationContext", stream, operations);
        ((ObjectNode) request.contextHints()).set("verifiedDomainOperations",
                enriched.contextHints().path("verifiedDomainOperations").deepCopy());
    }

    private AgenticAuthoringIntentResolutionResult resolveFourSurfaces(
            String focusIdentity, boolean resolved, List<List<AgenticAuthoringCandidate>> modelInputs) {
        return resolveFourSurfaces(focusIdentity, resolved, modelInputs, true);
    }

    private AgenticAuthoringIntentResolutionResult resolveFourSurfaces(
            String focusIdentity, boolean resolved, List<List<AgenticAuthoringCandidate>> modelInputs,
            boolean verifiedEnvelope) {
        var bindings = fourSurfaceBindings();
        var catalog = documentaryOtherRelease();
        var model = Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(model.resolve(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> {
                    modelInputs.add(List.copyOf(invocation.getArgument(4)));
                    boolean first = modelInputs.size() == 1;
                    return Optional.of(new AgenticAuthoringLlmIntentResolution(resolved,
                            resolved ? first ? "explore" : "create" : "unknown", resolved ? "page" : "unknown",
                            resolved ? first ? "discover_artifact" : "create_artifact" : "needs_clarification",
                            resolved ? PATH : null, resolved && first ? "governed resource scope" : null, "none",
                            "Constructed semantic resolution.", List.of(), List.of(), List.of()));
                });
        var metadata = Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        Mockito.when(metadata.discover(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                        Mockito.anyString(), Mockito.isNull())).thenReturn(List.of(catalog));
        var enhancer = Mockito.mock(AgenticAuthoringDomainCatalogCandidateEnhancer.class);
        Mockito.when(enhancer.enhance(Mockito.anyString(), Mockito.anyList(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> {
                    List<AgenticAuthoringCandidate> documentary = invocation.getArgument(1);
                    if (verifiedEnvelope) {
                        assertThat(documentary).noneMatch(item -> item.evidence().contains("domain-binding"));
                    }
                    return documentary;
                });
        var resolver = new AgenticAuthoringIntentResolverService(mapper, metadata, model, null,
                AgenticAuthoringDomainCatalogHints.DEFAULT_SERVICE_KEY, enhancer, false);
        var request = request(focusIdentity, bindings, "Prepare the governed workspace.");
        ((ObjectNode) request.contextHints().path("resourceDiscovery")).put("artifactKind", "page");
        if (verifiedEnvelope) {
            verifiedOperations(request, bindings);
            List<AgenticAuthoringCandidate> preserved = ReflectionTestUtils.invokeMethod(
                    resolver, "contextHintCandidates", request);
            assertThat(preserved).hasSize(4);
            for (var binding : bindings) {
                Object operation = ReflectionTestUtils.invokeMethod(resolver, "verifiedOperationForCandidate", request, binding);
                assertThat(operation).as("verified operation %s", binding.schemaUrl()).isNotNull();
            }
        }
        ObjectNode constraints = mapper.createObjectNode().put("appliesToDataSelection", false);
        constraints.putArray("filters");
        var orientation = new AgenticAuthoringPreIntentToolPlan(
                "praxis-agentic-authoring-pre-intent-tool-plan.v3", "Constructed full page orientation.", List.of(),
                "authoring_or_other", "", true, constraints, "page", "praxis-tabs");
        return resolver.resolve(request, "tenant", "user", "local", orientation);
    }

    @Test
    void publicResolverPreservesFourVerifiedOperationsThroughDocumentaryRefinement() {
        List<List<AgenticAuthoringCandidate>> inputs = new ArrayList<>();
        var result = resolveFourSurfaces("operations.missoes", true, inputs);
        assertThat(inputs).hasSize(2);
        for (int phase = 0; phase < inputs.size(); phase++) {
            var input = inputs.get(phase);
            var bindings = input.stream().filter(item -> item.evidence().contains("domain-binding")).toList();
            assertThat(bindings).as("provider resolution phase %s", phase + 1).hasSize(4);
            for (var original : fourSurfaceBindings()) {
                var preserved = bindings.stream().filter(item -> item.schemaUrl().equals(original.schemaUrl()))
                        .findFirst().orElseThrow();
                assertThat(preserved.operation()).isEqualTo(original.operation());
                assertThat(preserved.submitUrl()).isEqualTo(original.submitUrl());
                assertThat(preserved.submitMethod()).isEqualTo(original.submitMethod());
                assertThat(preserved.evidenceBundle()).isEqualTo(original.evidenceBundle());
                assertThat(preserved.evidence()).doesNotContain(CATALOG);
            }
        }
        var documentary = inputs.get(1).stream().filter(item -> item.evidence().contains(CATALOG)).findFirst().orElseThrow();
        assertThat(documentary).isEqualTo(documentaryOtherRelease());
        assertThat(documentary.evidence()).doesNotContain("domain-binding", "schema-grounding-verified");
        assertThat(result.valid()).as("warnings=%s; failures=%s", result.warnings(), result.failureCodes()).isTrue();
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.warnings()).doesNotContain(BLOCK);
        // Public presentation remains one selected resource; the operational alternatives
        // above must survive in both semantic passes, rather than becoming UI duplicates.
        assertThat(result.candidates()).containsExactly(result.selectedCandidate());
        assertThat(result.selectedCandidate().schemaUrl()).isEqualTo(fourSurfaceBindings().get(3).schemaUrl());
        assertThat(result.selectedCandidate().submitUrl()).isNull();
        assertThat(result.selectedCandidate().submitMethod()).isNull();
        assertThat(result.selectedCandidate().evidenceBundle()).isEqualTo(fourSurfaceBindings().get(3).evidenceBundle());
    }

    @Test
    void preservedOperationsCannotOverrideUnresolvedIntentOrMismatchedAiFocus() {
        var unresolved = resolveFourSurfaces("operations.missoes", false, new ArrayList<>());
        assertThat(unresolved.valid()).isFalse();
        assertThat(unresolved.selectedCandidate()).isNull();
        assertThat(unresolved.failureCodes()).contains("intent-operation-unknown", "intent-artifact-unknown");
        var mismatch = resolveFourSurfaces("operations.incidentes", true, new ArrayList<>());
        assertThat(mismatch.valid()).isFalse();
        assertThat(mismatch.selectedCandidate()).isNull();
        assertThat(mismatch.warnings()).contains(BLOCK);
    }

    @Test
    void verifiedCollectionReconciliationRejectsDifferentPrincipalScopeAndAmbiguousReleases() {
        var request = request("operations.missoes", fourSurfaceBindings());
        verifiedOperations(request, fourSurfaceBindings());
        var resolution = new AgenticAuthoringLlmIntentResolution(true, "create", "page", "create_artifact", PATH,
                null, "none", "Constructed semantic resolution.", List.of(), List.of(), List.of());
        var list = fourSurfaceBindings().get(3);
        AgenticAuthoringCandidate foreign = ReflectionTestUtils.invokeMethod(service, "verifiedCollectionForResolvedIntent",
                request, resolution, "page", List.of(list), "different-tenant", "local");
        assertThat(foreign).isNull();
        var anotherRelease = new AgenticAuthoringCandidate(list.resourcePath(), list.operation(), list.schemaUrl(),
                list.submitUrl(), list.submitMethod(), list.score(), list.reason(), list.evidence(),
                AgenticAuthoringEvidenceBundle.of("domain_binding", list.evidenceBundle().evidence().stream()
                        .map(item -> new AgenticAuthoringEvidenceBundle.Evidence(item.source(), item.kind(), item.ref(),
                                item.summary(), item.confidence(), item.matchedTerms(), item.tenantId(),
                                item.environment(), "release-c")).toList()));
        verifiedOperations(request, List.of(list, anotherRelease));
        AgenticAuthoringCandidate ambiguous = ReflectionTestUtils.invokeMethod(service, "verifiedCollectionForResolvedIntent",
                request, resolution, "page", List.of(list, anotherRelease), "tenant", "local");
        assertThat(ambiguous).isNull();
    }

    @Test
    void frontendMarkersWithoutBackendVerificationCannotReconcileDocumentarySelection() {
        var result = resolveFourSurfaces("operations.missoes", true, new ArrayList<>(), false);
        assertThat(result.valid()).isFalse();
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.warnings()).contains(BLOCK);
    }

    @Test
    void mismatchedBackendOperationOrBundleCannotPreserveAnAuthoritativeProjection() {
        var list = fourSurfaceBindings().get(3);
        for (String field : List.of("apiMethod", "schemaUrl", "bindingKey", "metadataUrl", "operationId", "sourceRelease")) {
            var request = request("operations.missoes", List.of(list));
            verifiedOperations(request, List.of(list));
            ((ObjectNode) request.contextHints().path("verifiedDomainOperations").path("entries").get(0))
                    .put(field, "mismatched");
            Object projection = ReflectionTestUtils.invokeMethod(service, "verifiedOperationForCandidate", request, list);
            assertThat(projection).as(field).isNull();
        }
        var request = request("operations.missoes", List.of(list));
        verifiedOperations(request, List.of(list));
        var noBundle = new AgenticAuthoringCandidate(list.resourcePath(), list.operation(), list.schemaUrl(),
                list.submitUrl(), list.submitMethod(), list.score(), list.reason(), list.evidence());
        Object projection = ReflectionTestUtils.invokeMethod(service, "verifiedOperationForCandidate", request, noBundle);
        assertThat(projection).isNull();
    }


    @Test
    void absentOptionalReleaseMustMatchExactlyWithoutInventingARelease() {
        var original = fourSurfaceBindings().get(3);
        var noRelease = new AgenticAuthoringCandidate(original.resourcePath(), original.operation(), original.schemaUrl(),
                original.submitUrl(), original.submitMethod(), original.score(), original.reason(), original.evidence(),
                AgenticAuthoringEvidenceBundle.of("domain_binding", original.evidenceBundle().evidence().stream()
                        .map(item -> new AgenticAuthoringEvidenceBundle.Evidence(item.source(), item.kind(), item.ref(),
                                item.summary(), item.confidence(), item.matchedTerms(), item.tenantId(),
                                item.environment(), "")).toList()));
        var request = request("operations.missoes", List.of(noRelease));
        verifiedOperations(request, List.of(noRelease));
        Object projection = ReflectionTestUtils.invokeMethod(service, "verifiedOperationForCandidate", request, noRelease);
        assertThat(projection).isNotNull();
        ((ObjectNode) request.contextHints().path("verifiedDomainOperations").path("entries").get(0))
                .put("sourceRelease", "release-b");
        Object mismatch = ReflectionTestUtils.invokeMethod(service, "verifiedOperationForCandidate", request, noRelease);
        assertThat(mismatch).isNull();
    }


    @Test
    void resourceFocusMarginComparesBusinessResourcesRatherThanTheirOperationAlternatives() {
        String employees = "/api/human-resources/funcionarios";
        String payroll = "/api/human-resources/vw-analytics-folha-pagamento";
        var employeeRead = candidate(employees, .61, "semantic-retrieval", "tool-search-api-resources",
                "semantic-role:operational-resource");
        var employeeStats = new AgenticAuthoringCandidate(employees, "post",
                "/schemas/filtered?path=" + employees + "/stats/group-by&operation=post&schemaType=response",
                employees + "/stats/group-by", "POST", .61, "Constructed same-resource operation.",
                employeeRead.evidence());
        var payrollProjection = candidate(payroll, .71, "semantic-retrieval", "tool-search-api-resources",
                "semantic-role:analytics-projection");
        var oneOperation = List.of(payrollProjection, employeeRead);
        var twoOperations = List.of(payrollProjection, employeeRead, employeeStats);
        for (var alternatives : List.of(oneOperation, twoOperations)) {
            var request = request("funcionarios", alternatives);
            ((ObjectNode) request.contextHints().path("resourceDiscovery")).put("artifactKind", "dashboard");
            ((ObjectNode) request.contextHints().path("resourceDiscovery").path("resourceSearchFocus"))
                    .put("desiredSurface", "dashboard analitico por departamento e cargo");
            AgenticAuthoringCandidate focused = ReflectionTestUtils.invokeMethod(service,
                    "resourceDiscoveryFocusedCandidate", request, "Governed analytical surface.", "dashboard", alternatives);
            assertThat(focused).isSameAs(employeeRead);
            assertThat(alternatives).contains(employeeRead, payrollProjection);
            assertThat(employeeStats.resourcePath()).isEqualTo(employeeRead.resourcePath());
            assertThat(employeeStats.schemaUrl()).isNotEqualTo(employeeRead.schemaUrl());
        }
        var payrollFocus = request("folha pagamento", twoOperations);
        ((ObjectNode) payrollFocus.contextHints().path("resourceDiscovery")).put("artifactKind", "dashboard");
        ((ObjectNode) payrollFocus.contextHints().path("resourceDiscovery").path("resourceSearchFocus"))
                .put("desiredSurface", "dashboard analitico da folha de pagamento");
        AgenticAuthoringCandidate focusedPayroll = ReflectionTestUtils.invokeMethod(service,
                "resourceDiscoveryFocusedCandidate", payrollFocus, "Governed analytical surface.", "dashboard", twoOperations);
        assertThat(focusedPayroll).isSameAs(payrollProjection);
    }

    @Test
    void exactDashboardPipelineKeepsPrimaryEntityAheadOfAnUnrelatedAnalyticalRole() {
        var fixture = new AgenticAuthoringIntentResolverServiceTest();
        AgenticAuthoringIntentResolverService resolver = (AgenticAuthoringIntentResolverService)
                ReflectionTestUtils.getField(fixture, "service");
        AgenticAuthoringCandidate employee = ReflectionTestUtils.invokeMethod(fixture, "candidateWithEvidence",
                "/api/human-resources/funcionarios", .61d, List.of("funcionarios", "cargo", "departamento", "detalhes"));
        employee = ReflectionTestUtils.invokeMethod(fixture, "withEvidence", employee, "tool-search-api-resources");
        employee = ReflectionTestUtils.invokeMethod(fixture, "withEvidence", employee, "semantic-role:operational-resource");
        AgenticAuthoringCandidate payroll = ReflectionTestUtils.invokeMethod(fixture, "candidateWithEvidence",
                "/api/human-resources/vw-analytics-folha-pagamento", .71d,
                List.of("analytics", "folha", "pagamento", "cargo", "departamento", "metricas"));
        payroll = ReflectionTestUtils.invokeMethod(fixture, "withEvidence", payroll, "tool-search-api-resources");
        payroll = ReflectionTestUtils.invokeMethod(fixture, "withEvidence", payroll, "semantic-role:analytics-projection");
        var focus = new AgenticAuthoringResourceSearchFocus("funcionarios",
                List.of("departamento", "cargo", "distribuicao", "metricas"),
                "dashboard analitico com distribuicao por departamento e cargo", "",
                "The employee registry is the primary subject; analytics are its presentation.");
        ObjectNode hints = ReflectionTestUtils.invokeMethod(fixture, "resourceDiscoveryContext", "dashboard",
                List.of(payroll, employee), focus);
        String prompt = "quero um dashboard analitico de funcionarios por departamento e cargo";
        AgenticAuthoringIntentResolutionRequest request = ReflectionTestUtils.invokeMethod(fixture,
                "requestWithContextHints", prompt, "openai", hints);
        var result = resolver.resolve(request);
        List<AgenticAuthoringCandidate> initial = ReflectionTestUtils.invokeMethod(resolver, "discoverCandidates",
                prompt, "dashboard", null, null, null);
        List<AgenticAuthoringCandidate> merged = ReflectionTestUtils.invokeMethod(resolver,
                "withContextHintCandidates", request, initial);
        List<AgenticAuthoringCandidate> ranked = ReflectionTestUtils.invokeMethod(resolver,
                "rankCandidatesForDecision", merged, "dashboard", prompt);
        var initialChoice = ReflectionTestUtils.<AgenticAuthoringCandidate>invokeMethod(resolver,
                "selectCandidate", ranked, null, "create", "dashboard", prompt);
        var focused = ReflectionTestUtils.<AgenticAuthoringCandidate>invokeMethod(resolver,
                "resourceDiscoveryFocusedCandidate", request, prompt, "dashboard", ranked);
        assertThat(initialChoice.resourcePath()).isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(focused.resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        Boolean preserve = ReflectionTestUtils.invokeMethod(resolver, "shouldPreserveSelectedProjectionForSemanticNeed",
                request, prompt, "dashboard", initialChoice, focused);
        Boolean prefer = ReflectionTestUtils.invokeMethod(resolver, "shouldPreferResourceDiscoveryFocusedCandidate",
                request, focused, initialChoice);
        assertThat(preserve).isFalse();
        assertThat(prefer).isTrue();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");

        // The analytical projection remains appropriate when the AI focus actually selects payroll.
        var payrollHints = hints.deepCopy();
        ((ObjectNode) payrollHints.path("resourceDiscovery").path("resourceSearchFocus"))
                .put("primaryBusinessEntity", "folha de pagamento")
                .put("desiredSurface", "dashboard analitico de folha de pagamento por departamento");
        String payrollPrompt = "quero um dashboard analitico de folha de pagamento por departamento";
        AgenticAuthoringIntentResolutionRequest payrollRequest = ReflectionTestUtils.invokeMethod(fixture,
                "requestWithContextHints", payrollPrompt, "openai", payrollHints);
        Boolean preservePayroll = ReflectionTestUtils.invokeMethod(resolver,
                "shouldPreserveSelectedProjectionForSemanticNeed", payrollRequest, payrollPrompt,
                "dashboard", initialChoice, focused);
        Boolean preferEmployee = ReflectionTestUtils.invokeMethod(resolver,
                "shouldPreferResourceDiscoveryFocusedCandidate", payrollRequest, focused, initialChoice);
        assertThat(preservePayroll).isTrue();
        assertThat(preferEmployee).isFalse();
        assertThat(resolver.resolve(payrollRequest).selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
    }

}
