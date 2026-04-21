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
