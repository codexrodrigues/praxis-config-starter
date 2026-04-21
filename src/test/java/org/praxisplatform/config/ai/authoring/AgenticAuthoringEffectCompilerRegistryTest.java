package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringEffectCompilerRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringEffectCompilerRegistry registry =
            new AgenticAuthoringEffectCompilerRegistry(
                    objectMapper,
                    new AgenticAuthoringTargetResolverRegistry());

    @Test
    void shouldCompileMergeByKeyIntoPatchOperationAndProposedConfig() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "email", "header": "Email" }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-table",
                operation("column.header.set", "column", "column-by-field", true,
                        "merge-by-key", "columns[]", "field", "columns[].header"),
                plan("\"email\"", "{ \"header\": \"Contato\" }"),
                proposedConfig,
                patchOperations,
                failures,
                warnings);

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("merge-by-key");
        assertThat(patchOperation.path("resolvedPath").asText()).isEqualTo("columns[]/0");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("email");
        assertThat(proposedConfig.path("columns").get(0).path("header").asText()).isEqualTo("Contato");
    }

    @Test
    void shouldCompileSetValueIntoResolvedNestedTarget() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "fieldMetadata": [
                    { "name": "email", "label": "Email" }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operation("field.label.set", "field", "field-by-name-or-label", true,
                        "set-value", "fieldMetadata[].label", "", "fieldMetadata[].label"),
                plan("\"email\"", "{ \"label\": \"E-mail Corporativo\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("set-value");
        assertThat(patchOperations.get(0).path("resolvedPath").asText()).isEqualTo("fieldMetadata[]/0.label");
        assertThat(proposedConfig.path("fieldMetadata").get(0).path("label").asText()).isEqualTo("E-mail Corporativo");
    }

    @Test
    void shouldCompileAppendUniqueIntoRootCollectionAndRejectDuplicates() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "email", "header": "Email" }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-table",
                operation("column.add", "column", "column-by-field", false,
                        "append-unique", "columns[]", "field", "columns[]"),
                plan("{}", "{ \"field\": \"name\", \"header\": \"Nome\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("append-unique");
        assertThat(patchOperations.get(0).path("keyValue").asText()).isEqualTo("name");
        assertThat(patchOperations.get(0).path("appendedIndex").asInt()).isEqualTo(1);
        assertThat(proposedConfig.path("columns")).hasSize(2);

        registry.appendCompiledEffects(
                "praxis-table",
                operation("column.add", "column", "column-by-field", false,
                        "append-unique", "columns[]", "field", "columns[]"),
                plan("{}", "{ \"field\": \"name\", \"header\": \"Nome duplicado\" }"),
                proposedConfig,
                objectMapper.createArrayNode(),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains("append-unique duplicate value for columns[] field=name");
        assertThat(proposedConfig.path("columns")).hasSize(2);
    }

    @Test
    void shouldCompileAppendUniqueIntoResolvedNestedCollection() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "conditionalRenderers": [] }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-table",
                operation("column.conditionalRenderer.add", "column", "column-by-field", true,
                        "append-unique", "columns[].conditionalRenderers[]", "id", "columns[].conditionalRenderers[]"),
                plan("\"status\"", "{ \"id\": \"status-badge\", \"condition\": { \"==\": [{ \"var\": \"status\" }, \"ACTIVE\"] } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        assertThat(patchOperations.get(0).path("resolvedPath").asText()).isEqualTo("columns[]/0");
        assertThat(patchOperations.get(0).path("keyValue").asText()).isEqualTo("status-badge");
        assertThat(proposedConfig.path("columns").get(0).path("conditionalRenderers")).hasSize(1);
    }

    @Test
    void shouldCompileReorderByKeyBeforeTarget() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "panels": [
                    { "id": "summary", "title": "Summary" },
                    { "id": "details", "title": "Details" },
                    { "id": "audit", "title": "Audit" }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-expansion",
                operation("panel.order.set", "panel", "panel-by-id-or-title", true,
                        "reorder-by-key", "panels[]", "id", "panels[]"),
                plan("\"audit\"", "{ \"beforePanelId\": \"summary\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("reorder-by-key");
        assertThat(patchOperations.get(0).path("keyValue").asText()).isEqualTo("audit");
        assertThat(patchOperations.get(0).path("fromIndex").asInt()).isEqualTo(2);
        assertThat(patchOperations.get(0).path("toIndex").asInt()).isZero();
        assertThat(proposedConfig.path("panels").get(0).path("id").asText()).isEqualTo("audit");
        assertThat(proposedConfig.path("panels").get(1).path("id").asText()).isEqualTo("summary");
    }

    @Test
    void shouldRollbackReorderByKeyWhenReferenceIsMissing() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "nodes": [
                      { "id": "hero" },
                      { "id": "body" }
                    ]
                  }
                }
                """);
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operation("block.order.set", "block", "rich-block-by-id-or-index", true,
                        "reorder-by-key", "document.nodes[]", "id", "document.nodes[]"),
                plan("\"body\"", "{ \"beforeBlockId\": \"missing\" }"),
                proposedConfig,
                objectMapper.createArrayNode(),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains("reorder-by-key before target not found: document.nodes[] id=missing");
        assertThat(proposedConfig.path("document").path("nodes").get(0).path("id").asText()).isEqualTo("hero");
        assertThat(proposedConfig.path("document").path("nodes").get(1).path("id").asText()).isEqualTo("body");
    }

    @Test
    void shouldRejectDomainCompilerEffectsUntilDomainCompilerExists() throws Exception {
        ObjectNode proposedConfig = objectMapper.createObjectNode();
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operation("layout.field.move", "field", "field-by-name-or-label", false,
                        "compile-domain-patch", "layout", "", "layout"),
                plan("\"email\"", "{ \"targetColumnId\": \"main\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(patchOperations).isEmpty();
        assertThat(failures).contains("domain compiler is required for operation: layout.field.move");
    }

    @Test
    void shouldCompileStepperReorderDomainPatchAndPreserveSelectedIndexIdentity() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "steps": [
                    { "id": "account", "label": "Conta" },
                    { "id": "review", "label": "Revisao" }
                  ],
                  "selectedIndex": 1
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-stepper",
                operationWithHandler("step.order.set", "step", "step-by-id-or-label", true,
                        "compile-domain-patch", "stepper-step-reorder", "steps[]"),
                plan("\"review\"", "{ \"beforeStepId\": \"account\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("reorder-by-key");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("stepper-step-reorder");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("review");
        assertThat(patchOperation.path("fromIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("toIndex").asInt()).isZero();
        assertThat(patchOperation.path("selectedIndexBefore").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("selectedIndexAfter").asInt()).isZero();
        assertThat(proposedConfig.path("steps").get(0).path("id").asText()).isEqualTo("review");
        assertThat(proposedConfig.path("steps").get(1).path("id").asText()).isEqualTo("account");
        assertThat(proposedConfig.path("selectedIndex").asInt()).isZero();
    }

    @Test
    void shouldCompileTabsReorderDomainPatchAndPreserveSelectedIndexIdentity() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "tabs": [
                    { "id": "general", "textLabel": "Geral" },
                    { "id": "security", "textLabel": "Seguranca" }
                  ],
                  "group": {
                    "selectedIndex": 1
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-tabs",
                operationWithHandler("tab.order.set", "tab", "tab-by-id-or-label", true,
                        "compile-domain-patch", "tabs.reorder-tab-and-preserve-selection", "tabs[]"),
                plan("\"security\"", "{ \"beforeTabId\": \"general\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("reorder-by-key");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("tabs.reorder-tab-and-preserve-selection");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("security");
        assertThat(patchOperation.path("fromIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("toIndex").asInt()).isZero();
        assertThat(patchOperation.path("selectedIndexBefore").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("selectedIndexAfter").asInt()).isZero();
        assertThat(proposedConfig.path("tabs").get(0).path("id").asText()).isEqualTo("security");
        assertThat(proposedConfig.path("tabs").get(1).path("id").asText()).isEqualTo("general");
        assertThat(proposedConfig.path("group").path("selectedIndex").asInt()).isZero();
    }

    @Test
    void shouldCompileTabsRemoveDomainPatchAndReselectSafely() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "tabs": [
                    { "id": "general", "textLabel": "Geral" },
                    { "id": "security", "textLabel": "Seguranca" },
                    { "id": "audit", "textLabel": "Auditoria" }
                  ],
                  "group": {
                    "selectedIndex": 1
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-tabs",
                operationWithHandler("tab.remove", "tab", "tab-by-id-or-label", true,
                        "compile-domain-patch", "tabs.remove-tab-and-reselect", "tabs[]"),
                plan("\"security\"", "{ }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("remove-by-key");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("tabs.remove-tab-and-reselect");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("security");
        assertThat(patchOperation.path("removedIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("selectedIndexBefore").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("selectedIndexAfter").asInt()).isEqualTo(1);
        assertThat(proposedConfig.path("tabs")).hasSize(2);
        assertThat(proposedConfig.path("tabs").get(0).path("id").asText()).isEqualTo("general");
        assertThat(proposedConfig.path("tabs").get(1).path("id").asText()).isEqualTo("audit");
        assertThat(proposedConfig.path("group").path("selectedIndex").asInt()).isEqualTo(1);
    }

    @Test
    void shouldCompileTabsSetActiveItemDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "tabs": [
                    { "id": "general", "textLabel": "Geral" },
                    { "id": "security", "textLabel": "Seguranca" }
                  ],
                  "group": {
                    "selectedIndex": 0
                  },
                  "nav": {
                    "selectedIndex": 0
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-tabs",
                operationWithHandler("tab.active.set", "activeTab", "tab-index-or-id", true,
                        "compile-domain-patch", "tabs.set-active-item", "group.selectedIndex"),
                plan("\"security\"", "{ \"selectedIndex\": 1, \"tabId\": \"security\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-active-index");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("tabs.set-active-item");
        assertThat(patchOperation.path("selectedIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("selectedTabId").asText()).isEqualTo("security");
        assertThat(patchOperation.path("groupSelectedIndexBefore").asInt()).isZero();
        assertThat(patchOperation.path("groupSelectedIndexAfter").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("navSelectedIndexBefore").asInt()).isZero();
        assertThat(patchOperation.path("navSelectedIndexAfter").asInt()).isEqualTo(1);
        assertThat(proposedConfig.path("group").path("selectedIndex").asInt()).isEqualTo(1);
        assertThat(proposedConfig.path("nav").path("selectedIndex").asInt()).isEqualTo(1);
    }

    @Test
    void shouldCompileRichContentBlockAddDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      { "id": "hero", "type": "text", "text": "Intro" },
                      { "id": "footer", "type": "text", "text": "End" }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("block.add", "block", "document-nodes-array", false,
                        "compile-domain-patch", "rich-content-block-add", "document.nodes[]"),
                plan("{}", "{ \"type\": \"text\", \"node\": { \"id\": \"body\", \"text\": \"Body\" }, \"afterBlockId\": \"hero\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("insert-rich-block");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("rich-content-block-add");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("body");
        assertThat(patchOperation.path("insertedIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("afterBlockId").asText()).isEqualTo("hero");
        assertThat(patchOperation.path("value").path("type").asText()).isEqualTo("text");
        assertThat(proposedConfig.path("document").path("nodes")).hasSize(3);
        assertThat(proposedConfig.path("document").path("nodes").get(1).path("id").asText()).isEqualTo("body");
    }

    private JsonNode operation(
            String operationId,
            String targetKind,
            String resolver,
            boolean required,
            String effectKind,
            String path,
            String key,
            String affectedPath) throws Exception {
        String keyProperty = key == null || key.isBlank() ? "" : "\"key\": \"%s\",".formatted(key);
        return objectMapper.readTree("""
                {
                  "operationId": "%s",
                  "target": {
                    "kind": "%s",
                    "resolver": "%s",
                    "ambiguityPolicy": "fail",
                    "required": %s
                  },
                  "effects": [
                    { "kind": "%s", "path": "%s", %s "inputPath": "" }
                  ],
                  "affectedPaths": ["%s"],
                  "submissionImpact": "visual-only"
                }
                """.formatted(operationId, targetKind, resolver, required, effectKind, path, keyProperty, affectedPath));
    }

    private JsonNode operationWithHandler(
            String operationId,
            String targetKind,
            String resolver,
            boolean required,
            String effectKind,
            String handler,
            String affectedPath) throws Exception {
        return objectMapper.readTree("""
                {
                  "operationId": "%s",
                  "target": {
                    "kind": "%s",
                    "resolver": "%s",
                    "ambiguityPolicy": "fail",
                    "required": %s
                  },
                  "effects": [
                    { "kind": "%s", "handler": "%s" }
                  ],
                  "affectedPaths": ["%s"],
                  "submissionImpact": "config-only"
                }
                """.formatted(operationId, targetKind, resolver, required, effectKind, handler, affectedPath));
    }

    private JsonNode plan(String targetJson, String inputJson) throws Exception {
        return objectMapper.readTree("""
                {
                  "target": %s,
                  "input": %s
                }
                """.formatted(targetJson, inputJson));
    }
}
