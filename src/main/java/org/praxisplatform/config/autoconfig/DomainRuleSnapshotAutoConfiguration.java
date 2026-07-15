package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.controller.DomainRuleSnapshotController;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Exposes the snapshot control plane when the host has enabled Config Starter persistence. */
@AutoConfiguration
@ConditionalOnBean({
  DomainRuleDefinitionRepository.class,
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
  DomainRuleSnapshotService domainRuleSnapshotService(
      DomainRuleDefinitionRepository definitionRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotEventRepository eventRepository,
      ObjectMapper objectMapper,
      DomainRuleImplementationCatalog implementationCatalog) {
    return new DomainRuleSnapshotService(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        objectMapper,
        implementationCatalog);
  }

  @Bean
  @ConditionalOnMissingBean
  DomainRuleSnapshotController domainRuleSnapshotController(DomainRuleSnapshotService service) {
    return new DomainRuleSnapshotController(service);
  }
}
