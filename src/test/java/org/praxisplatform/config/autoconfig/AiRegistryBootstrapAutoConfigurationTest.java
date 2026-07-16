package org.praxisplatform.config.autoconfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesService;
import org.praxisplatform.config.registry.AiRegistryBootstrapService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

@Tag("unit")
class AiRegistryBootstrapAutoConfigurationTest {

    @Test
    @SuppressWarnings("unchecked")
    void invalidatesCapabilitiesLoadedBeforeRegistryBootstrapCompleted() throws Exception {
        AiRegistryBootstrapService bootstrapService = mock(AiRegistryBootstrapService.class);
        AgenticAuthoringComponentCapabilitiesService capabilitiesService =
                mock(AgenticAuthoringComponentCapabilitiesService.class);
        ObjectProvider<AgenticAuthoringComponentCapabilitiesService> provider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<AgenticAuthoringComponentCapabilitiesService> consumer = invocation.getArgument(0);
            consumer.accept(capabilitiesService);
            return null;
        }).when(provider).ifAvailable(any());
        ApplicationRunner runner = new AiRegistryBootstrapAutoConfiguration()
                .aiRegistryBootstrapRunner(bootstrapService, provider);

        runner.run(mock(ApplicationArguments.class));

        verify(bootstrapService).bootstrapIfNeeded();
        verify(capabilitiesService).invalidateCapabilitiesCache();
    }
}
