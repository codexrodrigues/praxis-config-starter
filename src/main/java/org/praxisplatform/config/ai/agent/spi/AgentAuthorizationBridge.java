package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;
import java.util.Set;

/**
 * SPI de autorizacao e dupla projecao de dados para o agente operacional.
 *
 * <p>Permite que hosts com {@code praxis-authz-starter} apliquem decisoes OPA/PEPs enquanto hosts
 * sem OPA aplicam a governanca declarada via {@code @DomainGovernance}, {@code @AiUsagePolicy} e
 * {@code @UISchema}.
 */
public interface AgentAuthorizationBridge {

    boolean canReadResource(AgentExecutionContext context, String resourcePath);

    boolean canInvokeAction(
            AgentExecutionContext context,
            String resourcePath,
            String actionId,
            Object entityId);

    default boolean canModifyFields(
            AgentExecutionContext context,
            String resourcePath,
            Class<?> dtoType,
            Set<String> fieldNames) {
        return true;
    }

    DualProjectedPayload projectRecord(
            AgentExecutionContext context,
            String resourcePath,
            Class<?> dtoType,
            Map<String, Object> rawRecord);

    /**
     * Resolves canonical wire field names denied for analytical {@code FIELD_USE} categories
     * ({@code VALUE}, {@code GROUP}, {@code AGGREGATE}, {@code FILTER}) in fail-closed mode.
     *
     * <p>Hosts with {@code praxis-authz-starter} override this to consult static lineage and live OPA
     * {@code TARGET} decisions without relying on dummy row projection alone.</p>
     */
    default Set<String> resolveDeniedFieldUseAttributes(
            AgentExecutionContext context,
            String resourcePath,
            Class<?> dtoType,
            Set<String> candidateFields) {
        if (candidateFields == null || candidateFields.isEmpty()) {
            return Set.of();
        }
        Map<String, Object> probeMap = new java.util.LinkedHashMap<>();
        for (String field : candidateFields) {
            if (field != null && !field.isBlank()) {
                probeMap.putIfAbsent(field.trim(), Boolean.TRUE);
            }
        }
        if (probeMap.isEmpty()) {
            return Set.of();
        }
        DualProjectedPayload projected = projectRecord(context, resourcePath, dtoType, probeMap);
        if (projected == null) {
            return Set.of();
        }
        Set<String> denied = new java.util.LinkedHashSet<>();
        if (projected.hiddenFields() != null) {
            for (String f : projected.hiddenFields()) {
                if (f != null && !f.isBlank()) {
                    denied.add(f.trim());
                }
            }
        }
        if (projected.maskedForAiFields() != null) {
            for (String f : projected.maskedForAiFields()) {
                if (f != null && !f.isBlank()) {
                    denied.add(f.trim());
                }
            }
        }
        return denied.isEmpty() ? Set.of() : Set.copyOf(denied);
    }
}
