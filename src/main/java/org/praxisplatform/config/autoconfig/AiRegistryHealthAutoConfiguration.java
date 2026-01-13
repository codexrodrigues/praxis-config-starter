package org.praxisplatform.config.autoconfig;

import org.praxisplatform.config.registry.AiRegistryHealthIndicator;
import org.praxisplatform.config.registry.AiRegistryStatusService;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
@ConditionalOnProperty(
        prefix = "praxis.ai.registry.health",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AiRegistryHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AiRegistryHealthIndicator.class)
    public HealthIndicator aiRegistryHealthIndicator(AiRegistryStatusService statusService) {
        return new AiRegistryHealthIndicator(statusService);
    }
}
