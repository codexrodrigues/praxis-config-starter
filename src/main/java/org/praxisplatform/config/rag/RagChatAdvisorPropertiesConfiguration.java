package org.praxisplatform.config.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RagChatAdvisorProperties.class)
public class RagChatAdvisorPropertiesConfiguration {
}
