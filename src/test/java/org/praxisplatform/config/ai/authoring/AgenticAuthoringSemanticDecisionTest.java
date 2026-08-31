package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringSemanticDecisionTest {

    @Test
    void preservesExplicitActiveDecisionLineageForCanonicalModificationWithoutProviderFollowUpHint() {
        AgenticAuthoringCandidate employeeResource = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/funcionarios/filter/cursor",
                "POST",
                0.96,
                "governed employee resource",
                List.of("domain-binding", "schema-grounding-verified"));
        AgenticAuthoringSemanticDecision activeDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "table",
                "create_artifact",
                employeeResource,
                List.of(employeeResource),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Create the employee table",
                "Create the employee table",
                "Initial governed decision.");
        AgenticAuthoringLlmIntentResolution modificationWithoutFollowUpHint =
                new AgenticAuthoringLlmIntentResolution(
                        true,
                        "modify",
                        "table",
                        "column.header.set",
                        employeeResource.resourcePath(),
                        "",
                        "none",
                        "Rename the governed column.",
                        List.of(),
                        List.of(),
                        List.of());

        AgenticAuthoringSemanticDecision refinement = AgenticAuthoringSemanticDecision.from(
                "modify",
                "table",
                "column.header.set",
                employeeResource,
                List.of(employeeResource),
                null,
                List.of(),
                null,
                modificationWithoutFollowUpHint,
                activeDecision,
                "conversation-1",
                "turn-2",
                "Rename the status column",
                "Rename the status column",
                "Canonical component modification.");

        assertThat(refinement.decisionId()).isNotBlank();
        assertThat(refinement.previousDecisionId()).isEqualTo(activeDecision.decisionId());
        assertThat(refinement.refinementOf()).isEqualTo(activeDecision.decisionId());
        assertThat(refinement.previousDecisionRef()).isEqualTo(activeDecision.decisionId());
    }
}
