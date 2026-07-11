package org.praxisplatform.config.autoconfig;

import org.praxisplatform.config.service.UiConfigWriteAuthorizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Keeps existing installations compatible while exposing the canonical policy
 * seam for hosts that persist governed UI configuration.
 */
@AutoConfiguration
public class UiConfigWriteAuthorizationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(UiConfigWriteAuthorizer.class)
    UiConfigWriteAuthorizer permitUnclassifiedUiConfigWrites() {
        return request -> {
            // User preferences remain compatible until a host classifies them.
        };
    }
}
