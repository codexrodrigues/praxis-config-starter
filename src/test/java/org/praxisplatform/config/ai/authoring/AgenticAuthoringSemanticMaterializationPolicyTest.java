package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringSemanticMaterializationPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ignoresRejectedComponentCandidatesStoredInDiagnostics() {
        ObjectNode materialization = objectMapper.createObjectNode();
        materialization.putArray("widgets")
                .addObject()
                .put("key", "employees-table")
                .put("componentId", "praxis-table");
        materialization.withObject("/diagnostics/componentSelection")
                .putArray("rejectedCandidates")
                .addObject()
                .put("componentId", "praxis-chart");

        assertThat(AgenticAuthoringSemanticMaterializationPolicy.containsComponent(
                materialization,
                "praxis-table"))
                .isTrue();
        assertThat(AgenticAuthoringSemanticMaterializationPolicy.containsComponent(
                materialization,
                "praxis-chart"))
                .isFalse();
    }
}
