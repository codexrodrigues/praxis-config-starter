package org.praxisplatform.config.ai.authoring;

import org.praxisplatform.config.registry.AiRegistryComponentDefinitionsChangedEvent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class AgenticAuthoringComponentCapabilitiesRefreshListener {

    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;

    public AgenticAuthoringComponentCapabilitiesRefreshListener(
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService) {
        this.componentCapabilitiesService = componentCapabilitiesService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onComponentDefinitionsChanged(AiRegistryComponentDefinitionsChangedEvent event) {
        componentCapabilitiesService.invalidateCapabilitiesCache();
    }
}
