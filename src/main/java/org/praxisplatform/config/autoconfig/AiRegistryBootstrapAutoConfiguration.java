package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.registry.AiRegistryBootstrapProperties;
import org.praxisplatform.config.registry.AiRegistryBootstrapService;
import org.praxisplatform.config.registry.AiRegistryBootstrapState;
import org.praxisplatform.config.registry.AiRegistryHealthProperties;
import org.praxisplatform.config.registry.AiRegistryStatusService;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

@AutoConfiguration
@ConditionalOnClass(RegistryIngestionService.class)
@EnableConfigurationProperties({
        AiRegistryBootstrapProperties.class,
        AiRegistryHealthProperties.class
})
public class AiRegistryBootstrapAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AiRegistryBootstrapState aiRegistryBootstrapState() {
        return new AiRegistryBootstrapState();
    }

    @Bean
    @ConditionalOnMissingBean
    public AiRegistryStatusService aiRegistryStatusService(
            AiRegistryRepository repository,
            AiRegistryBootstrapProperties bootstrapProperties,
            AiRegistryHealthProperties healthProperties,
            AiRegistryBootstrapState bootstrapState) {
        return new AiRegistryStatusService(repository, bootstrapProperties, healthProperties, bootstrapState);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiRegistryBootstrapService aiRegistryBootstrapService(
            RegistryIngestionService ingestionService,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            AiRegistryBootstrapProperties bootstrapProperties,
            AiRegistryStatusService statusService,
            AiRegistryBootstrapState bootstrapState,
            AiRegistryRepository repository,
            RagVectorStoreService ragVectorStoreService) {
        return new AiRegistryBootstrapService(
                ingestionService,
                objectMapper,
                resourceLoader,
                bootstrapProperties,
                statusService,
                bootstrapState,
                repository,
                ragVectorStoreService);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "praxis.ai.registry.bootstrap",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public ApplicationRunner aiRegistryBootstrapRunner(AiRegistryBootstrapService bootstrapService) {
        return args -> bootstrapService.bootstrapIfNeeded();
    }

}
