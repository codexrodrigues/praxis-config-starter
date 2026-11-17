package org.praxisplatform.config.ai.agent.model;

import java.util.List;
import java.util.Map;
import org.praxisplatform.config.ai.agent.spi.AgentMapCopies;

/**
 * Declaracao neutra de uma ferramenta invocavel pelo modelo de linguagem durante o loop ReAct.
 */
public record AgentToolDeclaration(
        String name,
        String description,
        Map<String, Object> parametersJsonSchema,
        List<String> requiredParameters
) {
    public AgentToolDeclaration {
        parametersJsonSchema = AgentMapCopies.immutableOrderedMap(parametersJsonSchema);
        requiredParameters = AgentMapCopies.immutableList(requiredParameters);
    }
}
