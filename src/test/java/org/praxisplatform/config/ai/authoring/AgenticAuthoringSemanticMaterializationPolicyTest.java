package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
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

    @Test
    void requiresTheAuthoredResourceCompositionLayoutToBeMaterialized() {
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "mission workspace",
                "resource-master-detail",
                "praxis-table",
                List.of(),
                false,
                true,
                "llm-authored-semantic-decision");
        AgenticAuthoringSemanticDecision semanticDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-1",
                "create",
                "page",
                "create_artifact",
                null,
                visualizationDecision,
                null,
                false,
                "",
                "",
                "");
        ObjectNode wrongLayout = objectMapper.createObjectNode();
        wrongLayout.put("layoutPreset", "resource-crud");
        wrongLayout.putArray("widgets")
                .addObject()
                .put("key", "missoes-master")
                .put("componentId", "praxis-table");

        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult result =
                AgenticAuthoringSemanticMaterializationPolicy.validate(semanticDecision, wrongLayout);

        assertThat(result.failureCodes())
                .contains(AgenticAuthoringSemanticMaterializationPolicy.LAYOUT_REQUIRED_FAILURE);
        assertThat(result.warnings())
                .contains(AgenticAuthoringSemanticMaterializationPolicy.MATERIALIZATION_MISMATCH_WARNING);

        ObjectNode canonicalBlueprint = wrongLayout.deepCopy();
        canonicalBlueprint.put("layoutPreset", "master-detail-dashboard");
        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult canonicalResult =
                AgenticAuthoringSemanticMaterializationPolicy.validate(semanticDecision, canonicalBlueprint);
        assertThat(canonicalResult.failureCodes())
                .doesNotContain(AgenticAuthoringSemanticMaterializationPolicy.LAYOUT_REQUIRED_FAILURE);
    }

    @Test
    void validatesTheCanonicalPageInsideTheCompiledPatchEnvelope() {
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "mission workspace",
                "resource-master-detail",
                "praxis-table",
                List.of(),
                false,
                true,
                "llm-authored-semantic-decision");
        AgenticAuthoringSemanticDecision semanticDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-compiled-envelope",
                "create",
                "page",
                "create_artifact",
                null,
                visualizationDecision,
                null,
                false,
                "",
                "",
                "");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        ObjectNode page = compiledFormPatch.withObject("/patch/page");
        page.put("layoutPreset", "master-detail-dashboard");
        page.putArray("widgets")
                .addObject()
                .put("key", "missoes-master")
                .putObject("definition")
                .put("id", "praxis-table");
        compiledFormPatch.withObject("/diagnostics/resourceWorkspaceGrounding")
                .put("status", "verified");

        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult result =
                AgenticAuthoringSemanticMaterializationPolicy.validate(semanticDecision, compiledFormPatch);

        assertThat(result.failureCodes()).doesNotContain(
                AgenticAuthoringSemanticMaterializationPolicy.LAYOUT_REQUIRED_FAILURE,
                AgenticAuthoringSemanticMaterializationPolicy.PRIMARY_COMPONENT_REQUIRED_FAILURE,
                AgenticAuthoringSemanticMaterializationPolicy.RESOURCE_WORKSPACE_GROUNDING_REQUIRED_FAILURE);
    }

    @Test
    void keepsGovernedWorkspaceDiagnosticsRequiredForACompiledPatchEnvelope() {
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.withObject("/patch/page")
                .put("layoutPreset", "master-detail-dashboard")
                .putArray("widgets");

        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult unavailable =
                AgenticAuthoringSemanticMaterializationPolicy.validate(
                        new AgenticAuthoringSemanticDecision(
                                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                                "decision-compiled-envelope",
                                "create",
                                "page",
                                "create_artifact",
                                null,
                                null,
                                null,
                                false,
                                "",
                                "",
                                ""),
                        compiledFormPatch);

        assertThat(unavailable.failureCodes()).contains(
                AgenticAuthoringSemanticMaterializationPolicy.RESOURCE_WORKSPACE_GROUNDING_REQUIRED_FAILURE);
    }

    @Test
    void blocksMasterDetailBlueprintWithoutServerVerifiedWorkspaceOperations() {
        ObjectNode materialization = objectMapper.createObjectNode();
        materialization.put("layoutPreset", "master-detail-dashboard");
        materialization.withObject("/diagnostics/resourceWorkspaceGrounding")
                .put("status", "unavailable")
                .put("failureCode", "verified-domain-operations-missing");

        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult unavailable =
                AgenticAuthoringSemanticMaterializationPolicy.validate(
                        new AgenticAuthoringSemanticDecision(
                                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                                "decision-1",
                                "create",
                                "page",
                                "create_artifact",
                                null,
                                null,
                                null,
                                false,
                                "",
                                "",
                                ""),
                        materialization);

        assertThat(unavailable.failureCodes()).contains(
                AgenticAuthoringSemanticMaterializationPolicy.RESOURCE_WORKSPACE_GROUNDING_REQUIRED_FAILURE);

        materialization.withObject("/diagnostics/resourceWorkspaceGrounding").put("status", "verified");
        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult verified =
                AgenticAuthoringSemanticMaterializationPolicy.validate(
                        new AgenticAuthoringSemanticDecision(
                                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                                "decision-1",
                                "create",
                                "page",
                                "create_artifact",
                                null,
                                null,
                                null,
                                false,
                                "",
                                "",
                                ""),
                        materialization);
        assertThat(verified.failureCodes()).doesNotContain(
                AgenticAuthoringSemanticMaterializationPolicy.RESOURCE_WORKSPACE_GROUNDING_REQUIRED_FAILURE);
    }

    @Test
    void blocksSelectedResourceWorkspaceWithoutAnyCanonicalResourceBinding() {
        AgenticAuthoringSemanticDecision.SelectedResource selectedResource =
                new AgenticAuthoringSemanticDecision.SelectedResource(
                "/api/operations/missoes",
                "post",
                "/schemas/filtered?path=/api/operations/missoes/filter&operation=post&schemaType=response",
                "/api/operations/missoes/filter",
                "post");
        AgenticAuthoringSemanticDecision semanticDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-resource-binding",
                "create",
                "page",
                "create_master_detail",
                selectedResource,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "mission workspace",
                        "resource-master-detail",
                        "praxis-table",
                        List.of(),
                        false,
                        true,
                        "llm-authored-semantic-decision"),
                null,
                false,
                "",
                "",
                "");
        ObjectNode materialization = objectMapper.createObjectNode();
        materialization.put("layoutPreset", "master-detail-dashboard");
        materialization.putArray("widgets")
                .addObject()
                .put("componentId", "praxis-table");
        materialization.withObject("/diagnostics/resourceWorkspaceGrounding")
                .put("status", "verified");

        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult missing =
                AgenticAuthoringSemanticMaterializationPolicy.validate(semanticDecision, materialization);

        assertThat(missing.failureCodes()).contains(
                AgenticAuthoringSemanticMaterializationPolicy.RESOURCE_BINDING_MISMATCH_FAILURE);

        materialization.withArray("widgets").get(0).withObject("/inputs")
                .put("resourcePath", "/api/operations/missoes");
        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult bound =
                AgenticAuthoringSemanticMaterializationPolicy.validate(semanticDecision, materialization);
        assertThat(bound.failureCodes()).doesNotContain(
                AgenticAuthoringSemanticMaterializationPolicy.RESOURCE_BINDING_MISMATCH_FAILURE);
    }
}
