package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgenticAuthoringComponentOperationCompatibilityGraphTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ordersColumnCreationBeforeItsTargetDependentFormatting() throws Exception {
        var result = graph("1", operations(addColumn(), formatColumn())).resolve(List.of("column.format", "column.add"));
        assertThat(result.accepted()).isTrue();
        assertThat(result.operationIds()).containsExactly("column.add", "column.format");
    }

    @Test
    void rejectsRemovalTogetherWithLaterColumnMutation() throws Exception {
        var result = graph("1", operations(removeColumn(), formatColumn())).resolve(List.of("column.remove", "column.format"));
        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains("compatibility-conflict");
    }

    @Test
    void composesRendererAndVisibilityOnIndependentPaths() throws Exception {
        var result = graph("1", operations(renderer(), visibility())).resolve(List.of("column.renderer", "column.visibility"));
        assertThat(result.accepted()).isTrue();
    }

    @Test
    void rejectsDifferentSetValuesForTheSamePath() throws Exception {
        var result = graph("1", operations(set("a"), set("b"))).resolve(List.of("a", "b"));
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void composesOperationsOnIndependentPaths() throws Exception {
        var result = graph("1", operations(set("a", "appearance.compact"), set("b", "behavior.pagination"))).resolve(List.of("a", "b"));
        assertThat(result.accepted()).isTrue();
    }

    @Test
    void rejectsSetValueAtParentTogetherWithChildMutation() throws Exception {
        var result = graph("1", operations(set("replace", "appearance"), op("child", "global", "merge-object", "appearance.density", "")))
                .resolve(List.of("replace", "child"));
        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains("compatibility-conflict");
    }

    @Test
    void rejectsCyclicCreateBeforeDependencies() throws Exception {
        var result = graph("1", operations(
                op("first", "column", "append-unique", "columns[]", "target-exists"),
                op("second", "column", "append-unique", "columns[]", "target-exists")))
                .resolve(List.of("first", "second"));
        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo("component-operation-compatibility-cycle");
    }

    @Test
    void regeneratesWhenManifestVersionChanges() throws Exception {
        var service = new AgenticAuthoringComponentOperationCompatibilityGraphService();
        assertThat(service.resolve("praxis-table", manifest("1", operations(set("a"))), List.of("a")).accepted()).isTrue();
        assertThat(service.resolve("praxis-table", manifest("2", operations(set("b"))), List.of("b")).accepted()).isTrue();
    }

    @Test
    void addedOperationIsAvailableWithoutAParallelCatalog() throws Exception {
        var result = graph("1", operations(set("added"))).resolve(List.of("added"));
        assertThat(result.accepted()).isTrue();
    }

    @Test
    void removedOperationDisappearsWithoutAParallelCatalog() throws Exception {
        var result = graph("2", operations(set("remaining"))).resolve(List.of("removed"));
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void evictsAStaleGraphWhenTheManifestContentChangesWithoutVersionChange() throws Exception {
        var service = new AgenticAuthoringComponentOperationCompatibilityGraphService();
        assertThat(service.resolve("praxis-table", manifest("1", operations(set("removed"))), List.of("removed")).accepted()).isTrue();
        var result = service.resolve("praxis-table", manifest("1", operations(set("remaining"))), List.of("removed"));
        assertThat(result.accepted()).isFalse();
    }

    private AgenticAuthoringComponentOperationCompatibilityGraph graph(String version, String operations) throws Exception {
        return AgenticAuthoringComponentOperationCompatibilityGraph.derive(manifest(version, operations));
    }
    private JsonNode manifest(String version, String operations) throws Exception {
        return objectMapper.readTree("{\"componentId\":\"praxis-table\",\"manifestVersion\":\"" + version + "\",\"operations\":" + operations + "}");
    }
    private String operations(String... values) { return "[" + String.join(",", values) + "]"; }
    private String addColumn() { return op("column.add", "column", "append-unique", "columns[]", ""); }
    private String removeColumn() { return op("column.remove", "column", "remove-by-key", "columns[]", "target-exists"); }
    private String formatColumn() { return op("column.format", "column", "merge-by-key", "columns[].format", "target-exists"); }
    private String renderer() { return op("column.renderer", "renderer", "merge-object", "columns[].renderer", "target-exists"); }
    private String visibility() { return op("column.visibility", "column", "merge-by-key", "columns[].visible", "target-exists"); }
    private String set(String id) { return set(id, "appearance.compact"); }
    private String set(String id, String path) { return op(id, "global", "set-value", path, ""); }
    private String op(String id, String target, String effect, String path, String precondition) {
        return "{\"operationId\":\"" + id + "\",\"target\":{\"kind\":\"" + target + "\"},\"effects\":[{\"kind\":\"" + effect + "\",\"path\":\"" + path + "\"}],\"affectedPaths\":[\"" + path + "\"],\"preconditions\":" + (precondition.isBlank() ? "[]" : "[\"" + precondition + "\"]") + ",\"validators\":[],\"submissionImpact\":\"config-only\"}";
    }
}
