package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.controller.DomainRuleChangeWorkspaceController;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleWorkspaceReviewRepository;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleDefinitionFingerprint;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleTestRunService;
import org.praxisplatform.config.service.DomainRuleTestEvidencePolicyService;
import org.praxisplatform.config.service.DomainRuleService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Enables governed change workspaces when Config persistence is available. */
@AutoConfiguration(after = DomainRuleSnapshotAutoConfiguration.class)
@ConditionalOnBean({
    DomainRuleChangeWorkspaceRepository.class,
    DomainRuleTestScenarioRepository.class,
    DomainRuleTestRunRepository.class,
    DomainRuleTestRunResultRepository.class,
    DomainRuleWorkspaceReviewRepository.class,
    DomainRuleDefinitionRepository.class,
    DomainRuleService.class,
    DomainRuleGovernancePrincipalResolver.class
})
public class DomainRuleChangeWorkspaceAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  DomainRuleChangeWorkspaceService domainRuleChangeWorkspaceService(
      DomainRuleChangeWorkspaceRepository workspaceRepository,
      DomainRuleTestScenarioRepository scenarioRepository,
      DomainRuleDefinitionRepository definitionRepository,
      DomainRuleDefinitionFingerprint fingerprint,
      ObjectMapper objectMapper,
      DomainRuleTestRunRepository runRepository,
      DomainRuleTestRunResultRepository runResultRepository,
      DomainRuleWorkspaceReviewRepository reviewRepository,
      DomainRuleService domainRuleService,
      DomainRuleTestEvidencePolicyService evidencePolicyService) {
    return new DomainRuleChangeWorkspaceService(
        workspaceRepository, scenarioRepository, definitionRepository, fingerprint, objectMapper,
        runRepository, runResultRepository, reviewRepository, domainRuleService, evidencePolicyService);
  }

  @Bean
  @ConditionalOnMissingBean
  DomainRuleTestEvidencePolicyService domainRuleTestEvidencePolicyService(ObjectMapper objectMapper) {
    return new DomainRuleTestEvidencePolicyService(objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  DomainRuleChangeWorkspaceController domainRuleChangeWorkspaceController(
      DomainRuleChangeWorkspaceService service,
      DomainRuleGovernancePrincipalResolver principalResolver,
      DomainRuleTestRunService testRunService) {
    return new DomainRuleChangeWorkspaceController(service, principalResolver, testRunService);
  }

  @Bean @ConditionalOnMissingBean
  DomainRuleTestRunService domainRuleTestRunService(
      DomainRuleTestRunRepository runRepository, DomainRuleTestRunResultRepository resultRepository,
      DomainRuleChangeWorkspaceRepository workspaceRepository, DomainRuleTestScenarioRepository scenarioRepository,
      ObjectMapper objectMapper) {
    return new DomainRuleTestRunService(runRepository, resultRepository, workspaceRepository, scenarioRepository, objectMapper);
  }
}
