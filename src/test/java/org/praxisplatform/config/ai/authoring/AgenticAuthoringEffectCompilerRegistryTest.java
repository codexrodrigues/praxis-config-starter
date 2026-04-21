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
    void shouldCompileDynamicFormLayoutDomainPatches() throws Exception {
        ObjectNode proposedConfig = dynamicFormConfig();
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operationWithHandler("layout.field.move", "field", "field-by-name-or-label", true,
                        "compile-domain-patch", "form-layout-reconciler", "sections[].rows[].columns[].items"),
                plan("\"status\"", "{ \"targetSectionId\": \"secondary\", \"targetRowId\": \"r2\", \"targetColumnId\": \"c2\", \"index\": 0 }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operationWithHandler("layout.visualBlock.add", "column", "column-by-id-in-row", true,
                        "compile-domain-patch", "form-layout-visual-block-add", "sections[].rows[].columns[].items"),
                plan("\"c2\"", "{ \"id\": \"help\", \"document\": { \"kind\": \"praxis.rich-content\", \"version\": \"1.0.0\", \"nodes\": [] }, \"index\": 1 }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operationWithHandler("layout.visualBlock.update", "visualBlock", "layout-item-by-id", true,
                        "compile-domain-patch", "form-layout-visual-block-update", "sections[].rows[].columns[].items"),
                plan("\"intro\"", "{ \"rootClassName\": \"form-intro\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operationWithHandler("layout.visualBlock.move", "visualBlock", "layout-item-by-id", true,
                        "compile-domain-patch", "form-layout-visual-block-move", "sections[].rows[].columns[].items"),
                plan("\"intro\"", "{ \"targetSectionId\": \"secondary\", \"targetRowId\": \"r2\", \"targetColumnId\": \"c2\", \"index\": 2 }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operationWithHandler("layout.visualBlock.remove", "visualBlock", "layout-item-by-id", true,
                        "compile-domain-patch", "form-layout-visual-block-remove", "sections[].rows[].columns[].items"),
                plan("\"help\"", "{}"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operationWithHandler("field.local.remove", "localField", "field-by-name", true,
                        "compile-domain-patch", "form-layout-field-cleanup", "fieldMetadata[]"),
                plan("\"email\"", "{}"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-form",
                operationWithHandler("layout.section.remove", "section", "section-by-id-or-title", true,
                        "compile-domain-patch", "form-layout-section-cleanup", "sections[]"),
                plan("\"secondary\"", "{}"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).extracting(node -> node.path("op").asText())
                .containsExactly(
                        "move-form-field-layout-ref",
                        "add-form-visual-block",
                        "update-form-visual-block",
                        "move-form-visual-block",
                        "remove-form-visual-block",
                        "remove-form-local-field-and-layout-refs",
                        "remove-form-section-and-layout");
        assertThat(proposedConfig.path("fieldMetadata")).hasSize(1);
        assertThat(proposedConfig.path("fieldMetadata").get(0).path("name").asText()).isEqualTo("status");
        assertThat(proposedConfig.path("sections")).hasSize(1);
        assertThat(proposedConfig.toString()).doesNotContain("\"email\"");
    }

    @Test
    void shouldCompileTableRuleBuilderDomainPatchesAndDelegations() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status" }
                  ],
                  "ruleEffects": {
                    "rules": [
                      {
                        "ruleId": "existing",
                        "scope": "row",
                        "condition": { "==": [{ "var": "status" }, "open"] },
                        "effects": [
                          { "effectId": "paint", "effectType": "fundo", "payload": { "color": "yellow" } }
                        ]
                      }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("rule.add", "rule", "table-rule-by-stable-id", true,
                        "compile-domain-patch", "table-rule-builder-rule-add", "ruleEffects.rules[]"),
                plan("\"new-rule\"", "{ \"ruleId\": \"new-rule\", \"scope\": \"cell\", \"columnKey\": \"status\", \"condition\": { \"==\": [{ \"var\": \"status\" }, \"done\"] }, \"effect\": { \"effectType\": \"estilo\", \"payload\": { \"fontWeight\": \"600\" } } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("condition.set", "condition", "table-rule-condition-by-rule-id", true,
                        "compile-domain-patch", "table-rule-builder-condition-set", "delegatedAuthoringOperations"),
                plan("\"new-rule\"", "{ \"ruleId\": \"new-rule\", \"condition\": { \"==\": [{ \"var\": \"status\" }, \"blocked\"] }, \"mode\": \"replace\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("effect.add", "effect", "rule-effect-by-rule-and-effect-id", true,
                        "compile-domain-patch", "table-rule-builder-effect-add", "RuleEffectDefinition"),
                plan("{ \"ruleId\": \"new-rule\", \"effectId\": \"icon\" }", "{ \"ruleId\": \"new-rule\", \"effectId\": \"icon\", \"effectType\": \"icone\", \"payload\": { \"icon\": \"check\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("effect.update", "effect", "rule-effect-by-rule-and-effect-id", true,
                        "compile-domain-patch", "table-rule-builder-effect-update", "RuleEffectDefinition"),
                plan("{ \"ruleId\": \"new-rule\", \"effectId\": \"icon\" }", "{ \"ruleId\": \"new-rule\", \"effectId\": \"icon\", \"payload\": { \"color\": \"green\" }, \"mode\": \"merge\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("animation.set", "animation", "rule-animation-by-rule-and-effect-id", true,
                        "compile-domain-patch", "table-rule-builder-animation-set", "RuleEffectDefinition.animation"),
                plan("{ \"ruleId\": \"new-rule\", \"effectId\": \"icon\" }", "{ \"ruleId\": \"new-rule\", \"effectId\": \"icon\", \"animation\": { \"preset\": \"info-soft\", \"durationMs\": 250 } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("preset.apply", "preset", "default-effect-preset-by-key", true,
                        "compile-domain-patch", "table-rule-builder-preset-apply", "RuleEffectDefinition"),
                plan("{ \"presetKey\": \"alerta\" }", "{ \"ruleId\": \"new-rule\", \"presetKey\": \"alerta\", \"scope\": \"row\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("effect.remove", "effect", "rule-effect-by-rule-and-effect-id", true,
                        "compile-domain-patch", "table-rule-builder-effect-remove", "RuleEffectDefinition"),
                plan("{ \"ruleId\": \"existing\", \"effectId\": \"paint\" }", "{ \"ruleId\": \"existing\", \"effectId\": \"paint\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("tableIntegration.delegate", "tableDelegation", "praxis-table-authoring-operation", false,
                        "compile-domain-patch", "table-rule-builder-table-delegate", "delegatedAuthoringOperations"),
                plan("{}", "{ \"tableOperationId\": \"column.conditionalRenderer.add\", \"reason\": \"renderer placement belongs to praxis-table\", \"tableTarget\": { \"field\": \"status\" }, \"tableParams\": { \"renderer\": \"badge\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-table-rule-builder",
                operationWithHandler("rule.remove", "rule", "table-rule-by-stable-id", true,
                        "compile-domain-patch", "table-rule-builder-rule-remove", "delegatedAuthoringOperations"),
                plan("\"existing\"", "{ \"ruleId\": \"existing\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).extracting(node -> node.path("op").asText())
                .containsExactly(
                        "add-table-rule-builder-rule",
                        "set-table-rule-builder-condition",
                        "add-table-rule-builder-effect",
                        "update-table-rule-builder-effect",
                        "set-table-rule-builder-animation",
                        "apply-table-rule-builder-preset",
                        "remove-table-rule-builder-effect",
                        "delegate-table-rule-builder-table-operation",
                        "remove-table-rule-builder-rule");
        JsonNode newRule = proposedConfig.path("ruleEffects").path("rules").get(0);
        assertThat(newRule.path("ruleId").asText()).isEqualTo("new-rule");
        assertThat(newRule.path("condition").path("==").get(1).asText()).isEqualTo("blocked");
        assertThat(newRule.path("effects").get(0).path("effectId").asText()).isEqualTo("preset-alerta");
        assertThat(proposedConfig.path("delegatedAuthoringOperations")).hasSize(4);
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

    @Test
    void shouldCompileExpansionPanelRemoveAndPromoteReplacementExpandedPanel() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "accordion": { "multi": false },
                  "panels": [
                    { "id": "summary", "title": "Summary", "expanded": true },
                    { "id": "details", "title": "Details", "expanded": false },
                    { "id": "audit", "title": "Audit", "expanded": false }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-expansion",
                operationWithHandler("panel.remove", "panel", "panel-by-id-or-title", true,
                        "compile-domain-patch", "expansion-panel-remove", "panels[]"),
                plan("\"summary\"", "{ \"replacementExpandedPanelId\": \"details\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("remove-expansion-panel");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("summary");
        assertThat(patchOperation.path("removedWasExpanded").asBoolean()).isTrue();
        assertThat(proposedConfig.path("panels")).hasSize(2);
        assertThat(proposedConfig.path("panels").get(0).path("id").asText()).isEqualTo("details");
        assertThat(proposedConfig.path("panels").get(0).path("expanded").asBoolean()).isTrue();
        assertThat(proposedConfig.path("panels").get(1).path("expanded").asBoolean()).isFalse();
    }

    @Test
    void shouldCompileExpansionMultiExpandSetAndCollapseCompetingPanels() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "accordion": { "multi": true },
                  "panels": [
                    { "id": "summary", "expanded": true },
                    { "id": "details", "expanded": true }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-expansion",
                operationWithHandler("behavior.multiExpand.set", "behavior", "expansion-behavior-config", true,
                        "compile-domain-patch", "expansion-multi-expand-set", "accordion.multi"),
                plan("{}", "{ \"multi\": false }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-expansion-multi-expand");
        assertThat(patchOperation.path("previousValue").asBoolean()).isTrue();
        assertThat(patchOperation.path("value").asBoolean()).isFalse();
        assertThat(proposedConfig.path("accordion").path("multi").asBoolean()).isFalse();
        assertThat(proposedConfig.path("panels").get(0).path("expanded").asBoolean()).isTrue();
        assertThat(proposedConfig.path("panels").get(1).path("expanded").asBoolean()).isFalse();
    }

    @Test
    void shouldCompileExpansionDefaultExpandedUpsertAndCollapseOthersInSingleMode() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "accordion": { "multi": false },
                  "panels": [
                    { "id": "summary", "expanded": true },
                    { "id": "details", "expanded": false, "disabled": false }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-expansion",
                operationWithHandler("behavior.defaultExpanded.set", "expandedState", "panel-by-id-or-title", true,
                        "compile-domain-patch", "expansion-default-expanded-upsert", "panels[].expanded"),
                plan("\"details\"", "{ \"expanded\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-expansion-default-expanded");
        assertThat(patchOperation.path("keyValue").asText()).isEqualTo("details");
        assertThat(patchOperation.path("collapseOthers").asBoolean()).isTrue();
        assertThat(proposedConfig.path("panels").get(0).path("expanded").asBoolean()).isFalse();
        assertThat(proposedConfig.path("panels").get(1).path("expanded").asBoolean()).isTrue();
    }

    @Test
    void shouldCompileFilesUploadPresignBaseUrlAndDerivedPaths() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "baseUrl": "/api/praxis/files",
                  "strategy": "direct"
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-files-upload",
                operationWithHandler("endpoint.presign.set", "presignEndpoint", "files-api-base-url-presign-contract", false,
                        "compile-domain-patch", "files-upload-presign-base-url", "baseUrl"),
                plan("null", "{ \"baseUrl\": \"/api/praxis/files/uploads\", \"strategy\": \"presign\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-files-upload-endpoint-base-url");
        assertThat(patchOperation.path("previousBaseUrl").asText()).isEqualTo("/api/praxis/files");
        assertThat(patchOperation.path("baseUrl").asText()).isEqualTo("/api/praxis/files/uploads");
        assertThat(patchOperation.path("strategy").asText()).isEqualTo("presign");
        assertThat(patchOperation.path("derivedPresignPath").asText()).isEqualTo("/api/praxis/files/uploads/upload/presign");
        assertThat(proposedConfig.path("baseUrl").asText()).isEqualTo("/api/praxis/files/uploads");
        assertThat(proposedConfig.path("strategy").asText()).isEqualTo("presign");
    }

    @Test
    void shouldRejectFilesUploadEndpointPathOverrides() throws Exception {
        ObjectNode proposedConfig = objectMapper.createObjectNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-files-upload",
                operationWithHandler("endpoint.upload.set", "uploadEndpoint", "files-api-base-url-upload-contract", false,
                        "compile-domain-patch", "files-upload-direct-base-url", "baseUrl"),
                plan("null", "{ \"baseUrl\": \"/api/praxis/files/upload\", \"strategy\": \"direct\" }"),
                proposedConfig,
                objectMapper.createArrayNode(),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains("files-upload-direct-base-url rejects unsafe baseUrl: /api/praxis/files/upload");
        assertThat(proposedConfig.path("baseUrl").isMissingNode()).isTrue();
    }

    @Test
    void shouldCompileListTemplateSlotSet() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "templating": {
                    "primary": { "type": "text", "expr": "name" }
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-list",
                operationWithHandler("template.slot.set", "itemTemplate", "list-template-slot", true,
                        "compile-domain-patch", "list-template-slot-set", "templating.primary"),
                plan("\"primary\"", "{ \"slot\": \"primary\", \"template\": { \"type\": \"text\", \"expr\": \"title\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-list-template-slot");
        assertThat(patchOperation.path("path").asText()).isEqualTo("templating.primary");
        assertThat(patchOperation.path("previousValue").path("expr").asText()).isEqualTo("name");
        assertThat(patchOperation.path("value").path("expr").asText()).isEqualTo("title");
        assertThat(proposedConfig.path("templating").path("primary").path("expr").asText()).isEqualTo("title");
    }

    @Test
    void shouldRejectListTemplateSlotMismatch() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "templating": {}
                }
                """);
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-list",
                operationWithHandler("template.slot.set", "itemTemplate", "list-template-slot", true,
                        "compile-domain-patch", "list-template-slot-set", "templating.primary"),
                plan("\"primary\"", "{ \"slot\": \"secondary\", \"template\": { \"type\": \"text\", \"expr\": \"title\" } }"),
                proposedConfig,
                objectMapper.createArrayNode(),
                failures,
                new ArrayList<>());

        assertThat(failures).contains("list-template-slot-set target slot does not match input slot");
        assertThat(proposedConfig.path("templating").path("secondary").isMissingNode()).isTrue();
    }

    @Test
    void shouldCompileCronExpressionSetAndDiagnostics() throws Exception {
        ObjectNode proposedConfig = objectMapper.createObjectNode();
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "pdx-cron-builder",
                operationWithHandler("cron.expression.set", "expression", "schedule-expression", false,
                        "compile-domain-patch", "cron-expression-set", "schedule.expression"),
                plan("null", "{ \"cron\": \"0 9 * * MON\", \"dialect\": \"unix\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-cron-expression");
        assertThat(patchOperation.path("cron").asText()).isEqualTo("0 9 * * MON");
        assertThat(patchOperation.path("diagnostics").path("valid").asBoolean()).isTrue();
        assertThat(proposedConfig.path("schedule").path("kind").asText()).isEqualTo("customCron");
        assertThat(proposedConfig.path("schedule").path("expression").path("cron").asText()).isEqualTo("0 9 * * MON");
        assertThat(proposedConfig.path("value").asText()).isEqualTo("0 9 * * MON");
    }

    @Test
    void shouldCompileCronFrequencyToExpression() throws Exception {
        ObjectNode proposedConfig = objectMapper.createObjectNode();
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "pdx-cron-builder",
                operationWithHandler("cron.frequency.set", "frequency", "schedule-kind-and-recurrence", false,
                        "compile-domain-patch", "cron-frequency-to-expression", "schedule.expression"),
                plan("null", "{ \"kind\": \"daily\", \"recurrence\": { \"hour\": 8, \"minute\": 30 } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("set-cron-frequency");
        assertThat(patchOperations.get(0).path("cron").asText()).isEqualTo("30 8 * * *");
        assertThat(proposedConfig.path("schedule").path("kind").asText()).isEqualTo("daily");
        assertThat(proposedConfig.path("schedule").path("expression").path("cron").asText()).isEqualTo("30 8 * * *");
    }

    @Test
    void shouldCompileCronPreviewGenerate() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "schedule": {
                    "expression": { "cron": "0 9 * * *", "dialect": "unix" },
                    "timezone": "UTC"
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "pdx-cron-builder",
                operationWithHandler("cron.preview.generate", "preview", "schedule-preview-config", false,
                        "compile-domain-patch", "cron-preview-generate", "preview"),
                plan("null", "{ \"occurrences\": 2, \"from\": \"2026-04-21T00:00:00Z\", \"timezone\": \"UTC\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("generate-cron-preview");
        assertThat(patchOperations.get(0).path("preview")).hasSize(2);
        assertThat(proposedConfig.path("preview")).hasSize(2);
    }

    @Test
    void shouldCompileSettingsPanelSizeIntoConfigAndRuntimeWidth() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "config": {
                    "id": "table-settings",
                    "minWidth": "480px"
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-settings-panel",
                operationWithHandler("panel.size.set", "panelSize", "settings-panel-size-and-resize", false,
                        "compile-domain-patch", "settings-panel-size-set", "config.minWidth"),
                plan("null", "{ \"width\": \"920px\", \"minWidth\": \"640px\", \"maxWidth\": \"1200px\", \"resizable\": true, \"persistSizeKey\": \"settings:table\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("set-settings-panel-size");
        assertThat(patchOperation.path("previousConfig").path("minWidth").asText()).isEqualTo("480px");
        assertThat(proposedConfig.path("config").path("minWidth").asText()).isEqualTo("640px");
        assertThat(proposedConfig.path("config").path("resizable").asBoolean()).isTrue();
        assertThat(proposedConfig.path("runtime").path("width").asText()).isEqualTo("920px");
    }

    @Test
    void shouldCompileSettingsPanelRuntimeBehaviorsWithoutMutatingConfigSemantics() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "config": {
                    "id": "table-settings"
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-settings-panel",
                operationWithHandler("panel.applyBehavior.set", "applyBehavior", "settings-value-provider-apply-contract", false,
                        "compile-domain-patch", "settings-panel-apply-behavior-set", "ref.applied$"),
                plan("null", "{ \"requireDirty\": true, \"requireValid\": true, \"blockWhileBusy\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("set-settings-panel-apply-behavior");
        assertThat(patchOperations.get(0).path("path").asText()).isEqualTo("runtime.settingsPanel.applyBehavior");
        assertThat(proposedConfig.path("config").has("applyBehavior")).isFalse();
        assertThat(proposedConfig.path("runtime").path("settingsPanel").path("applyBehavior").path("requireDirty").asBoolean()).isTrue();
    }

    @Test
    void shouldCompileSettingsPanelEditorHostAndDelegateConsumerConfig() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "config": {
                    "id": "table-settings",
                    "content": {
                      "component": "old-editor"
                    }
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-settings-panel",
                operationWithHandler("editor.host.configure", "editorHost", "settings-panel-editor-host", true,
                        "compile-domain-patch", "settings-panel-editor-host-configure", "config.content.component"),
                plan("{ \"componentId\": \"praxis-table\" }", """
                        {
                          "componentId": "praxis-table",
                          "inputs": { "tableId": "orders" },
                          "configManifestComponentId": "praxis-table",
                          "editorContract": "SettingsValueProvider"
                        }
                        """),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("configure-settings-panel-editor-host");
        assertThat(patchOperation.path("previousContent").path("component").asText()).isEqualTo("old-editor");
        assertThat(proposedConfig.path("config").path("content").path("component").asText()).isEqualTo("praxis-table");
        assertThat(proposedConfig.path("config").path("content").path("inputs").path("tableId").asText()).isEqualTo("orders");
        assertThat(proposedConfig.path("delegatedConsumerPatch").path("componentId").asText()).isEqualTo("praxis-table");
    }

    @Test
    void shouldCompileDialogPresetApplyWithTypeVariantLocalMergeOrder() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "globalPresets": {
                    "confirm": { "ariaRole": "alertdialog", "themeColor": "light", "width": "480px" },
                    "variants": {
                      "danger": { "themeColor": "dark", "disableClose": true }
                    }
                  },
                  "config": {
                    "title": "Old"
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-dialog",
                operationWithHandler("dialog.preset.apply", "preset", "praxis-dialog-global-preset-by-type-variant", false,
                        "compile-domain-patch", "dialog-preset-apply", "config"),
                plan("null", "{ \"dialogType\": \"confirm\", \"variant\": \"danger\", \"localConfig\": { \"title\": \"Delete record\", \"width\": \"640px\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("apply-dialog-preset");
        assertThat(proposedConfig.path("config").path("ariaRole").asText()).isEqualTo("alertdialog");
        assertThat(proposedConfig.path("config").path("themeColor").asText()).isEqualTo("dark");
        assertThat(proposedConfig.path("config").path("width").asText()).isEqualTo("640px");
        assertThat(proposedConfig.path("variant").asText()).isEqualTo("danger");
    }

    @Test
    void shouldCompileDialogChildHostConfigure() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "content": { "type": "template", "templateId": "old" }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-dialog",
                operationWithHandler("childHost.configure", "childHost", "praxis-dialog-child-component-or-template-host", false,
                        "compile-domain-patch", "dialog-child-host-configure", "content"),
                plan("null", "{ \"contentType\": \"component\", \"componentId\": \"praxis-dynamic-form\", \"inputs\": { \"formId\": \"customer\" }, \"data\": { \"mode\": \"create\" }, \"childManifestComponentId\": \"praxis-dynamic-form\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode patchOperation = patchOperations.get(0);
        assertThat(patchOperation.path("op").asText()).isEqualTo("configure-dialog-child-host");
        assertThat(patchOperation.path("previousContent").path("templateId").asText()).isEqualTo("old");
        assertThat(proposedConfig.path("content").path("type").asText()).isEqualTo("component");
        assertThat(proposedConfig.path("componentId").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(proposedConfig.path("inputs").path("formId").asText()).isEqualTo("customer");
        assertThat(proposedConfig.path("delegatedChildManifest").path("componentId").asText()).isEqualTo("praxis-dynamic-form");
    }

    @Test
    void shouldCompileDialogChildOperationDelegateIntoHostEnvelope() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "content": { "type": "component", "componentId": "praxis-table" },
                  "inputs": { "tableId": "orders" }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-dialog",
                operationWithHandler("childOperation.delegate", "childHost", "praxis-dialog-child-component-or-template-host", true,
                        "compile-domain-patch", "dialog-child-operation-delegate", "inputs"),
                plan("{ \"componentId\": \"praxis-table\" }", "{ \"childManifestComponentId\": \"praxis-table\", \"operationId\": \"column.header.set\", \"target\": \"email\", \"params\": { \"header\": \"Email\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode delegated = proposedConfig.path("inputs").path("__praxisAuthoringDelegatedPatch");
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("delegate-dialog-child-operation");
        assertThat(delegated.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(delegated.path("operationId").asText()).isEqualTo("column.header.set");
        assertThat(delegated.path("input").path("header").asText()).isEqualTo("Email");
        assertThat(proposedConfig.path("data").path("__praxisAuthoringDelegatedPatch").path("componentId").asText()).isEqualTo("praxis-table");
    }

    @Test
    void shouldCompileDynamicFieldsControlRegistrationAndMetadataProfile() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "componentRegistry": [],
                  "componentMetadata": { "controlProfiles": [] }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-dynamic-fields",
                operationWithHandler("controlType.register", "controlType", "field-control-type-token", false,
                        "compile-domain-patch", "dynamic-fields-control-registration", "componentRegistry"),
                plan("null", "{ \"controlType\": \"pdx-money\", \"selector\": \"pdx-money-input\", \"componentExport\": \"PdxMoneyInputComponent\", \"packageOwned\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("register-dynamic-field-control");
        assertThat(proposedConfig.path("componentRegistry").get(0).path("controlType").asText()).isEqualTo("pdx-money");
        assertThat(proposedConfig.path("componentMetadata").path("controlProfiles").get(0).path("selector").asText()).isEqualTo("pdx-money-input");
    }

    @Test
    void shouldCompileDynamicFieldsAliasAddRemoveSelectorAndCoverage() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "componentRegistry": [
                    { "controlType": "pdx-money", "selector": "pdx-money-input" }
                  ],
                  "controlTypeAliases": [
                    { "alias": "money", "normalizedAlias": "money", "controlType": "pdx-money" }
                  ],
                  "selectorMappings": []
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-dynamic-fields",
                operationWithHandler("controlType.alias.add", "controlAlias", "normalized-control-type-alias", false,
                        "compile-domain-patch", "dynamic-fields-alias-registration", "controlTypeAliases"),
                plan("null", "{ \"alias\": \"currency money\", \"controlType\": \"pdx-money\", \"reason\": \"legacy schema\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-fields",
                operationWithHandler("selector.mapping.set", "selector", "field-selector-registry-entry", false,
                        "compile-domain-patch", "dynamic-fields-selector-mapping-set", "selectorMappings[]"),
                plan("null", "{ \"selector\": \"[data-money]\", \"controlType\": \"pdx-money\", \"source\": \"package-default\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-fields",
                operationWithHandler("runtimeCoverage.validate", "runtimeCoverage", "component-registry-coverage", true,
                        "compile-domain-patch", "dynamic-fields-runtime-coverage-validation", "runtimeCoverage"),
                plan("\"pdx-money\"", "{ \"controlType\": \"pdx-money\", \"componentRegistered\": true, \"valueAccessor\": true, \"evidence\": [\"registry spec\"] }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-dynamic-fields",
                operationWithHandler("controlType.alias.remove", "controlAlias", "normalized-control-type-alias", true,
                        "compile-domain-patch", "dynamic-fields-alias-removal", "controlTypeAliases"),
                plan("\"money\"", "{ \"alias\": \"money\", \"replacementControlType\": \"pdx-money\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(4);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("register-dynamic-field-alias");
        assertThat(patchOperations.get(1).path("op").asText()).isEqualTo("set-dynamic-field-selector-mapping");
        assertThat(patchOperations.get(2).path("op").asText()).isEqualTo("validate-dynamic-fields-runtime-coverage");
        assertThat(patchOperations.get(3).path("op").asText()).isEqualTo("remove-dynamic-field-alias");
        assertThat(proposedConfig.path("controlTypeAliases")).hasSize(1);
        assertThat(proposedConfig.path("controlTypeAliases").get(0).path("normalizedAlias").asText()).isEqualTo("currency-money");
        assertThat(proposedConfig.path("selectorMappings").get(0).path("selector").asText()).isEqualTo("[data-money]");
        assertThat(proposedConfig.path("runtimeCoverage").get(0).path("validated").asBoolean()).isTrue();
    }

    @Test
    void shouldCompileCrudResourceActionDefaultsAndPermissions() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "actions": [
                    { "id": "create" },
                    { "id": "delete", "disabled": true }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-crud",
                operationWithHandler("resource.bind", "resourceBinding", "crud-resource-by-path-or-key", true,
                        "compile-domain-patch", "crud-resource-bind", "resource.path"),
                plan("{ \"resourcePath\": \"/customers\" }", "{ \"resourcePath\": \"/customers\", \"resourceKey\": \"customers\", \"idField\": \"id\", \"queryContext\": { \"pageSize\": 25 } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-crud",
                operationWithHandler("surface.create.configure", "createSurface", "crud-action-by-id:create", true,
                        "compile-domain-patch", "crud-create-surface-configure", "actions[].form"),
                plan("\"create\"", "{ \"actionId\": \"create\", \"openMode\": \"modal\", \"formId\": \"customer-create\", \"form\": { \"schemaUrl\": \"/api/customers/schema\", \"submitUrl\": \"/api/customers\", \"submitMethod\": \"post\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-crud",
                operationWithHandler("dialog.size.set", "dialogHost", "crud-dialog-host-defaults", true,
                        "compile-domain-patch", "crud-dialog-host-set", "defaults.modal"),
                plan("{}", "{ \"defaultOpenMode\": \"modal\", \"modal\": { \"width\": \"640px\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-crud",
                operationWithHandler("permissions.set", "permissions", "crud-resource-capabilities", true,
                        "compile-domain-patch", "crud-permissions-set", "actions[].disabled"),
                plan("{}", "{ \"requiredCapabilities\": [\"create\"], \"denyWhenMissingCapability\": true, \"actionPermissions\": { \"delete\": { \"disabled\": false, \"requiresConfirmation\": true } } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(4);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("bind-crud-resource");
        assertThat(patchOperations.get(1).path("op").asText()).isEqualTo("configure-crud-action-surface");
        assertThat(patchOperations.get(2).path("op").asText()).isEqualTo("set-crud-dialog-host-defaults");
        assertThat(patchOperations.get(3).path("op").asText()).isEqualTo("set-crud-permissions");
        assertThat(proposedConfig.path("resource").path("path").asText()).isEqualTo("/customers");
        assertThat(proposedConfig.path("actions").get(0).path("form").path("submitMethod").asText()).isEqualTo("post");
        assertThat(proposedConfig.path("defaults").path("modal").path("width").asText()).isEqualTo("640px");
        assertThat(proposedConfig.path("actions").get(1).path("requiresConfirmation").asBoolean()).isTrue();
    }

    @Test
    void shouldCompileCrudDeleteAndChildDelegation() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "actions": [
                    { "id": "delete", "disabled": true }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-crud",
                operationWithHandler("delete.enabled.set", "deleteAction", "crud-action-by-id:delete", true,
                        "compile-domain-patch", "crud-delete-behavior-set", "actions[].autoDelete"),
                plan("\"delete\"", "{ \"enabled\": true, \"autoDelete\": true, \"requiresConfirmation\": true, \"form\": { \"submitUrl\": \"/api/customers/{id}\", \"submitMethod\": \"delete\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-crud",
                operationWithHandler("form.childOperation.delegate", "childOperation", "child-authoring-manifest-operation", false,
                        "compile-domain-patch", "crud-child-operation-delegate", "delegatedAuthoringOperations"),
                plan("null", "{ \"childComponentId\": \"praxis-dynamic-form\", \"childOperationId\": \"field.label.set\", \"reason\": \"field labels belong to form manifest\", \"childTarget\": { \"name\": \"email\" }, \"childParams\": { \"label\": \"Email\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(2);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("set-crud-delete-behavior");
        assertThat(patchOperations.get(1).path("op").asText()).isEqualTo("delegate-crud-child-operation");
        assertThat(proposedConfig.path("actions").get(0).path("disabled").asBoolean()).isFalse();
        assertThat(proposedConfig.path("delegatedAuthoringOperations").get(0).path("childComponentId").asText()).isEqualTo("praxis-dynamic-form");
    }

    @Test
    void shouldCompileChartSeriesAxisDataAndEvents() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "chartDocument": {
                    "version": "0.1.0",
                    "kind": "combo",
                    "source": { "kind": "derived" },
                    "dimensions": [
                      { "field": "month", "role": "category" }
                    ],
                    "metrics": [
                      { "field": "revenue", "aggregation": "sum", "axis": "primary" }
                    ]
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-chart",
                operationWithHandler("series.add", "series", "x-ui-chart-metric-by-field", false,
                        "compile-domain-patch", "chart-series-add", "chartDocument.metrics[]"),
                plan("{}", "{ \"field\": \"margin\", \"aggregation\": \"avg\", \"seriesKind\": \"line\", \"axis\": \"secondary\", \"afterField\": \"revenue\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-chart",
                operationWithHandler("axis.configure", "axis", "x-ui-chart-dimension-or-metric-axis", false,
                        "compile-domain-patch", "chart-axis-configure", "chartDocument.metrics[].axis"),
                plan("{}", "{ \"metricField\": \"margin\", \"metricAxis\": \"secondary\", \"dimensionField\": \"month\", \"dimensionRole\": \"time\", \"orientation\": \"vertical\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-chart",
                operationWithHandler("data.resource.bind", "dataBinding", "x-ui-chart-source-and-field-catalog", false,
                        "compile-domain-patch", "chart-data-resource-bind", "chartDocument.source"),
                plan("{}", "{ \"sourceKind\": \"praxis.stats\", \"resource\": \"/api/sales/stats\", \"operation\": \"timeseries\", \"dimensions\": [{ \"field\": \"month\" }], \"metrics\": [{ \"field\": \"revenue\", \"aggregation\": \"sum\" }] }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-chart",
                operationWithHandler("crossFilter.configure", "crossFilter", "x-ui-chart-events-cross-filter", false,
                        "compile-domain-patch", "chart-event-cross-filter-configure", "chartDocument.events.crossFilter"),
                plan("{}", "{ \"event\": \"pointClick\", \"action\": \"update-context\", \"target\": \"orders-table\", \"mapping\": { \"date\": \"month\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(4);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("add-chart-series");
        assertThat(patchOperations.get(1).path("op").asText()).isEqualTo("configure-chart-axis");
        assertThat(patchOperations.get(2).path("op").asText()).isEqualTo("bind-chart-data-resource");
        assertThat(patchOperations.get(3).path("op").asText()).isEqualTo("configure-chart-cross-filter-event");
        assertThat(proposedConfig.path("chartDocument").path("source").path("resource").asText()).isEqualTo("/api/sales/stats");
        assertThat(proposedConfig.path("chartDocument").path("metrics").get(0).path("field").asText()).isEqualTo("revenue");
        assertThat(proposedConfig.path("chartDocument").path("events").path("crossFilter").path("mapping").path("date").asText()).isEqualTo("month");
    }

    @Test
    void shouldCompileVisualBuilderGraphDomainPatches() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "nodes": [
                    { "id": "root", "nodeId": "root", "nodeType": "group", "children": ["condition"], "config": {} },
                    { "id": "condition", "nodeId": "condition", "nodeType": "condition", "parentId": "root", "children": [], "config": { "condition": { "==": [{ "var": "status" }, "open"] } } }
                  ],
                  "rootNodes": ["root"],
                  "contextVariables": [
                    { "name": "status", "scope": "row", "type": "string" }
                  ]
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-visual-builder",
                operationWithHandler("node.add", "node", "rule-node-by-id", true,
                        "compile-domain-patch", "visual-builder-node-add", "RuleBuilderState.nodes"),
                plan("{}", "{ \"nodeId\": \"effect-node\", \"nodeType\": \"effect\", \"label\": \"Paint row\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-visual-builder",
                operationWithHandler("edge.connect", "edge", "rule-node-edge-by-source-target", true,
                        "compile-domain-patch", "visual-builder-edge-connect", "RuleBuilderState.nodes[].children"),
                plan("{}", "{ \"sourceNodeId\": \"condition\", \"targetNodeId\": \"effect-node\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-visual-builder",
                operationWithHandler("condition.set", "condition", "json-logic-condition-by-node", true,
                        "compile-domain-patch", "visual-builder-condition-set", "RuleBuilderState.nodes[].config.condition"),
                plan("\"condition\"", "{ \"nodeId\": \"condition\", \"condition\": { \"==\": [{ \"var\": \"status\" }, \"approved\"] } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-visual-builder",
                operationWithHandler("effect.set", "effect", "property-effect-by-node", true,
                        "compile-domain-patch", "visual-builder-effect-set", "RuleBuilderState.nodes[].config"),
                plan("\"effect-node\"", "{ \"nodeId\": \"effect-node\", \"targetType\": \"row\", \"targets\": [\"status\"], \"properties\": { \"class\": \"approved\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-visual-builder",
                operationWithHandler("variable.add", "contextVariable", "context-variable-by-name-scope", true,
                        "compile-domain-patch", "visual-builder-variable-add", "RuleBuilderConfig.contextVariables"),
                plan("{}", "{ \"name\": \"priority\", \"scope\": \"row\", \"type\": \"number\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-visual-builder",
                operationWithHandler("dsl.roundTrip.validate", "dslDocument", "rule-builder-json-logic-document", false,
                        "compile-domain-patch", "visual-builder-dsl-round-trip-validate", "RuleBuilderState.currentJSON"),
                plan("{}", "{}"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(6);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("add-visual-builder-node");
        assertThat(patchOperations.get(1).path("op").asText()).isEqualTo("connect-visual-builder-edge");
        assertThat(patchOperations.get(5).path("op").asText()).isEqualTo("validate-visual-builder-dsl-round-trip");
        assertThat(proposedConfig.path("nodes").get(1).path("children").get(0).asText()).isEqualTo("effect-node");
        assertThat(proposedConfig.path("nodes").get(2).path("config").path("properties").path("class").asText()).isEqualTo("approved");
        assertThat(proposedConfig.path("contextVariables").get(1).path("name").asText()).isEqualTo("priority");
        assertThat(proposedConfig.path("currentJSON").path("kind").asText()).isEqualTo("praxis.visual-builder.dsl");
        assertThat(proposedConfig.path("validationErrors")).isEmpty();
    }

    @Test
    void shouldCompileMetadataEditorAuthoringPatches() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "fieldMetadata": { "name": "status", "label": "Status", "controlType": "select" },
                  "properties": [
                    { "name": "label", "editorType": "text" }
                  ],
                  "componentRegistry": [
                    { "controlType": "select" },
                    { "controlType": "pdx-combo" }
                  ],
                  "editorCoverage": []
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-metadata-editor",
                operationWithHandler("fieldMetadata.property.set", "fieldMetadata", "field-metadata-json-path", true,
                        "compile-domain-patch", "metadata-field-property-set", "fieldMetadata.label"),
                plan("{ \"path\": \"label\" }", "{ \"path\": \"label\", \"value\": \"Situação\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-metadata-editor",
                operationWithHandler("controlType.set", "controlType", "dynamic-fields-control-type-discovery", true,
                        "compile-domain-patch", "metadata-control-type-set", "fieldMetadata.controlType"),
                plan("\"select\"", "{ \"controlType\": \"pdx-combo\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-metadata-editor",
                operationWithHandler("optionSource.configure", "optionSource", "field-metadata-option-source", false,
                        "compile-domain-patch", "metadata-option-source-configure", "fieldMetadata.optionSource"),
                plan("{}", "{ \"kind\": \"resource\", \"resource\": \"/api/status\", \"valueField\": \"id\", \"labelField\": \"name\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-metadata-editor",
                operationWithHandler("cascade.configure", "cascade", "metadata-editor-cascade-rules", false,
                        "compile-domain-patch", "metadata-cascade-configure", "fieldMetadata.dependencyFields"),
                plan("{}", "{ \"dependentField\": \"country\", \"sourceField\": \"state\", \"strategy\": \"replace\", \"debounceMs\": 200 }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-metadata-editor",
                operationWithHandler("renderer.configure", "renderer", "metadata-editor-renderer-property", true,
                        "compile-domain-patch", "metadata-renderer-configure", "properties[]"),
                plan("\"label\"", "{ \"propertyName\": \"label\", \"editorType\": \"textarea\", \"group\": \"basic\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-metadata-editor",
                operationWithHandler("validationRule.add", "validation", "field-metadata-validation-rules", false,
                        "compile-domain-patch", "metadata-validation-rule-add", "fieldMetadata.validators[]"),
                plan("{}", "{ \"rule\": { \"type\": \"required\" }, \"message\": \"Required\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-metadata-editor",
                operationWithHandler("contextHint.set", "contextHint", "metadata-editor-context-hints", false,
                        "compile-domain-patch", "metadata-context-hint-set", "fieldMetadata.helpText"),
                plan("{}", "{ \"hintPath\": \"helpText\", \"value\": \"Choose status\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-metadata-editor",
                operationWithHandler("normalization.apply", "normalization", "metadata-editor-schema-normalizer", false,
                        "compile-domain-patch", "metadata-normalization-apply", "normalizedSeed"),
                plan("{}", "{ \"mode\": \"preserve-advanced-properties\", \"preserveUnknownCanonicalFields\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(8);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("set-metadata-field-property");
        assertThat(patchOperations.get(7).path("op").asText()).isEqualTo("apply-metadata-normalization");
        assertThat(proposedConfig.path("fieldMetadata").path("label").asText()).isEqualTo("Situação");
        assertThat(proposedConfig.path("fieldMetadata").path("controlType").asText()).isEqualTo("pdx-combo");
        assertThat(proposedConfig.path("fieldMetadata").path("optionSource").path("resource").asText()).isEqualTo("/api/status");
        assertThat(proposedConfig.path("fieldMetadata").path("dependencyFields").get(0).asText()).isEqualTo("country");
        assertThat(proposedConfig.path("properties").get(0).path("editorType").asText()).isEqualTo("textarea");
        assertThat(proposedConfig.path("fieldMetadata").path("validators")).hasSize(1);
        assertThat(proposedConfig.path("normalizedSeed").path("normalized").asBoolean()).isTrue();
        assertThat(proposedConfig.path("form").path("fieldMetadata").path("helpText").asText()).isEqualTo("Choose status");
    }

    @Test
    void shouldCompileManualFormAuthoringPatches() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "currentConfig": {
                    "fieldMetadata": [
                      { "name": "email", "label": "Email", "controlType": "text" }
                    ],
                    "sections": [
                      { "id": "main", "rows": [ { "id": "r1", "columns": [ { "id": "c1", "fields": ["email"] } ] } ] }
                    ]
                  },
                  "componentRegistry": [
                    { "controlType": "text" },
                    { "controlType": "pdx-email" }
                  ],
                  "form": {
                    "value": { "email": "old@example.com" }
                  }
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-manual-form",
                operationWithHandler("manualField.add", "manualField", "manual-form-field-by-name", true,
                        "compile-domain-patch", "manual-field-add", "currentConfig.fieldMetadata"),
                plan("\"phone\"", "{ \"fieldName\": \"phone\", \"controlType\": \"text\", \"label\": \"Phone\", \"source\": \"manual-template\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-manual-form",
                operationWithHandler("manualField.label.set", "manualField", "manual-form-field-by-name", true,
                        "compile-domain-patch", "manual-field-label-set", "currentConfig.fieldMetadata[].label"),
                plan("\"email\"", "{ \"fieldName\": \"email\", \"label\": \"Work email\", \"updatePlaceholderWhenEmpty\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-manual-form",
                operationWithHandler("layout.configure", "layout", "manual-form-layout", false,
                        "compile-domain-patch", "manual-layout-configure", "currentConfig.sections"),
                plan("{}", "{ \"usePathNames\": true, \"fieldOrder\": [\"phone\", \"email\"] }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-manual-form",
                operationWithHandler("toolbar.configure", "toolbar", "manual-form-customization-toolbar", false,
                        "compile-domain-patch", "manual-toolbar-configure", "enableCustomization"),
                plan("{}", "{ \"enableCustomization\": true, \"editableFlags\": [\"required\", \"openMetadataEditor\"] }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-manual-form",
                operationWithHandler("autosave.enabled.set", "autosave", "manual-form-autosave-policy", false,
                        "compile-domain-patch", "manual-autosave-enabled-set", "enableAutoSave"),
                plan("{}", "{ \"enabled\": true, \"debounceMs\": 500, \"storageKey\": \"manual.customer\", \"namespace\": \"sales\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-manual-form",
                operationWithHandler("submitBehavior.set", "submitBehavior", "manual-form-submit-behavior", false,
                        "compile-domain-patch", "manual-submit-behavior-set", "currentConfig.behavior"),
                plan("{}", "{ \"action\": \"saveDraft\", \"focusFirstError\": true, \"delegateFormSubmitTo\": \"praxis-dynamic-form\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-manual-form",
                operationWithHandler("metadataBridge.configure", "metadataBridge", "manual-field-metadata-bridge", false,
                        "compile-domain-patch", "manual-metadata-bridge-configure", "metadataBridge"),
                plan("{}", "{ \"enabled\": true, \"fieldName\": \"email\", \"openMode\": \"field-toolbar\", \"delegateFieldMetadataTo\": \"praxis-metadata-editor\", \"delegateControlDiscoveryTo\": \"praxis-dynamic-fields\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-manual-form",
                operationWithHandler("manualField.remove", "manualField", "manual-form-field-by-name", true,
                        "compile-domain-patch", "manual-field-remove", "currentConfig.fieldMetadata"),
                plan("\"email\"", "{ \"fieldName\": \"email\", \"removeFromLayout\": true, \"clearPersistedValue\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(8);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("add-manual-field");
        assertThat(patchOperations.get(7).path("op").asText()).isEqualTo("remove-manual-field");
        assertThat(proposedConfig.path("currentConfig").path("fieldMetadata")).hasSize(1);
        assertThat(proposedConfig.path("currentConfig").path("fieldMetadata").get(0).path("name").asText()).isEqualTo("phone");
        assertThat(proposedConfig.path("currentFieldMetadata").get(0).path("name").asText()).isEqualTo("phone");
        assertThat(proposedConfig.path("form").path("value").has("email")).isFalse();
        assertThat(proposedConfig.path("enableCustomization").asBoolean()).isTrue();
        assertThat(proposedConfig.path("enableAutoSave").asBoolean()).isTrue();
        assertThat(proposedConfig.path("persistenceOptions").path("storageKey").asText()).isEqualTo("manual.customer");
        assertThat(proposedConfig.path("currentConfig").path("behavior").path("action").asText()).isEqualTo("saveDraft");
        assertThat(proposedConfig.path("metadataBridge").path("openMode").asText()).isEqualTo("field-toolbar");
    }

    @Test
    void shouldCompileEditorialFormsAuthoringPatches() throws Exception {
        ObjectNode proposedConfig = (ObjectNode) objectMapper.readTree("""
                {
                  "solution": {
                    "solutionId": "onboarding",
                    "journeys": [
                      {
                        "journeyId": "main",
                        "steps": [
                          {
                            "stepId": "start",
                            "blocks": [
                              {
                                "blockId": "profile",
                                "kind": "dataCollection",
                                "fields": [ { "name": "email" } ]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  },
                  "adapterRegistry": [
                    { "adapterId": "dynamic-form", "supportedDataBlockTypes": ["dataCollection"] }
                  ],
                  "snapshot": { "diagnostics": { "items": [] } },
                  "runtimeContext": { "formData": {} },
                  "hostConfig": {}
                }
                """);
        ArrayNode patchOperations = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        registry.appendCompiledEffects(
                "praxis-editorial-forms",
                operationWithHandler("snapshot.set", "snapshot", "editorial-runtime-snapshot", false,
                        "compile-domain-patch", "editorial-snapshot-set", "solution.solutionId"),
                plan("{}", "{ \"solutionId\": \"onboarding\", \"journeyId\": \"main\", \"stepId\": \"start\", \"instanceId\": \"i1\", \"runtimeContextPatch\": { \"locale\": \"pt-BR\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-editorial-forms",
                operationWithHandler("fallback.configure", "fallback", "editorial-runtime-fallback-state", false,
                        "compile-domain-patch", "editorial-fallback-configure", "snapshot.diagnostics.items"),
                plan("{}", "{ \"mode\": \"warning\", \"diagnosticCode\": \"adapter-missing\", \"scope\": { \"journeyId\": \"main\", \"stepId\": \"start\", \"blockId\": \"profile\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-editorial-forms",
                operationWithHandler("presentation.configure", "presentation", "editorial-solution-presentation", false,
                        "compile-domain-patch", "editorial-presentation-configure", "solution.presentation"),
                plan("{}", "{ \"layout\": { \"orientation\": \"vertical\" }, \"stepper\": { \"visible\": true } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-editorial-forms",
                operationWithHandler("adapter.bind", "adapter", "editorial-data-block-adapter-registry", true,
                        "compile-domain-patch", "editorial-adapter-bind", "hostConfig.dataBlockAdapters"),
                plan("\"dynamic-form\"", "{ \"adapterId\": \"dynamic-form\", \"dataBlockType\": \"dataCollection\", \"componentRef\": \"praxis-dynamic-form\", \"forwardOperationalEvents\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-editorial-forms",
                operationWithHandler("dataBlock.add", "dataBlock", "editorial-journey-step-block-by-id", true,
                        "compile-domain-patch", "editorial-data-block-add", "solution.journeys[].steps[].blocks"),
                plan("{ \"journeyId\": \"main\", \"stepId\": \"start\", \"blockId\": \"review\" }", "{ \"journeyId\": \"main\", \"stepId\": \"start\", \"block\": { \"blockId\": \"review\", \"kind\": \"reviewSections\", \"title\": \"Review\" } }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-editorial-forms",
                operationWithHandler("fieldBinding.set", "fieldBinding", "editorial-data-block-field-binding", true,
                        "compile-domain-patch", "editorial-field-binding-set", "runtimeContext.formData"),
                plan("{ \"blockId\": \"profile\", \"fieldName\": \"email\" }", "{ \"blockId\": \"profile\", \"fieldName\": \"email\", \"contextPath\": \"customer.email\", \"mode\": \"readWrite\", \"delegateFieldMetadataTo\": \"praxis-metadata-editor\" }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());
        registry.appendCompiledEffects(
                "praxis-editorial-forms",
                operationWithHandler("dataBlock.remove", "dataBlock", "editorial-journey-step-block-by-id", true,
                        "compile-domain-patch", "editorial-data-block-remove", "solution.journeys[].steps[].blocks"),
                plan("{ \"journeyId\": \"main\", \"stepId\": \"start\", \"blockId\": \"review\" }", "{ \"journeyId\": \"main\", \"stepId\": \"start\", \"blockId\": \"review\", \"requireNoFieldBindings\": true }"),
                proposedConfig,
                patchOperations,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(patchOperations).hasSize(7);
        assertThat(patchOperations.get(0).path("op").asText()).isEqualTo("set-editorial-snapshot");
        assertThat(patchOperations.get(6).path("op").asText()).isEqualTo("remove-editorial-data-block");
        assertThat(proposedConfig.path("runtimeContext").path("locale").asText()).isEqualTo("pt-BR");
        assertThat(proposedConfig.path("snapshot").path("diagnostics").path("items")).hasSize(1);
        assertThat(proposedConfig.path("solution").path("presentation").path("layout").path("orientation").asText()).isEqualTo("vertical");
        assertThat(proposedConfig.path("hostConfig").path("dataBlockAdapters").get(0).path("adapterId").asText()).isEqualTo("dynamic-form");
        assertThat(proposedConfig.path("solution").path("journeys").get(0).path("steps").get(0).path("blocks")).hasSize(1);
        assertThat(proposedConfig.path("solution").path("journeys").get(0).path("steps").get(0).path("blocks").get(0)
                .path("fieldBindings").path("email").path("contextPath").asText()).isEqualTo("customer.email");
    }

    private ObjectNode dynamicFormConfig() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "fieldMetadata": [
                    { "name": "email", "label": "Email", "source": "local" },
                    { "name": "status", "label": "Status", "source": "schema" }
                  ],
                  "sections": [
                    {
                      "id": "main",
                      "rows": [
                        {
                          "id": "r1",
                          "columns": [
                            {
                              "id": "c1",
                              "fields": ["email"],
                              "items": [
                                { "id": "intro", "type": "richContent", "document": { "kind": "praxis.rich-content", "version": "1.0.0", "nodes": [] } },
                                { "type": "field", "field": "status" }
                              ]
                            }
                          ]
                        }
                      ]
                    },
                    {
                      "id": "secondary",
                      "rows": [
                        {
                          "id": "r2",
                          "columns": [
                            { "id": "c2", "items": [] }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);
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
