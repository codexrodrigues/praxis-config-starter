package org.praxisplatform.config.ai.agent.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.springframework.core.Ordered;

/**
 * Extensible SPI allowing Praxis modules or host adapters to append deterministic
 * {@code praxis.assistant-content/v2} blocks from captured turn artifacts.
 */
public interface OperationalAssistantBlockContributor extends Ordered {

    List<ObjectNode> contribute(AgentTurnArtifactStore artifacts, ObjectMapper objectMapper);

    @Override
    default int getOrder() {
        return 0;
    }
}
