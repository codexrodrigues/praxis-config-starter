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
class KpiAgenticAuthoringCompilerTest {

    private static final String CARD_1_ID = "11111111-1111-4111-8111-111111111111";
    private static final String CARD_2_ID = "22222222-2222-4222-8222-222222222222";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringTargetResolverRegistry resolverRegistry =
            new AgenticAuthoringTargetResolverRegistry();
    private final AgenticAuthoringValidatorRegistry validatorRegistry =
            new AgenticAuthoringValidatorRegistry(resolverRegistry);
    private final AgenticAuthoringEffectCompilerRegistry compilerRegistry =
            new AgenticAuthoringEffectCompilerRegistry(objectMapper, resolverRegistry);

    private ObjectNode baseStripConfig() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "title": "Resumo Executivo",
                  "columns": 4,
                  "minCardWidthPx": 240,
                  "density": "comfortable",
                  "cards": [
                    {
                      "schemaVersion": 1,
                      "id": "%s",
                      "presentation": { "label": "Taxa Ativa", "icon": "groups", "format": "percent" },
                      "source": { "kind": "verified-recipe", "resourcePath": "workforce/assignments", "recipeId": "active-rate" },
                      "derivation": { "kind": "ratio", "numeratorKeys": ["ACTIVE"], "denominatorMode": "population-total" },
                      "visualization": { "kind": "bullet", "target": 85 }
                    },
                    {
                      "schemaVersion": 1,
                      "id": "%s",
                      "presentation": { "label": "Total Bloqueado", "icon": "verified_user", "format": "integer" },
                      "source": { "kind": "stats-query", "resourcePath": "workforce/employees", "operation": "group-by", "request": { "field": "status", "metric": { "operation": "COUNT" } } },
                      "derivation": { "kind": "scalar", "aggregation": "total" },
                      "visualization": { "kind": "number-only" },
                      "governance": { "lockLevel": "metric-locked" }
                    }
                  ]
                }
                """.formatted(CARD_1_ID, CARD_2_ID));
    }

    @Test
    void supportsAllKpiResolversValidatorsAndHandlers() {
        assertThat(resolverRegistry.supportsResolver("kpi-strip-root")).isTrue();
        assertThat(resolverRegistry.supportsResolver("kpi-card-root")).isTrue();
        assertThat(resolverRegistry.supportsResolver("kpi-card-by-id")).isTrue();

        for (String validatorId : List.of(
                "kpi-strip-config-valid",
                "kpi-card-spec-valid",
                "kpi-card-exists",
                "kpi-card-id-unique",
                "kpi-card-limit-bounded",
                "kpi-card-minimum-preserved",
                "kpi-card-lock-policy-respected",
                "kpi-resource-path-canonical",
                "kpi-verified-recipe-valid",
                "kpi-stats-query-valid",
                "kpi-visualization-compatible-with-derivation",
                "kpi-thresholds-ordered-and-bounded",
                "kpi-icon-snake-case",
                "kpi-interaction-valid",
                "kpi-removal-confirmed")) {
            assertThat(AgenticAuthoringValidatorRegistry.supportsValidator(validatorId)).isTrue();
        }

        for (String handler : List.of(
                "kpi-card-add-from-recipe",
                "kpi-card-add-from-stats-query",
                "kpi-card-update-presentation",
                "kpi-card-set-target-and-thresholds",
                "kpi-card-change-visualization",
                "kpi-card-set-interaction",
                "kpi-strip-reorder-cards",
                "kpi-strip-duplicate-card",
                "kpi-strip-remove-card")) {
            assertThat(compilerRegistry.supportsDomainPatchHandler(handler)).isTrue();
        }
    }

    @Test
    void enforcesGovernanceLocksSsrfPathsMatrixBoundsAndConfirmation() throws Exception {
        ObjectNode strip = baseStripConfig();

        // 1. Fail-closed metric-locked check on kpi.setTargetAndThresholds
        List<String> lockFailures = new ArrayList<>();
        validatorRegistry.executeOperationValidators(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.setTargetAndThresholds",
                          "target": { "kind": "kpiCard", "resolver": "kpi-card-by-id", "required": true },
                          "validators": ["kpi-card-exists", "kpi-card-lock-policy-respected", "kpi-thresholds-ordered-and-bounded"]
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.setTargetAndThresholds",
                          "target": { "cardId": "%s" },
                          "input": { "cardId": "%s", "target": 95, "thresholds": [{ "max": 80, "tone": "warning" }, { "max": 60, "tone": "danger" }] }
                        }
                        """.formatted(CARD_2_ID, CARD_2_ID)),
                strip,
                lockFailures,
                new ArrayList<>());
        assertThat(lockFailures)
                .anyMatch(msg -> msg.contains("kpi-card-lock-policy-respected"))
                .anyMatch(msg -> msg.contains("kpi-thresholds-ordered-and-bounded"));

        // 2. Reject SSRF / absolute URL + PascalCase icon + incompatible stackedBar for scalar
        List<String> securityFailures = new ArrayList<>();
        validatorRegistry.executeOperationValidators(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.addFromStatsQuery",
                          "target": { "kind": "kpiStrip", "resolver": "kpi-strip-root", "required": false },
                          "validators": ["kpi-resource-path-canonical", "kpi-stats-query-valid", "kpi-icon-snake-case", "kpi-visualization-compatible-with-derivation"]
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.addFromStatsQuery",
                          "input": {
                            "resourcePath": "https://evil.example/api/workforce",
                            "operation": "group-by",
                            "field": "status",
                            "icon": "TrendingUp",
                            "derivation": { "kind": "scalar" },
                            "visualization": { "kind": "stackedBar" }
                          }
                        }
                        """),
                strip,
                securityFailures,
                new ArrayList<>());
        assertThat(securityFailures)
                .anyMatch(msg -> msg.contains("kpi-resource-path-canonical"))
                .anyMatch(msg -> msg.contains("kpi-icon-snake-case"))
                .anyMatch(msg -> msg.contains("kpi-visualization-compatible-with-derivation"));

        // 3. Reject unsafe javascript: drillDown & unconfirmed removal
        List<String> unsafeInteraction = new ArrayList<>();
        validatorRegistry.executeOperationValidators(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.setInteraction",
                          "target": { "kind": "kpiCard", "resolver": "kpi-card-by-id", "required": true },
                          "validators": ["kpi-card-exists", "kpi-interaction-valid"]
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.setInteraction",
                          "target": { "cardId": "%s" },
                          "input": { "cardId": "%s", "drillDown": { "route": "javascript:alert(1)" } }
                        }
                        """.formatted(CARD_1_ID, CARD_1_ID)),
                strip,
                unsafeInteraction,
                new ArrayList<>());
        assertThat(unsafeInteraction).anyMatch(msg -> msg.contains("kpi-interaction-valid"));

        List<String> unconfirmedRemoval = new ArrayList<>();
        validatorRegistry.executeOperationValidators(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.removeCard",
                          "target": { "kind": "kpiCard", "resolver": "kpi-card-by-id", "required": true },
                          "validators": ["kpi-card-exists", "kpi-card-minimum-preserved", "kpi-removal-confirmed"]
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.removeCard",
                          "target": { "cardId": "%s" },
                          "confirmed": false,
                          "input": { "cardId": "%s", "confirmRemoval": false }
                        }
                        """.formatted(CARD_1_ID, CARD_1_ID)),
                strip,
                unconfirmedRemoval,
                new ArrayList<>());
        assertThat(unconfirmedRemoval).anyMatch(msg -> msg.contains("kpi-removal-confirmed"));

        // 4. Reject >12 card bound
        ObjectNode maxStrip = baseStripConfig();
        ArrayNode maxCards = (ArrayNode) maxStrip.path("cards");
        while (maxCards.size() < 12) {
            maxCards.add(maxCards.get(0).deepCopy());
        }
        List<String> limitFailures = new ArrayList<>();
        validatorRegistry.executeOperationValidators(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.addFromRecipe",
                          "validators": ["kpi-card-limit-bounded"]
                        }
                        """),
                objectMapper.readTree("""
                        { "operationId": "kpi.addFromRecipe", "input": {} }
                        """),
                maxStrip,
                limitFailures,
                new ArrayList<>());
        assertThat(limitFailures).anyMatch(msg -> msg.contains("kpi-card-limit-bounded"));
    }

    @Test
    void compilesAddUpdateTargetVisualizationInteractionDuplicateReorderAndRemovePatches() throws Exception {
        ObjectNode strip = baseStripConfig();
        ArrayNode patches = objectMapper.createArrayNode();
        List<String> failures = new ArrayList<>();

        // Add from recipe
        String addedUuid = "33333333-3333-4333-8333-333333333333";
        compilerRegistry.appendCompiledEffects(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.addFromRecipe",
                          "effects": [{ "kind": "compile-domain-patch", "handler": "kpi-card-add-from-recipe" }],
                          "affectedPaths": ["cards[]", "cards[].source", "cards[].source.resourcePath"],
                          "submissionImpact": "affects-remote-binding"
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.addFromRecipe",
                          "input": {
                            "id": "%s",
                            "resourcePath": "workforce/vacancies",
                            "recipeId": "open-vacancies",
                            "label": "Vagas Abertas",
                            "icon": "work",
                            "format": "integer",
                            "derivation": { "kind": "scalar", "aggregation": "total" },
                            "visualization": { "kind": "bullet", "target": 12 }
                          }
                        }
                        """.formatted(addedUuid)),
                strip,
                patches,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(strip.path("cards")).hasSize(3);
        assertThat(strip.path("cards").get(2).path("source").path("kind").asText()).isEqualTo("verified-recipe");

        // Update presentation + change visualization + set target & interaction on card 1
        compilerRegistry.appendCompiledEffects(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.updatePresentation",
                          "target": { "kind": "kpiCard", "resolver": "kpi-card-by-id", "required": true },
                          "effects": [{ "kind": "compile-domain-patch", "handler": "kpi-card-update-presentation" }],
                          "affectedPaths": ["cards[].presentation"],
                          "submissionImpact": "visual-only"
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.updatePresentation",
                          "target": "%s",
                          "input": { "cardId": "%s", "label": "Taxa Revisada", "tone": "success" }
                        }
                        """.formatted(CARD_1_ID, CARD_1_ID)),
                strip,
                patches,
                failures,
                new ArrayList<>());

        compilerRegistry.appendCompiledEffects(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.changeVisualization",
                          "target": { "kind": "kpiCard", "resolver": "kpi-card-by-id", "required": true },
                          "effects": [{ "kind": "compile-domain-patch", "handler": "kpi-card-change-visualization" }],
                          "affectedPaths": ["cards[].visualization", "cards[].visualization.kind"],
                          "submissionImpact": "visual-only"
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.changeVisualization",
                          "target": "%s",
                          "input": { "cardId": "%s", "kind": "radial" }
                        }
                        """.formatted(CARD_1_ID, CARD_1_ID)),
                strip,
                patches,
                failures,
                new ArrayList<>());

        compilerRegistry.appendCompiledEffects(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.setInteraction",
                          "target": { "kind": "kpiCard", "resolver": "kpi-card-by-id", "required": true },
                          "effects": [{ "kind": "compile-domain-patch", "handler": "kpi-card-set-interaction" }],
                          "affectedPaths": ["cards[].interaction"],
                          "submissionImpact": "config-only"
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.setInteraction",
                          "target": "%s",
                          "input": {
                            "cardId": "%s",
                            "crossFilter": { "field": "statuses", "values": ["ACTIVE"] },
                            "filterReactionMode": "page-scope-only"
                          }
                        }
                        """.formatted(CARD_1_ID, CARD_1_ID)),
                strip,
                patches,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        JsonNode card1 = strip.path("cards").get(0);
        assertThat(card1.path("presentation").path("label").asText()).isEqualTo("Taxa Revisada");
        assertThat(card1.path("visualization").path("kind").asText()).isEqualTo("radial");
        assertThat(card1.path("interaction").path("filterReaction").path("mode").asText())
                .isEqualTo("page-scope-only");

        // Duplicate, reorder, remove
        String dupUuid = "44444444-4444-4444-8444-444444444444";
        compilerRegistry.appendCompiledEffects(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.duplicateCard",
                          "target": { "kind": "kpiCard", "resolver": "kpi-card-by-id", "required": true },
                          "effects": [{ "kind": "compile-domain-patch", "handler": "kpi-strip-duplicate-card" }],
                          "affectedPaths": ["cards[]"],
                          "submissionImpact": "config-only"
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.duplicateCard",
                          "target": "%s",
                          "input": { "cardId": "%s", "newCardId": "%s", "newLabel": "Vagas Copia" }
                        }
                        """.formatted(addedUuid, addedUuid, dupUuid)),
                strip,
                patches,
                failures,
                new ArrayList<>());

        compilerRegistry.appendCompiledEffects(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.reorderCards",
                          "effects": [{ "kind": "compile-domain-patch", "handler": "kpi-strip-reorder-cards" }],
                          "affectedPaths": ["cards[]"],
                          "submissionImpact": "visual-only"
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.reorderCards",
                          "input": { "orderedIds": ["%s", "%s", "%s", "%s"] }
                        }
                        """.formatted(dupUuid, CARD_1_ID, CARD_2_ID, addedUuid)),
                strip,
                patches,
                failures,
                new ArrayList<>());

        assertThat(strip.path("cards").get(0).path("id").asText()).isEqualTo(dupUuid);

        compilerRegistry.appendCompiledEffects(
                "praxis-kpi-strip",
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.removeCard",
                          "target": { "kind": "kpiCard", "resolver": "kpi-card-by-id", "required": true },
                          "effects": [{ "kind": "compile-domain-patch", "handler": "kpi-strip-remove-card" }],
                          "affectedPaths": ["cards[]"],
                          "submissionImpact": "config-only"
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "operationId": "kpi.removeCard",
                          "target": "%s",
                          "confirmed": true,
                          "input": { "cardId": "%s", "confirmRemoval": true }
                        }
                        """.formatted(dupUuid, dupUuid)),
                strip,
                patches,
                failures,
                new ArrayList<>());

        assertThat(failures).isEmpty();
        assertThat(strip.path("cards")).hasSize(3);
    }
}
