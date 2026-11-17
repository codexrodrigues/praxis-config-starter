package org.praxisplatform.config.ai.agent.tools;

import java.util.Map;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;

/**
 * Contrato de uma ferramenta local invocavel pelo loop ReAct operacional.
 */
public interface OperationalAgentTool {

    String name();

    AgentToolDeclaration declaration();

    /**
     * Executa a ferramenta retornando o payload seguro ({@code modelSafe}) que sera devolvido ao
     * LLM, registrando a projecao completa ({@code uiRecord}) no {@link AgentTurnArtifactStore}.
     */
    Map<String, Object> execute(
            AgentExecutionContext context,
            TurnScopedPiiVault piiVault,
            AgentTurnArtifactStore artifacts,
            Map<String, Object> rawArguments);
}
