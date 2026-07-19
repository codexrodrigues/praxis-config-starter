package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringComponentDiscoveryServiceTest {

    private final AgenticAuthoringComponentDiscoveryService service =
            new AgenticAuthoringComponentDiscoveryService();

    @Test
    void ranksGovernedComponentsOnlyAfterTheSemanticVisualizationDecision() {
        AgenticAuthoringSemanticDecision decision = decision(new AgenticAuthoringVisualizationDecision(
                "1.0", "analytical employee overview", "dashboard", "praxis-chart", List.of(),
                true, true, List.of(), true, true, "llm_semantic_decision"));

        var result = service.discover(
                decision,
                new AgenticAuthoringComponentCapabilitiesService().listCapabilities());

        assertThat(result.schemaVersion()).isEqualTo("praxis-agentic-authoring-component-selection.v1");
        assertThat(result.acceptedCandidates()).isNotEmpty();
        assertThat(result.acceptedCandidates().get(0).componentId()).isEqualTo("praxis-chart");
        assertThat(result.acceptedCandidates().get(0).reason())
                .isEqualTo("primary-component-from-semantic-decision");
        assertThat(result.acceptedCandidates())
                .extracting(AgenticAuthoringComponentDiscoveryService.ComponentCandidate::evidenceRefs)
                .allSatisfy(refs -> assertThat(refs).allMatch(ref -> ref.startsWith("ai_registry:component:")));
    }

    @Test
    void preservesRejectedCandidatesAndSemanticExclusionsInTheAuditProjection() {
        AgenticAuthoringSemanticDecision decision = decision(new AgenticAuthoringVisualizationDecision(
                "1.0", "employee results", "dashboard", "praxis-table", List.of(),
                false, true, List.of("praxis-chart"), true, false, "llm_semantic_decision"));

        var result = service.discover(
                decision,
                new AgenticAuthoringComponentCapabilitiesService().listCapabilities());

        assertThat(result.rejectedCandidates())
                .filteredOn(candidate -> "praxis-chart".equals(candidate.componentId()))
                .extracting(AgenticAuthoringComponentDiscoveryService.ComponentCandidate::reason)
                .containsExactly("excluded-by-semantic-decision");
    }

    @Test
    void doesNotExpandBroadSemanticRefsIntoUnrelatedComponentCatalogs() {
        AgenticAuthoringSemanticDecision decision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-2",
                "create",
                "page",
                "create_artifact",
                null,
                new AgenticAuthoringVisualizationDecision(
                        "1.0", "operational page", "page", "praxis-chart", List.of(),
                        true, true, List.of(), false, false, "llm_semantic_decision"),
                null,
                false,
                "",
                "",
                "");

        var result = service.discover(
                decision,
                new AgenticAuthoringComponentCapabilitiesService().listCapabilities());

        assertThat(result.acceptedCandidates())
                .extracting(AgenticAuthoringComponentDiscoveryService.ComponentCandidate::componentId)
                .startsWith("praxis-chart")
                .doesNotContain("praxis-table-rule-builder", "praxis-tabs");
        assertThat(result.acceptedCandidates())
                .allSatisfy(candidate -> assertThat(candidate.matchedCapabilityIds()).hasSizeLessThan(10));
    }

    private AgenticAuthoringSemanticDecision decision(AgenticAuthoringVisualizationDecision visualization) {
        return new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-1",
                "create",
                "dashboard",
                "create_dashboard",
                null,
                visualization,
                null,
                false,
                "",
                "",
                "");
    }
}
