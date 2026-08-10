package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadReader;
import org.praxisplatform.config.controller.DomainRuleSnapshotController;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleCompositionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
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
      DomainRuleImplementationCatalog implementationCatalog) {
    return new DomainRuleSnapshotService(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        compositionApprovalRepository,
        definitionApprovalRepository,
        definitionFingerprint,
        objectMapper,
        implementationCatalog);
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
}
