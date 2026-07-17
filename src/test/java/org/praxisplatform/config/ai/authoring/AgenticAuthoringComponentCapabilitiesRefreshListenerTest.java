package org.praxisplatform.config.ai.authoring;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.registry.AiRegistryComponentDefinitionsChangedEvent;

@Tag("unit")
class AgenticAuthoringComponentCapabilitiesRefreshListenerTest {

    @Test
    void invalidatesCatalogAfterCommittedComponentDefinitionRevision() {
        AgenticAuthoringComponentCapabilitiesService service =
                mock(AgenticAuthoringComponentCapabilitiesService.class);
        AgenticAuthoringComponentCapabilitiesRefreshListener listener =
                new AgenticAuthoringComponentCapabilitiesRefreshListener(service);

        listener.onComponentDefinitionsChanged(new AiRegistryComponentDefinitionsChangedEvent(
                "registry-v2",
                105,
                Instant.parse("2026-07-16T12:00:00Z")));

        verify(service).invalidateCapabilitiesCache();
    }
}
