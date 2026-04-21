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
    void shouldCompileStepperRemoveDomainPatchAndReselectSafely() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "steps": [
                    { "id": "account", "label": "Conta" },
                    { "id": "review", "label": "Revisao" },
                    { "id": "confirm", "label": "Confirmar" }
                  ],
                  "selectedIndex": 1
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-stepper",
                operationWithHandler("step.remove", "step", "step-by-id-or-label", true,
                        "compile-domain-patch", "stepper-step-remove", "steps[]"),
                plan("\"review\"", "{ }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("remove-step-and-reselect");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("stepper-step-remove");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("review");
        assertThat(patchOperation.path("removedIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("removedValue").path("label").asText()).isEqualTo("Revisao");
        assertThat(patchOperation.path("selectedIndexBefore").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("selectedIndexAfter").asInt()).isEqualTo(1);
        assertThat(proposedConfig.path("steps")).hasSize(2);
        assertThat(proposedConfig.path("steps").get(1).path("id").asText()).isEqualTo("confirm");
        assertThat(proposedConfig.path("selectedIndex").asInt()).isEqualTo(1);
    }

    @Test
    void shouldCompileStepperValidationRuleUpsertIntoNestedFormConfig() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "steps": [
                    {
                      "id": "account",
                      "label": "Conta",
                      "form": {
                        "config": {
                          "fieldMetadata": [
                            { "name": "email", "label": "Email" }
                          ]
                        }
                      }
                    }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-stepper",
                operationWithHandler("validation.rule.add", "validationGate", "step-validation-by-id", true,
                        "compile-domain-patch", "stepper-validation-rule-upsert", "steps[].form.config.formRules[]"),
                plan("\"account\"", """
                        {
                          "stepId": "account",
                          "fieldName": "email",
                          "message": "Email obrigatorio",
                          "rule": {
                            "condition": { "!": [{ "var": "email" }] }
                          }
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("upsert-step-validation-rule");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("stepper-validation-rule-upsert");
        assertThat(patchOperation.path("stepId").asText()).isEqualTo("account");
        assertThat(patchOperation.path("fieldName").asText()).isEqualTo("email");
        assertThat(patchOperation.path("ruleId").asText()).isEqualTo("validation-email");
        JsonNode formRule = proposedConfig.path("steps").get(0).path("form").path("config").path("formRules").get(0);
        assertThat(formRule.path("context").asText()).isEqualTo("validation");
        assertThat(formRule.path("targetType").asText()).isEqualTo("field");
        assertThat(formRule.path("targets").get(0).asText()).isEqualTo("email");
        assertThat(formRule.path("effect").path("properties").path("message").asText()).isEqualTo("Email obrigatorio");
        assertThat(proposedConfig.path("steps").get(0).path("hasError").asBoolean()).isTrue();
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
    void shouldCompileTabsDisabledPatchForGroupTab() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "tabs": [
                    { "id": "general", "textLabel": "Geral" },
                    { "id": "security", "textLabel": "Seguranca", "disabled": false }
                  ],
                  "group": { "selectedIndex": 0 }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-tabs",
                operationWithHandler("tab.disabled.set", "disabledState", "tab-or-link-by-id", true,
                        "compile-domain-patch", "tabs.set-tab-or-link-disabled", "tabs[].disabled"),
                plan("\"security\"", "{ \"disabled\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-tab-or-link-disabled");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("tabs.set-tab-or-link-disabled");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("security");
        assertThat(patchOperation.path("before").asBoolean()).isFalse();
        assertThat(patchOperation.path("after").asBoolean()).isTrue();
        assertThat(proposedConfig.path("tabs").get(1).path("disabled").asBoolean()).isTrue();
    }

    @Test
    void shouldCompileTabsVisiblePatchForNavLink() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "nav": {
                    "links": [
                      { "id": "home", "textLabel": "Home" },
                      { "id": "audit", "textLabel": "Auditoria", "visible": true }
                    ],
                    "selectedIndex": 0
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-tabs",
                operationWithHandler("tab.visible.set", "visibility", "tab-or-link-by-id", true,
                        "compile-domain-patch", "tabs.set-tab-or-link-visible", "nav.links[].visible"),
                plan("\"audit\"", "{ \"visible\": false }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-tab-or-link-visible");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("tabs.set-tab-or-link-visible");
        assertThat(patchOperation.path("path").asText()).isEqualTo("nav.links[]/1.visible");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("audit");
        assertThat(proposedConfig.path("nav").path("links").get(1).path("visible").asBoolean()).isFalse();
    }

    @Test
    void shouldCompileTabsContentPatchForNavLink() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "nav": {
                    "links": [
                      { "id": "home", "textLabel": "Home" },
                      { "id": "audit", "textLabel": "Auditoria" }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-tabs",
                operationWithHandler("tab.content.set", "tabContent", "tab-or-link-by-id", true,
                        "compile-domain-patch", "tabs.set-tab-or-link-content", "nav.links[].widgets"),
                plan("\"audit\"", """
                        {
                          "widgets": [
                            { "id": "audit-table", "component": "praxis-table" }
                          ]
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("merge-tab-or-link-content");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("tabs.set-tab-or-link-content");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("audit");
        assertThat(patchOperation.path("value").path("widgets").get(0).path("id").asText()).isEqualTo("audit-table");
        assertThat(proposedConfig.path("nav").path("links").get(1).path("widgets").get(0).path("component").asText())
                .isEqualTo("praxis-table");
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

    @Test
    void shouldCompileRichContentMediaBlockUpdateDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      {
                        "id": "profile",
                        "type": "mediaBlock",
                        "title": "Old",
                        "subtitle": "Old subtitle",
                        "avatar": {
                          "name": "old",
                          "imageSrc": "/old.png"
                        }
                      }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("mediaBlock.update", "media", "rich-media-node-by-id-or-path", true,
                        "compile-domain-patch", "rich-content-media-block-update", "document.nodes[].title"),
                plan("\"profile\"", """
                        {
                          "title": "New",
                          "subtitle": "New subtitle",
                          "avatarName": "person",
                          "avatarImageSrc": "/assets/person.png"
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("merge-rich-media-block");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("rich-content-media-block-update");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("profile");
        assertThat(patchOperation.path("resolvedPath").asText()).isEqualTo("document.nodes[]/0");
        assertThat(patchOperation.path("value").path("title").asText()).isEqualTo("New");
        assertThat(patchOperation.path("value").path("subtitle").asText()).isEqualTo("New subtitle");
        assertThat(patchOperation.path("value").path("avatar").path("name").asText()).isEqualTo("person");
        assertThat(patchOperation.path("value").path("avatar").path("imageSrc").asText()).isEqualTo("/assets/person.png");
        JsonNode updatedBlock = proposedConfig.path("document").path("nodes").get(0);
        assertThat(updatedBlock.path("title").asText()).isEqualTo("New");
        assertThat(updatedBlock.path("subtitle").asText()).isEqualTo("New subtitle");
        assertThat(updatedBlock.path("avatar").path("name").asText()).isEqualTo("person");
        assertThat(updatedBlock.path("avatar").path("imageSrc").asText()).isEqualTo("/assets/person.png");
    }

    @Test
    void shouldCompileRichContentLinkRemoveDomainPatchPreservingLabelAsText() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      { "id": "intro", "type": "text", "text": "Intro" },
                      { "id": "terms-link", "type": "link", "label": "Terms", "href": "/terms" },
                      { "id": "footer", "type": "text", "text": "End" }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("link.remove", "link", "rich-link-node-by-id-or-path", true,
                        "compile-domain-patch", "rich-content-link-remove", "document.nodes[]"),
                plan("\"terms-link\"", "{ \"preserveLabelAsText\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("replace-rich-link-with-text");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("rich-content-link-remove");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("terms-link");
        assertThat(patchOperation.path("removedIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("removedValue").path("type").asText()).isEqualTo("link");
        assertThat(patchOperation.path("replacementValue").path("id").asText()).isEqualTo("terms-link-text");
        assertThat(patchOperation.path("replacementValue").path("type").asText()).isEqualTo("text");
        assertThat(patchOperation.path("replacementValue").path("text").asText()).isEqualTo("Terms");
        JsonNode replacement = proposedConfig.path("document").path("nodes").get(1);
        assertThat(replacement.path("id").asText()).isEqualTo("terms-link-text");
        assertThat(replacement.path("type").asText()).isEqualTo("text");
        assertThat(replacement.path("text").asText()).isEqualTo("Terms");
        assertThat(proposedConfig.path("document").path("nodes")).hasSize(3);
    }

    @Test
    void shouldCompileRichContentTimelineItemAddDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      {
                        "id": "history",
                        "type": "timeline",
                        "items": [
                          { "id": "created", "title": "Created" },
                          { "id": "published", "title": "Published" }
                        ]
                      }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("timeline.item.add", "timelineItem", "rich-timeline-node-by-id-or-path", true,
                        "compile-domain-patch", "rich-content-timeline-item-add", "document.nodes[].items[]"),
                plan("\"history\"", """
                        {
                          "timelineBlockId": "history",
                          "item": { "id": "reviewed", "title": "Reviewed" },
                          "afterItemId": "created"
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("insert-rich-timeline-item");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("rich-content-timeline-item-add");
        assertThat(patchOperation.path("timelineBlockId").asText()).isEqualTo("history");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("reviewed");
        assertThat(patchOperation.path("insertedIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("afterItemId").asText()).isEqualTo("created");
        assertThat(patchOperation.path("value").path("title").asText()).isEqualTo("Reviewed");
        JsonNode items = proposedConfig.path("document").path("nodes").get(0).path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(1).path("id").asText()).isEqualTo("reviewed");
        assertThat(items.get(2).path("id").asText()).isEqualTo("published");
    }

    @Test
    void shouldCompileRichContentTimelineItemUpdateDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      {
                        "id": "history",
                        "type": "timeline",
                        "items": [
                          { "id": "created", "title": "Created" },
                          { "id": "published", "title": "Published", "subtitle": "Draft" }
                        ]
                      }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("timeline.item.update", "timelineItem", "rich-timeline-item-by-block-id-and-item-id", true,
                        "compile-domain-patch", "rich-content-timeline-item-update", "document.nodes[].items[].title"),
                plan("""
                        {
                          "timelineBlockId": "history",
                          "itemId": "published"
                        }
                        """, """
                        {
                          "timelineBlockId": "history",
                          "patch": {
                            "title": "Published live",
                            "badge": "done"
                          }
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("merge-rich-timeline-item");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("rich-content-timeline-item-update");
        assertThat(patchOperation.path("timelineBlockId").asText()).isEqualTo("history");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("published");
        assertThat(patchOperation.path("value").path("title").asText()).isEqualTo("Published live");
        assertThat(patchOperation.path("value").path("badge").asText()).isEqualTo("done");
        JsonNode item = proposedConfig.path("document").path("nodes").get(0).path("items").get(1);
        assertThat(item.path("title").asText()).isEqualTo("Published live");
        assertThat(item.path("subtitle").asText()).isEqualTo("Draft");
        assertThat(item.path("badge").asText()).isEqualTo("done");
    }

    @Test
    void shouldCompileRichContentTimelineItemRemoveDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      {
                        "id": "history",
                        "type": "timeline",
                        "items": [
                          { "id": "created", "title": "Created" },
                          { "id": "published", "title": "Published", "subtitle": "Draft" },
                          { "id": "archived", "title": "Archived" }
                        ]
                      }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("timeline.item.remove", "timelineItem", "rich-timeline-item-by-block-id-and-item-id", true,
                        "compile-domain-patch", "rich-content-timeline-item-remove", "document.nodes[].items[]"),
                plan("""
                        {
                          "timelineBlockId": "history",
                          "itemId": "published"
                        }
                        """, """
                        {
                          "timelineBlockId": "history",
                          "itemId": "published"
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("remove-rich-timeline-item");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("rich-content-timeline-item-remove");
        assertThat(patchOperation.path("timelineBlockId").asText()).isEqualTo("history");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("published");
        assertThat(patchOperation.path("removedIndex").asInt()).isEqualTo(1);
        assertThat(patchOperation.path("removedValue").path("title").asText()).isEqualTo("Published");
        JsonNode items = proposedConfig.path("document").path("nodes").get(0).path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("id").asText()).isEqualTo("created");
        assertThat(items.get(1).path("id").asText()).isEqualTo("archived");
    }

    @Test
    void shouldCompileRichContentSanitizationPolicyDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      { "id": "intro", "type": "text", "text": "Intro" }
                    ],
                    "sanitizationPolicy": {
                      "allowedUrlProtocols": ["https"],
                      "maxNodeDepth": 8
                    }
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("sanitizationPolicy.set", "sanitizationPolicy", "rich-content-validation-policy", false,
                        "compile-domain-patch", "rich-content-sanitization-policy", "document"),
                plan("null", """
                        {
                          "allowHtml": false,
                          "allowedUrlProtocols": ["https", "mailto"],
                          "allowImageDataUrls": false,
                          "maxNodeDepth": 10,
                          "maxNodeCount": 200
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-rich-content-sanitization-policy");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("rich-content-sanitization-policy");
        assertThat(patchOperation.path("path").asText()).isEqualTo("document.sanitizationPolicy");
        assertThat(patchOperation.path("previousValue").path("maxNodeDepth").asInt()).isEqualTo(8);
        assertThat(patchOperation.path("value").path("allowedUrlProtocols")).hasSize(2);
        JsonNode policy = proposedConfig.path("document").path("sanitizationPolicy");
        assertThat(policy.path("allowHtml").asBoolean()).isFalse();
        assertThat(policy.path("allowedUrlProtocols")).hasSize(2);
        assertThat(policy.path("maxNodeDepth").asInt()).isEqualTo(10);
        assertThat(policy.path("maxNodeCount").asInt()).isEqualTo(200);
    }

    @Test
    void shouldCompileRichContentPresetApplyDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      { "id": "intro", "type": "text", "text": "Intro" }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("preset.apply", "preset", "rich-block-preset-ref", false,
                        "compile-domain-patch", "rich-content-preset-apply", "document.nodes[]"),
                plan("null", """
                        {
                          "ref": {
                            "kind": "rich-block",
                            "namespace": "praxis.rich-content",
                            "presetId": "profile-summary",
                            "version": "1.0.0"
                          },
                          "inputs": {
                            "title": "Ana Silva",
                            "subtitle": "Account owner"
                          }
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(1);
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("insert-rich-preset");
        assertThat(patchOperation.path("domainHandler").asText()).isEqualTo("rich-content-preset-apply");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("profile-summary");
        assertThat(patchOperation.path("value").path("type").asText()).isEqualTo("preset");
        assertThat(patchOperation.path("value").path("ref").path("presetId").asText()).isEqualTo("profile-summary");
        assertThat(patchOperation.path("value").path("inputs").path("title").asText()).isEqualTo("Ana Silva");
        JsonNode nodes = proposedConfig.path("document").path("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(1).path("id").asText()).isEqualTo("profile-summary");
    }

    @Test
    void shouldCompileRichContentPresetApplyReplacementDomainPatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "document": {
                    "kind": "praxis.rich-content",
                    "version": "1.0.0",
                    "nodes": [
                      { "id": "profile", "type": "mediaBlock", "title": "Old" }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-rich-content",
                operationWithHandler("preset.apply", "preset", "rich-block-preset-ref", false,
                        "compile-domain-patch", "rich-content-preset-apply", "document.nodes[]"),
                plan("null", """
                        {
                          "ref": {
                            "kind": "rich-block",
                            "namespace": "praxis.rich-content",
                            "presetId": "profile-summary"
                          },
                          "replaceBlockId": "profile",
                          "inputs": {
                            "title": "New"
                          }
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("replace-rich-block-with-preset");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("profile");
        assertThat(patchOperation.path("previousValue").path("type").asText()).isEqualTo("mediaBlock");
        JsonNode node = proposedConfig.path("document").path("nodes").get(0);
        assertThat(node.path("id").asText()).isEqualTo("profile");
        assertThat(node.path("type").asText()).isEqualTo("preset");
        assertThat(node.path("ref").path("presetId").asText()).isEqualTo("profile-summary");
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
