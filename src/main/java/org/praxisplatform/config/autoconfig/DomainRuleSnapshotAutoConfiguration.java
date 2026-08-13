package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadReader;
import org.praxisplatform.config.controller.DomainRuleSnapshotController;
import org.praxisplatform.config.controller.DomainRuleExecutionObservationController;
import org.praxisplatform.config.controller.DomainRuleHostStatusController;
import org.praxisplatform.config.controller.DomainRuleRolloutController;
import org.praxisplatform.config.controller.DomainRuleRolloutPolicyController;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleCompositionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.repository.DomainRuleExecutionObservationRepository;
import org.praxisplatform.config.repository.DomainRuleHostStatusRepository;
import org.praxisplatform.config.repository.DomainRuleCandidateProbeRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyHeadRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRolloutEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRolloutRepository;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.service.DomainRuleSnapshotActivationGate;
import org.praxisplatform.config.service.DomainRuleExecutionObservationService;
import org.praxisplatform.config.service.DomainRuleHostStatusService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleSnapshotHeadReaderAdapter;
import org.praxisplatform.config.service.DomainRuleDefinitionFingerprint;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Clock;
import java.time.Duration;

/** Exposes the snapshot control plane when the host has enabled Config Starter persistence. */
@AutoConfiguration
@ConditionalOnBean({
  DomainRuleDefinitionRepository.class,
  DomainRuleDefinitionApprovalRepository.class,
  DomainRuleCompositionApprovalRepository.class,
  DomainRuleSnapshotRepository.class,
  DomainRuleSnapshotHeadRepository.class,
  DomainRuleSnapshotEventRepository.class
})
public class DomainRuleSnapshotAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  DomainRuleImplementationCatalog domainRuleImplementationCatalog() {
    return DomainRuleImplementationCatalog.denyAll();
  }

  @Bean
  @ConditionalOnMissingBean
  DomainRuleDefinitionFingerprint domainRuleDefinitionFingerprint(ObjectMapper objectMapper) {
    return new DomainRuleDefinitionFingerprint(objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  DomainRuleSnapshotService domainRuleSnapshotService(
      DomainRuleDefinitionRepository definitionRepository,
      DomainRuleDefinitionApprovalRepository definitionApprovalRepository,
      DomainRuleCompositionApprovalRepository compositionApprovalRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotEventRepository eventRepository,
      ObjectMapper objectMapper,
      DomainRuleDefinitionFingerprint definitionFingerprint,
      DomainRuleImplementationCatalog implementationCatalog,
      ObjectProvider<DomainRuleSnapshotActivationGate> activationGate) {
    return new DomainRuleSnapshotService(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        compositionApprovalRepository,
        definitionApprovalRepository,
        definitionFingerprint,
        objectMapper,
        implementationCatalog,
        activationGate.getIfAvailable(DomainRuleSnapshotActivationGate::allowAll));
  }

  @Bean
  @ConditionalOnMissingBean(PublishedRuleSnapshotHeadReader.class)
  PublishedRuleSnapshotHeadReader publishedRuleSnapshotHeadReader(
      DomainRuleSnapshotService snapshotService) {
    return new DomainRuleSnapshotHeadReaderAdapter(snapshotService);
  }

  @Bean
  @ConditionalOnMissingBean
  DomainRuleGovernancePrincipalResolver domainRuleGovernancePrincipalResolver(
      AiPrincipalContextResolver principalContextResolver,
      @Value("${praxis.ai.security.corporate-mode:true}") boolean corporateMode) {
    return new DomainRuleGovernancePrincipalResolver(principalContextResolver, corporateMode);
  }

  @Bean
  @ConditionalOnMissingBean
  DomainRuleSnapshotController domainRuleSnapshotController(
      DomainRuleSnapshotService service,
      DomainRuleGovernancePrincipalResolver principalResolver) {
    return new DomainRuleSnapshotController(service, principalResolver);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DomainRuleExecutionObservationRepository.class)
  DomainRuleExecutionObservationService domainRuleExecutionObservationService(
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotEventRepository snapshotEventRepository,
      DomainRuleExecutionObservationRepository observationRepository) {
    return new DomainRuleExecutionObservationService(
        snapshotRepository, snapshotEventRepository, observationRepository, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DomainRuleExecutionObservationRepository.class)
  DomainRuleExecutionObservationController domainRuleExecutionObservationController(
      DomainRuleExecutionObservationService service,
      DomainRuleGovernancePrincipalResolver principalResolver) {
    return new DomainRuleExecutionObservationController(service, principalResolver);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DomainRuleHostStatusRepository.class)
  DomainRuleHostStatusService domainRuleHostStatusService(
      DomainRuleHostStatusRepository statusRepository,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotService snapshotService,
      ObjectMapper objectMapper,
      @Value("${praxis.config.domain-rules.host-status.stale-after:PT2M}") String staleAfter) {
    return new DomainRuleHostStatusService(
        statusRepository, headRepository, snapshotRepository, snapshotService, objectMapper,
        Clock.systemUTC(), Duration.parse(staleAfter));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DomainRuleHostStatusRepository.class)
  DomainRuleHostStatusController domainRuleHostStatusController(
      DomainRuleHostStatusService service,
      DomainRuleGovernancePrincipalResolver principalResolver) {
    return new DomainRuleHostStatusController(service, principalResolver);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({DomainRuleRolloutPolicyRepository.class,
      DomainRuleRolloutPolicyHeadRepository.class, DomainRuleRolloutPolicyEventRepository.class,
      DomainRuleSnapshotRolloutRepository.class})
  DomainRuleRolloutPolicyService domainRuleRolloutPolicyService(
      DomainRuleRolloutPolicyRepository policies,
      DomainRuleRolloutPolicyHeadRepository policyHeads,
      DomainRuleRolloutPolicyEventRepository policyEvents,
      DomainRuleSnapshotHeadRepository snapshotHeads,
      DomainRuleSnapshotRolloutRepository rollouts) {
    return new DomainRuleRolloutPolicyService(
        policies, policyHeads, policyEvents, snapshotHeads, rollouts, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DomainRuleRolloutPolicyService.class)
  DomainRuleRolloutPolicyController domainRuleRolloutPolicyController(
      DomainRuleRolloutPolicyService service,
      DomainRuleGovernancePrincipalResolver principalResolver) {
    return new DomainRuleRolloutPolicyController(service, principalResolver);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({DomainRuleRolloutPolicyRepository.class, DomainRuleSnapshotRolloutRepository.class,
      DomainRuleCandidateProbeRepository.class, DomainRuleSnapshotRolloutEventRepository.class,
      DomainRuleRolloutPolicyService.class})
  DomainRuleRolloutService domainRuleRolloutService(DomainRuleRolloutPolicyRepository policies,
      DomainRuleSnapshotRolloutRepository rollouts, DomainRuleCandidateProbeRepository probes,
      DomainRuleSnapshotRolloutEventRepository events, DomainRuleSnapshotRepository snapshots,
      DomainRuleSnapshotHeadRepository heads, DomainRuleRolloutPolicyService policyService,
      ObjectMapper objectMapper) {
    return new DomainRuleRolloutService(policies, rollouts, probes, events, snapshots, heads,
        policyService, objectMapper, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DomainRuleRolloutService.class)
  DomainRuleRolloutController domainRuleRolloutController(DomainRuleRolloutService service,
      DomainRuleGovernancePrincipalResolver principalResolver) {
    return new DomainRuleRolloutController(service, principalResolver);
  }
}
