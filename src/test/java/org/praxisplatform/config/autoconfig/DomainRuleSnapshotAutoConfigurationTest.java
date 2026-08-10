package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadReader;
import org.praxisplatform.config.controller.DomainRuleSnapshotController;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleCompositionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleImplementationScope;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Service;

@Tag("unit")
class DomainRuleSnapshotAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DomainRuleSnapshotAutoConfiguration.class))
      .withBean(ObjectMapper.class, ObjectMapper::new)
      .withBean(DomainRuleDefinitionRepository.class, () -> mock(DomainRuleDefinitionRepository.class))
      .withBean(DomainRuleDefinitionApprovalRepository.class,
          () -> mock(DomainRuleDefinitionApprovalRepository.class))
      .withBean(DomainRuleCompositionApprovalRepository.class,
          () -> mock(DomainRuleCompositionApprovalRepository.class))
      .withBean(AiPrincipalContextResolver.class, () -> mock(AiPrincipalContextResolver.class))
      .withBean(DomainRuleSnapshotRepository.class, () -> mock(DomainRuleSnapshotRepository.class))
      .withBean(DomainRuleSnapshotHeadRepository.class, () -> mock(DomainRuleSnapshotHeadRepository.class))
      .withBean(DomainRuleSnapshotEventRepository.class, () -> mock(DomainRuleSnapshotEventRepository.class));

  @Test
  void exposesPublicReaderAndHttpControllerWhenPersistenceBoundaryExists() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(PublishedRuleSnapshotHeadReader.class);
      assertThat(context).hasSingleBean(DomainRuleSnapshotController.class);
      assertThat(context).hasSingleBean(DomainRuleImplementationCatalog.class);
      assertThat(context.getBean(DomainRuleImplementationCatalog.class)
          .allowedImplementations(new DomainRuleImplementationScope(
              "tenant-a", "prod", "quickstart"))).isEmpty();
    });
  }

  @Test
  void snapshotServiceIsOwnedExclusivelyByAutoConfiguration() {
    assertThat(DomainRuleSnapshotService.class.isAnnotationPresent(Service.class)).isFalse();
  }

  @Test
  void preservesHostOwnedImplementationCatalog() {
    DomainRuleImplementationCatalog hostCatalog = scope -> java.util.List.of();

    contextRunner
        .withBean(DomainRuleImplementationCatalog.class, () -> hostCatalog)
        .run(context -> assertThat(context.getBean(DomainRuleImplementationCatalog.class))
            .isSameAs(hostCatalog));
  }

  @Test
  void preservesHostOwnedPublishedHeadReader() {
    PublishedRuleSnapshotHeadReader hostReader = scope -> java.util.Optional.empty();

    contextRunner
        .withBean(PublishedRuleSnapshotHeadReader.class, () -> hostReader)
        .run(context -> assertThat(context.getBean(PublishedRuleSnapshotHeadReader.class))
            .isSameAs(hostReader));
  }
}
