package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.service.DomainRuleDefinitionFingerprint;
import org.praxisplatform.config.service.DomainRuleAssistantSearchService;
import org.praxisplatform.config.service.DomainRuleExplanationProjectionService;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Exposes the read-only decision explanation projection after the rule control plane is available. */
@AutoConfiguration(
        after = DomainRuleSnapshotAutoConfiguration.class,
        before = AgenticAuthoringAutoConfiguration.class)
@ConditionalOnBean({DomainRuleService.class, DomainRuleDefinitionFingerprint.class})
public class DomainRuleExplanationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DomainRuleAssistantSearchService domainRuleAssistantSearchService(
            DomainRuleDefinitionRepository definitionRepository) {
        return new DomainRuleAssistantSearchService(definitionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    DomainRuleExplanationProjectionService domainRuleExplanationProjectionService(
            DomainRuleService domainRuleService,
            DomainRuleDefinitionFingerprint definitionFingerprint,
            ObjectMapper objectMapper) {
        return new DomainRuleExplanationProjectionService(
                domainRuleService, definitionFingerprint, objectMapper);
    }
}
