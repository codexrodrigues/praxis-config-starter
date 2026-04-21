package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringTargetResolverRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringTargetResolverRegistry registry =
            new AgenticAuthoringTargetResolverRegistry();

    @Test
    void shouldResolveColumnByFieldUsingOperationTargetResolver() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-table",
                operation("column.header.set", "column", "column-by-field", "fail", true),
                objectMapper.readTree("\"email\""),
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "email", "header": "Email" }
                          ]
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.kind()).isEqualTo("column");
        assertThat(result.resolver()).isEqualTo("column-by-field");
        assertThat(result.path()).isEqualTo("columns[]/0");
        assertThat(result.value().path("header").asText()).isEqualTo("Email");
    }

    @Test
    void shouldFailAmbiguousTargetByDefault() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-table",
                operation("column.header.set", "column", "column-by-field", "fail", true),
                objectMapper.readTree("\"email\""),
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "email", "header": "Primary" },
                            { "field": "email", "header": "Duplicate" }
                          ]
                        }
                        """));

        assertThat(result.status()).isEqualTo("ambiguous");
        assertThat(result.candidates()).containsExactly("columns[]/0", "columns[]/1");
        assertThat(result.failures()).contains("target is ambiguous: email");
    }

    @Test
    void shouldSelectFirstAmbiguousTargetWhenPolicyAllowsIt() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-table",
                operation("column.header.set", "column", "column-by-field", "first", true),
                objectMapper.readTree("\"email\""),
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "email", "header": "Primary" },
                            { "field": "email", "header": "Duplicate" }
                          ]
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.path()).isEqualTo("columns[]/0");
        assertThat(result.candidates()).containsExactly("columns[]/0", "columns[]/1");
        assertThat(result.failures()).contains("target matched multiple candidates; first candidate selected by ambiguityPolicy=first");
    }

    @Test
    void shouldTreatGlobalOptionalResolverAsNotRequired() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-table",
                operation("toolbar.visibility.set", "toolbar", "toolbar-config", "fail", false),
                objectMapper.createObjectNode(),
                objectMapper.readTree("{}"));

        assertThat(result.status()).isEqualTo("not-required");
        assertThat(result.kind()).isEqualTo("toolbar");
        assertThat(result.resolver()).isEqualTo("toolbar-config");
    }

    @Test
    void shouldTreatChartRootResolversAsGlobalWhenOptional() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-chart",
                operation("data.resource.bind", "dataBinding", "x-ui-chart-source-and-field-catalog", "fail", false),
                objectMapper.createObjectNode(),
                objectMapper.readTree("{ \"chartDocument\": { \"kind\": \"bar\" } }"));

        assertThat(result.status()).isEqualTo("not-required");
        assertThat(result.kind()).isEqualTo("dataBinding");
        assertThat(result.resolver()).isEqualTo("x-ui-chart-source-and-field-catalog");
    }

    @Test
    void shouldResolveExpansionPanelByIdOrTitle() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-expansion",
                operation("panel.order.set", "panel", "panel-by-id-or-title", "fail", true),
                objectMapper.readTree("\"summary\""),
                objectMapper.readTree("""
                        {
                          "panels": [
                            { "id": "summary", "title": "Summary" }
                          ]
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("panel-by-id-or-title");
        assertThat(result.path()).isEqualTo("panels[]/0");
    }

    @Test
    void shouldResolveRichContentBlockById() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-rich-content",
                operation("block.order.set", "block", "rich-block-by-id-or-index", "fail", true),
                objectMapper.readTree("\"body\""),
                objectMapper.readTree("""
                        {
                          "document": {
                            "nodes": [
                              { "id": "body", "kind": "text" }
                            ]
                          }
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("rich-block-by-id-or-index");
        assertThat(result.path()).isEqualTo("document.nodes[]/0");
    }

    @Test
    void shouldResolveRichMediaNodeById() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-rich-content",
                operation("mediaBlock.update", "media", "rich-media-node-by-id-or-path", "fail", true),
                objectMapper.readTree("\"profile\""),
                objectMapper.readTree("""
                        {
                          "document": {
                            "nodes": [
                              { "id": "profile", "type": "mediaBlock", "title": "Profile" }
                            ]
                          }
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("rich-media-node-by-id-or-path");
        assertThat(result.path()).isEqualTo("document.nodes[]/0");
        assertThat(result.value().path("type").asText()).isEqualTo("mediaBlock");
    }

    @Test
    void shouldResolveRichLinkNodeById() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-rich-content",
                operation("link.remove", "link", "rich-link-node-by-id-or-path", "fail", true),
                objectMapper.readTree("\"terms-link\""),
                objectMapper.readTree("""
                        {
                          "document": {
                            "nodes": [
                              { "id": "terms-link", "type": "link", "label": "Terms", "href": "/terms" }
                            ]
                          }
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("rich-link-node-by-id-or-path");
        assertThat(result.path()).isEqualTo("document.nodes[]/0");
        assertThat(result.value().path("type").asText()).isEqualTo("link");
    }

    @Test
    void shouldResolveRichTimelineNodeById() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-rich-content",
                operation("timeline.item.add", "timelineItem", "rich-timeline-node-by-id-or-path", "fail", true),
                objectMapper.readTree("\"history\""),
                objectMapper.readTree("""
                        {
                          "document": {
                            "nodes": [
                              { "id": "history", "type": "timeline", "items": [] }
                            ]
                          }
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("rich-timeline-node-by-id-or-path");
        assertThat(result.path()).isEqualTo("document.nodes[]/0");
        assertThat(result.value().path("type").asText()).isEqualTo("timeline");
    }

    @Test
    void shouldResolveRichTimelineItemByBlockAndItemId() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-rich-content",
                operation("timeline.item.update", "timelineItem", "rich-timeline-item-by-block-id-and-item-id", "fail", true),
                objectMapper.readTree("""
                        {
                          "timelineBlockId": "history",
                          "itemId": "published"
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "document": {
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
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("rich-timeline-item-by-block-id-and-item-id");
        assertThat(result.path()).isEqualTo("document.nodes[]/0.items[]/1");
        assertThat(result.value().path("title").asText()).isEqualTo("Published");
    }

    @Test
    void shouldResolveTableConditionalRendererById() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-table",
                operation("column.conditionalRenderer.add", "conditionalRenderer", "conditional-renderer-in-column", "fail", true),
                objectMapper.readTree("\"status-badge\""),
                objectMapper.readTree("""
                        {
                          "columns": [
                            {
                              "field": "status",
                              "conditionalRenderers": [
                                { "id": "status-badge", "type": "badge" }
                              ]
                            }
                          ]
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("conditional-renderer-in-column");
        assertThat(result.path()).isEqualTo("columns[]/0.conditionalRenderers[]/0");
    }

    @Test
    void shouldResolveStepperStepByLabel() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-stepper",
                operation("step.label.set", "stepLabel", "step-by-id-or-label", "fail", true),
                objectMapper.readTree("\"Review\""),
                objectMapper.readTree("""
                        {
                          "steps": [
                            { "id": "review", "label": "Review" }
                          ]
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("step-by-id-or-label");
        assertThat(result.path()).isEqualTo("steps[]/0");
    }

    @Test
    void shouldResolveTabByIndexOrId() throws Exception {
        JsonNode config = objectMapper.readTree("""
                {
                  "tabs": [
                    { "id": "general", "textLabel": "Geral" },
                    { "id": "security", "textLabel": "Seguranca" }
                  ]
                }
                """);

        AgenticAuthoringResolvedTarget byIndex = registry.resolve(
                "praxis-tabs",
                operation("tab.active.set", "activeTab", "tab-index-or-id", "fail", true),
                objectMapper.readTree("1"),
                config);
        AgenticAuthoringResolvedTarget byId = registry.resolve(
                "praxis-tabs",
                operation("tab.active.set", "activeTab", "tab-index-or-id", "fail", true),
                objectMapper.readTree("\"security\""),
                config);

        assertThat(byIndex.status()).isEqualTo("resolved");
        assertThat(byIndex.resolver()).isEqualTo("tab-index-or-id");
        assertThat(byIndex.path()).isEqualTo("tabs[]/1");
        assertThat(byIndex.value().path("id").asText()).isEqualTo("security");
        assertThat(byId.status()).isEqualTo("resolved");
        assertThat(byId.path()).isEqualTo("tabs[]/1");
    }

    @Test
    void shouldResolveDynamicFieldsAliasesSelectorsAndCoverageTargets() throws Exception {
        JsonNode config = objectMapper.readTree("""
                {
                  "componentRegistry": [
                    { "controlType": "pdx-money", "selector": "pdx-money-input" }
                  ],
                  "controlTypeAliases": [
                    { "alias": "Currency Money", "normalizedAlias": "currency-money", "controlType": "pdx-money" }
                  ],
                  "selectorMappings": [
                    { "selector": "[data-money]", "controlType": "pdx-money" }
                  ],
                  "editorCoverage": [
                    { "controlType": "pdx-money", "metadataEditor": true }
                  ]
                }
                """);

        AgenticAuthoringResolvedTarget alias = registry.resolve(
                "praxis-dynamic-fields",
                operation("controlType.alias.remove", "controlAlias", "normalized-control-type-alias", "fail", true),
                objectMapper.readTree("\"currency money\""),
                config);
        AgenticAuthoringResolvedTarget selector = registry.resolve(
                "praxis-dynamic-fields",
                operation("selector.mapping.set", "selector", "field-selector-registry-entry", "fail", true),
                objectMapper.readTree("\"[data-money]\""),
                config);
        AgenticAuthoringResolvedTarget coverage = registry.resolve(
                "praxis-dynamic-fields",
                operation("editorCoverage.validate", "editorCoverage", "metadata-editor-tooling-coverage", "fail", true),
                objectMapper.readTree("\"pdx-money\""),
                config);

        assertThat(alias.status()).isEqualTo("resolved");
        assertThat(alias.path()).isEqualTo("controlTypeAliases[]/0");
        assertThat(selector.status()).isEqualTo("resolved");
        assertThat(selector.path()).isEqualTo("selectorMappings[]/0");
        assertThat(coverage.status()).isEqualTo("resolved");
        assertThat(coverage.path()).isEqualTo("editorCoverage[]/0");
    }

    @Test
    void shouldResolveCrudActionAndGlobalCrudTargets() throws Exception {
        JsonNode config = objectMapper.readTree("""
                {
                  "resource": { "path": "/customers" },
                  "actions": [
                    { "id": "create" },
                    { "id": "delete" }
                  ]
                }
                """);

        AgenticAuthoringResolvedTarget createAction = registry.resolve(
                "praxis-crud",
                operation("surface.create.configure", "createSurface", "crud-action-by-id:create", "fail", true),
                objectMapper.readTree("\"create\""),
                config);
        AgenticAuthoringResolvedTarget resource = registry.resolve(
                "praxis-crud",
                operation("resource.bind", "resourceBinding", "crud-resource-by-path-or-key", "fail", true),
                objectMapper.readTree("{\"resourcePath\":\"/customers\"}"),
                config);
        AgenticAuthoringResolvedTarget permissions = registry.resolve(
                "praxis-crud",
                operation("permissions.set", "permissions", "crud-resource-capabilities", "fail", true),
                objectMapper.readTree("{}"),
                config);

        assertThat(createAction.status()).isEqualTo("resolved");
        assertThat(createAction.path()).isEqualTo("actions[]/0");
        assertThat(resource.status()).isEqualTo("resolved");
        assertThat(resource.path()).isEqualTo("$");
        assertThat(permissions.status()).isEqualTo("resolved");
        assertThat(permissions.path()).isEqualTo("$");
    }

    @Test
    void shouldResolveDynamicFormLayoutItemById() throws Exception {
        AgenticAuthoringResolvedTarget result = registry.resolve(
                "praxis-dynamic-form",
                operation("layout.visualBlock.update", "visualBlock", "layout-item-by-id", "fail", true),
                objectMapper.readTree("\"intro\""),
                objectMapper.readTree("""
                        {
                          "sections": [
                            {
                              "id": "main",
                              "rows": [
                                {
                                  "id": "r1",
                                  "columns": [
                                    {
                                      "id": "c1",
                                      "items": [
                                        { "id": "intro", "type": "richContent" }
                                      ]
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                        """));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("layout-item-by-id");
        assertThat(result.path()).isEqualTo("sections[]/0.rows[]/0.columns[]/0.items[]/0");
        assertThat(result.value().path("type").asText()).isEqualTo("richContent");
    }

    @Test
    void shouldResolveTableRuleAndNestedEffectTargets() throws Exception {
        JsonNode config = objectMapper.readTree("""
                {
                  "ruleEffects": {
                    "rules": [
                      {
                        "ruleId": "sla",
                        "effects": [
                          { "effectId": "paint", "effectType": "fundo" }
                        ]
                      }
                    ]
                  }
                }
                """);

        AgenticAuthoringResolvedTarget rule = registry.resolve(
                "praxis-table-rule-builder",
                operation("condition.set", "condition", "table-rule-condition-by-rule-id", "fail", true),
                objectMapper.readTree("{ \"ruleId\": \"sla\" }"),
                config);
        AgenticAuthoringResolvedTarget effect = registry.resolve(
                "praxis-table-rule-builder",
                operation("effect.update", "effect", "rule-effect-by-rule-and-effect-id", "fail", true),
                objectMapper.readTree("{ \"ruleId\": \"sla\", \"effectId\": \"paint\" }"),
                config);

        assertThat(rule.status()).isEqualTo("resolved");
        assertThat(rule.path()).isEqualTo("ruleEffects.rules[]/0");
        assertThat(effect.status()).isEqualTo("resolved");
        assertThat(effect.path()).isEqualTo("ruleEffects.rules[]/0.effects[]/0");
    }

    @Test
    void shouldResolveVisualBuilderNodeEdgeVariableAndGlobalDslTargets() throws Exception {
        JsonNode config = objectMapper.readTree("""
                {
                  "nodes": [
                    { "id": "root", "nodeId": "root", "children": ["condition"] },
                    { "id": "condition", "nodeId": "condition", "parentId": "root", "children": [] }
                  ],
                  "contextVariables": [
                    { "name": "status", "scope": "row", "type": "string" }
                  ]
                }
                """);

        AgenticAuthoringResolvedTarget node = registry.resolve(
                "praxis-visual-builder",
                operation("node.configure", "node", "rule-node-by-id", "fail", true),
                objectMapper.readTree("\"condition\""),
                config);
        AgenticAuthoringResolvedTarget edge = registry.resolve(
                "praxis-visual-builder",
                operation("edge.remove", "edge", "rule-node-edge-by-source-target", "fail", true),
                objectMapper.readTree("{ \"sourceNodeId\": \"root\", \"targetNodeId\": \"condition\" }"),
                config);
        AgenticAuthoringResolvedTarget variable = registry.resolve(
                "praxis-visual-builder",
                operation("variable.update", "contextVariable", "context-variable-by-name-scope", "fail", true),
                objectMapper.readTree("{ \"name\": \"status\", \"scope\": \"row\" }"),
                config);
        AgenticAuthoringResolvedTarget dsl = registry.resolve(
                "praxis-visual-builder",
                operation("dsl.roundTrip.validate", "dslDocument", "rule-builder-json-logic-document", "fail", false),
                objectMapper.readTree("{}"),
                config);

        assertThat(node.status()).isEqualTo("resolved");
        assertThat(node.path()).isEqualTo("nodes[]/1");
        assertThat(edge.status()).isEqualTo("resolved");
        assertThat(edge.path()).isEqualTo("nodes[]/0.children[]/0");
        assertThat(variable.status()).isEqualTo("resolved");
        assertThat(variable.path()).isEqualTo("contextVariables[]/0");
        assertThat(dsl.status()).isEqualTo("not-required");
        assertThat(dsl.path()).isEmpty();
    }

    @Test
    void shouldResolveMetadataEditorTargets() throws Exception {
        JsonNode config = objectMapper.readTree("""
                {
                  "fieldMetadata": {
                    "name": "status",
                    "label": "Status",
                    "controlType": "select",
                    "validators": []
                  },
                  "componentRegistry": [
                    { "controlType": "select" }
                  ],
                  "properties": [
                    { "name": "label", "editorType": "text" }
                  ]
                }
                """);

        AgenticAuthoringResolvedTarget fieldPath = registry.resolve(
                "praxis-metadata-editor",
                operation("fieldMetadata.property.set", "fieldMetadata", "field-metadata-json-path", "fail", true),
                objectMapper.readTree("{ \"path\": \"label\" }"),
                config);
        AgenticAuthoringResolvedTarget controlType = registry.resolve(
                "praxis-metadata-editor",
                operation("controlType.set", "controlType", "dynamic-fields-control-type-discovery", "fail", true),
                objectMapper.readTree("\"select\""),
                config);
        AgenticAuthoringResolvedTarget renderer = registry.resolve(
                "praxis-metadata-editor",
                operation("renderer.configure", "renderer", "metadata-editor-renderer-property", "fail", true),
                objectMapper.readTree("{ \"propertyName\": \"label\" }"),
                config);
        AgenticAuthoringResolvedTarget validator = registry.resolve(
                "praxis-metadata-editor",
                operation("validationRule.add", "validation", "field-metadata-validation-rules", "fail", false),
                objectMapper.readTree("{}"),
                config);
        AgenticAuthoringResolvedTarget normalizer = registry.resolve(
                "praxis-metadata-editor",
                operation("normalization.apply", "normalization", "metadata-editor-schema-normalizer", "fail", false),
                objectMapper.readTree("{}"),
                config);

        assertThat(fieldPath.status()).isEqualTo("resolved");
        assertThat(fieldPath.path()).isEqualTo("fieldMetadata.label");
        assertThat(controlType.status()).isEqualTo("resolved");
        assertThat(controlType.path()).isEqualTo("componentRegistry[]/0");
        assertThat(renderer.status()).isEqualTo("resolved");
        assertThat(renderer.path()).isEqualTo("properties[]/0");
        assertThat(validator.status()).isEqualTo("not-required");
        assertThat(normalizer.status()).isEqualTo("not-required");
    }

    private JsonNode operation(
            String operationId,
            String kind,
            String resolver,
            String ambiguityPolicy,
            boolean required) throws Exception {
        return objectMapper.readTree("""
                {
                  "operationId": "%s",
                  "target": {
                    "kind": "%s",
                    "resolver": "%s",
                    "ambiguityPolicy": "%s",
                    "required": %s
                  }
                }
                """.formatted(operationId, kind, resolver, ambiguityPolicy, required));
    }
}
