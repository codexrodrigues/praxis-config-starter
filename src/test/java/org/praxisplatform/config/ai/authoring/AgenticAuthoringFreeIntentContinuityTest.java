package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Deterministic boundary proof: semantic outputs are stubs, not evidence of LLM accuracy. */
@Tag("unit")
class AgenticAuthoringFreeIntentContinuityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgenticAuthoringIntentResolverService resolver =
            new AgenticAuthoringIntentResolverService(mapper, null);

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void aPredicateDoesNotAuthorizeSkippingTheRequestedFullSemanticPass(String domain) {
        Boolean required = ReflectionTestUtils.invokeMethod(resolver,
                "requiresAdditionalIntentResolution", orientation(constraints(domain, false)));
        assertThat(required).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void fullResolutionRetainsAdditionalPredicatesAndExclusions(String domain) {
        ObjectNode initial = constraints(domain, false);
        ObjectNode refined = constraints(domain, true);
        var resolution = resolution(domain, true, refined, List.of());
        var actual = normalize(domain, resolution, initial);
        assertThat(actual.queryConstraints()).isEqualTo(refined);
        assertThat(actual.visualizationDecision()).isEqualTo(resolution.visualizationDecision());
        assertThat(actual.selectedResourcePath()).isEqualTo(resolution.selectedResourcePath());
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void incompleteOrAmbiguousOutputCannotBecomeExecutableFromOneCandidate(String domain) {
        ObjectNode initial = constraints(domain, false);
        var incomplete = resolution(domain, false, null, List.of("Which pending records do you mean?"));
        assertThat(normalize(domain, incomplete, initial)).isSameAs(incomplete);
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void laterSemanticResourceChoiceCannotBeReplacedByTheOnlyRetrievedCandidate(String domain) {
        var other = resolution("another-resource", true, constraints(domain, true), List.of());
        assertThat(normalize(domain, other, constraints(domain, false))).isSameAs(other);
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void fullPassCannotSilentlyForgetAPlannedPredicate(String domain) {
        var initial = constraints(domain, true);
        var lostPredicate = resolution(domain, true, constraints(domain, false), List.of());
        var blocked = normalize(domain, lostPredicate, initial);
        assertThat(blocked.resolved()).isFalse();
        assertThat(blocked.selectedResourcePath()).isNull();
        assertThat(blocked.warnings()).contains("llm-pre-intent-query-constraints-not-preserved");
        assertThat(blocked.quickReplies()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void twoPreservativeRefinementsKeepAllEarlierRequirements(String domain) {
        var first = constraints(domain, false);
        var second = constraints(domain, true);
        var afterFirstRefinement = normalize(domain, resolution(domain, true, second, List.of()), first);
        var third = second.deepCopy();
        third.withArray("filters").addObject().put("concept", domain + " active records")
                .put("field", "active").put("operator", "eq").put("value", true);
        var afterSecondRefinement = normalize(domain, resolution(domain, true, third, List.of()),
                (ObjectNode) afterFirstRefinement.queryConstraints());
        assertThat(afterSecondRefinement.queryConstraints()).isEqualTo(third);
        assertThat(afterSecondRefinement.visualizationDecision().excludedComponentIds())
                .containsExactly("praxis-chart");
    }

    private AgenticAuthoringLlmIntentResolution normalize(String domain,
            AgenticAuthoringLlmIntentResolution resolution, ObjectNode initial) {
        var candidate = new AgenticAuthoringCandidate("/api/synthetic/" + domain, "post",
                "/schemas/filtered?path=/api/synthetic/" + domain + "/filter&operation=post&schemaType=response",
                "/api/synthetic/" + domain + "/filter", "post", 0.95,
                "Governed synthetic resource", List.of("tool-search-api-resources", "schema-grounding-verified"));
        return ReflectionTestUtils.invokeMethod(resolver, "normalizeConstrainedAuthoringResolution",
                resolution, orientation(initial), List.of(candidate));
    }

    private AgenticAuthoringPreIntentToolPlan orientation(ObjectNode constraints) {
        return new AgenticAuthoringPreIntentToolPlan("praxis-agentic-authoring-pre-intent-tool-plan.v2",
                "The workflow needs full semantic grounding beyond the record-selection predicate.",
                List.of(), "authoring_or_other", "", true, constraints, "page", "praxis-crud");
    }

    private AgenticAuthoringLlmIntentResolution resolution(String domain, boolean resolved,
            ObjectNode constraints, List<String> questions) {
        var visual = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1", "review pending records",
                "resource-crud", "praxis-crud", List.of(), false, true,
                List.of("praxis-chart"), true, false, "llm-full-intent");
        return new AgenticAuthoringLlmIntentResolution(resolved, resolved ? "create" : "unknown",
                resolved ? "page" : "unknown", resolved ? "create_artifact" : "needs_clarification",
                resolved ? "/api/synthetic/" + domain : null, null, "none", "Review before applying.",
                List.of(), questions, List.of(), null, resolved ? visual : null, false,
                resolved ? "component_authoring" : "unknown", constraints, List.of());
    }

    private ObjectNode constraints(String domain, boolean refined) {
        ObjectNode value = mapper.createObjectNode().put("appliesToDataSelection", true);
        var filters = value.putArray("filters");
        filters.addObject().put("concept", domain + " organizational group")
                .put("field", "groupId").put("operator", "eq").put("value", 7);
        if (refined) filters.addObject().put("concept", domain + " pending records")
                .put("field", "pending").put("operator", "eq").put("value", true);
        return value;
    }
}
