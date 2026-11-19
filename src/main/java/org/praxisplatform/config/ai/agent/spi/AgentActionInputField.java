package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Campo de entrada extraido do DTO de comando ou intencao parcial para instrucao do modelo e
 * apresentacao no cartao de proposta governada.
 */
public record AgentActionInputField(
        String name,
        String label,
        String description,
        String controlType,
        String javaType,
        boolean required,
        List<Map<String, String>> options
) {
    public AgentActionInputField {
        options = AgentMapCopies.immutableList(options);
    }
}
