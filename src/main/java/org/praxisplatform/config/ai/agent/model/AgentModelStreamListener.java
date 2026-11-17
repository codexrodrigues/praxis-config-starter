package org.praxisplatform.config.ai.agent.model;

/**
 * Callback opcional para emissao incremental de progresso textual ou pensamento do provedor.
 */
public interface AgentModelStreamListener {

    AgentModelStreamListener NOOP = new AgentModelStreamListener() {
        @Override
        public void onTextDelta(String delta) {
        }

        @Override
        public void onThoughtDelta(String delta) {
        }
    };

    void onTextDelta(String delta);

    default void onThoughtDelta(String delta) {
    }
}
