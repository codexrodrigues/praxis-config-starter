package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Metadados editoriais extraidos de {@code @UISchema} para curadoria deterministica de cartoes
 * 360 e tabelas compactas no assistente operacional.
 */
public record AgentFieldDescriptor(
        String name,
        String label,
        int order,
        boolean quickViewVisible,
        int quickViewOrder,
        boolean hidden,
        boolean tableHidden,
        String labelTrue,
        String labelFalse,
        Map<String, String> optionLabels
) {
    public AgentFieldDescriptor {
        optionLabels = AgentMapCopies.immutableOrderedMap(optionLabels);
    }
}
