package org.praxisplatform.config.ai.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentAnalyticalProvenance;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult;
import org.praxisplatform.config.ai.agent.spi.AgentVerifiedRecipeExecutionRequest;
import org.springframework.util.StringUtils;

/**
 * Ferramenta prioritaria para execucao fail-closed de uma {@code @VerifiedAnalyticalRecipe}
 * declarada pelo backend do recurso, preservando filtros travados e carimbo de proveniencia.
 */
public class ExecuteVerifiedAnalyticalRecipeTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "execute_verified_analytical_recipe";

    private final AgentResourceExecutionBridge bridge;

    public ExecuteVerifiedAnalyticalRecipeTool(AgentResourceExecutionBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public AgentToolDeclaration declaration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entityKey", Map.of(
                "type", "string",
                "description", "Entity key or canonical resourcePath from discover_topic_resources_and_schemas."));
        properties.put("recipeId", Map.of(
                "type", "string",
                "description", "Exact id of a verified recipe listed in schema digest verifiedRecipes."));
        properties.put("parameters", Map.of(
                "type", "object",
                "description", "Bounded adjustable parameter map declared by the recipe (never overrides server lockedFilters)."));
        properties.put("timezone", Map.of(
                "type", "string",
                "description", "Optional IANA timezone (e.g. America/Sao_Paulo)."));
        properties.put("chartKind", Map.of(
                "type", "string",
                "description", "Optional chart visualization override: bar, horizontal-bar, pie, donut, line."));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Optional short display title for the chart."));

        return new AgentToolDeclaration(
                TOOL_NAME,
                "Execute a backend-verified analytical recipe (group-by, timeseries, distribution, or comparison) with immutable server filters and deterministic provenance.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "recipeId"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(
            AgentExecutionContext context,
            TurnScopedPiiVault piiVault,
            AgentTurnArtifactStore artifacts,
            Map<String, Object> rawArguments) {
        Map<String, Object> args = piiVault != null && rawArguments != null
                ? piiVault.rehydrateArguments(rawArguments)
                : (rawArguments == null ? Map.of() : rawArguments);

        String entityKey = args.get("entityKey") != null
                ? String.valueOf(args.get("entityKey")).trim()
                : "";
        String recipeId = args.get("recipeId") != null
                ? String.valueOf(args.get("recipeId")).trim()
                : "";
        if (!StringUtils.hasText(entityKey)) {
            throw new IllegalArgumentException("entityKey is required for execute_verified_analytical_recipe");
        }
        if (!StringUtils.hasText(recipeId)) {
            throw new IllegalArgumentException("recipeId is required for execute_verified_analytical_recipe");
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        Object rawParams = args.get("parameters");
        if (rawParams instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    parameters.put(String.valueOf(k), v);
                }
            });
        }
        String timezone = args.get("timezone") != null
                ? String.valueOf(args.get("timezone")).trim()
                : null;
        String chartKind = args.get("chartKind") != null
                ? String.valueOf(args.get("chartKind")).trim()
                : null;
        String title = args.get("title") != null
                ? String.valueOf(args.get("title")).trim()
                : null;

        AgentStatsResult result = bridge.executeVerifiedAnalyticalRecipe(
                context,
                new AgentVerifiedRecipeExecutionRequest(
                        entityKey,
                        recipeId,
                        parameters,
                        timezone,
                        chartKind,
                        title));

        if (artifacts != null) {
            artifacts.recordStatsResult(result);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityKey", result.entityKey());
        out.put("resourceKey", result.resourceKey());
        if (result.resourcePath() != null && !result.resourcePath().isBlank()) {
            out.put("resourcePath", result.resourcePath());
        }
        out.put("title", result.title());
        out.put("operationKind", result.operationKind());
        out.put("field", result.field());
        out.put("metricOperation", result.metricOperation());
        out.put("totalElements", result.totalElements());
        out.put("bucketCount", result.buckets().size());
        out.put("buckets", result.buckets().stream()
                .map(b -> {
                    Map<String, Object> bucket = new LinkedHashMap<>();
                    bucket.put("key", b.key());
                    bucket.put("label", b.label());
                    bucket.put("value", b.value());
                    bucket.put("count", b.count());
                    if (b.previousValue() != null) {
                        bucket.put("previousValue", b.previousValue());
                    }
                    if (b.delta() != null) {
                        bucket.put("delta", b.delta());
                    }
                    if (b.deltaPercent() != null) {
                        bucket.put("deltaPercent", b.deltaPercent());
                    }
                    return bucket;
                })
                .toList());
        if (StringUtils.hasText(result.emptyReason())) {
            out.put("emptyReason", result.emptyReason());
        }
        AgentAnalyticalProvenance provenance = result.analyticalProvenance();
        if (provenance != null) {
            Map<String, Object> provOut = new LinkedHashMap<>();
            provOut.put("mode", provenance.mode());
            if (StringUtils.hasText(provenance.recipeId())) {
                provOut.put("recipeId", provenance.recipeId());
            }
            if (StringUtils.hasText(provenance.recipeVersion())) {
                provOut.put("recipeVersion", provenance.recipeVersion());
            }
            if (StringUtils.hasText(provenance.governanceTier())) {
                provOut.put("governanceTier", provenance.governanceTier());
            }
            if (StringUtils.hasText(provenance.provenanceDigest())) {
                provOut.put("provenanceDigest", provenance.provenanceDigest());
            }
            if (!provenance.lockedFilters().isEmpty()) {
                provOut.put("lockedFilters", provenance.lockedFilters());
            }
            if (!provenance.appliedParameters().isEmpty()) {
                provOut.put("appliedParameters", provenance.appliedParameters());
            }
            out.put("analyticalProvenance", provOut);
        }
        return piiVault != null ? piiVault.scrubOutboundForModel(out) : out;
    }
}
