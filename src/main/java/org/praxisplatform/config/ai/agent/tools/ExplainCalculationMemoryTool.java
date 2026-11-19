package org.praxisplatform.config.ai.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory.EvaluatedStep;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory.EvaluatedTrail;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;

/**
 * Ferramenta que avalia deterministicamente as trilhas de {@code @CalculationMemory} para um
 * registro ou filtro e materializa a memoria de calculo auditavel na UI.
 */
public class ExplainCalculationMemoryTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "explain_calculation_memory";

    private final AgentResourceExecutionBridge bridge;

    public ExplainCalculationMemoryTool(AgentResourceExecutionBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge is required");
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
                "description", "Identificador do recurso com @CalculationMemory (entityKey, resourceKey ou resourcePath)."));
        properties.put("entityId", Map.of(
                "type", "string",
                "description", "Opcional: chave primaria ou composta do registro especifico."));
        properties.put("filters", Map.of(
                "type", "object",
                "description", "Opcional: mapa campo->valor do FilterDTO para localizar registros quando entityId nao for conhecido."));
        properties.put("maxRecords", Map.of(
                "type", "integer",
                "description", "Opcional: maximo de registros avaliados quando filtrado (1 a 5, padrao 2)."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Avalia e explica deterministicamente as formulas, parcelas, tetos, descontos e fundamentacao legal (@CalculationMemory) de registros financeiros ou calculos auditaveis.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey"));
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

        String entityKey = args.get("entityKey") == null ? null : args.get("entityKey").toString().trim();
        String entityId = args.get("entityId") == null ? null : args.get("entityId").toString().trim();
        Map<String, Object> filters = args.get("filters") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        int maxRecords = asInt(args.get("maxRecords"), 2);

        List<AgentEvaluatedCalculationMemory> evaluated = bridge.evaluateCalculationMemory(
                context, entityKey, entityId, filters, maxRecords);

        for (AgentEvaluatedCalculationMemory memory : evaluated) {
            artifacts.recordCalculationMemory(memory);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("found", !evaluated.isEmpty());
        output.put("count", evaluated.size());
        output.put("entityKey", entityKey == null ? "" : entityKey);
        output.put("items", evaluated.stream()
                .map(mem -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("entityKey", nonNull(mem.entityKey()));
                    item.put("resourcePath", nonNull(mem.resourcePath()));
                    item.put("entityId", nonNull(mem.entityId()));
                    item.put("recordTitle", nonNull(mem.recordTitle()));
                    item.put("title", nonNull(mem.modelSafeTitle()));
                    item.put("subtitle", nonNull(mem.modelSafeSubtitle()));
                    item.put("legalBasis", nonNull(mem.modelSafeLegalBasis()));
                    item.put("trails", mapModelSafeTrails(mem.modelSafeTrails()));
                    item.put("maskedForAiFields", mem.maskedForAiFields());
                    return item;
                })
                .toList());
        return piiVault != null ? piiVault.scrubOutboundForModel(output) : output;
    }

    static List<Map<String, Object>> mapModelSafeTrails(List<EvaluatedTrail> trails) {
        if (trails == null || trails.isEmpty()) {
            return List.of();
        }
        return trails.stream()
                .map(trail -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", nonNull(trail.id()));
                    row.put("title", nonNull(trail.title()));
                    row.put("ruleCode", nonNull(trail.ruleCode()));
                    row.put("formula", nonNull(trail.formula()));
                    row.put("legalBasis", nonNull(trail.legalBasis()));
                    row.put("legalPurpose", nonNull(trail.legalPurpose()));
                    row.put("resultLabel", nonNull(trail.resultLabel()));
                    row.put("resultFormatted", nonNull(trail.resultFormatted()));
                    row.put("resultTone", nonNull(trail.resultTone()));
                    row.put("resultHint", nonNull(trail.resultHint()));
                    row.put("steps", trail.steps().stream()
                            .map(ExplainCalculationMemoryTool::mapStep)
                            .toList());
                    return row;
                })
                .toList();
    }

    private static Map<String, Object> mapStep(EvaluatedStep step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", nonNull(step.role()));
        m.put("label", nonNull(step.label()));
        m.put("operator", nonNull(step.operator()));
        m.put("formattedValue", nonNull(step.formattedValue()));
        m.put("hint", nonNull(step.hint()));
        m.put("legalBasis", nonNull(step.legalBasis()));
        return m;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
