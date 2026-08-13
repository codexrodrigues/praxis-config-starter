package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadReader;
import org.praxisplatform.config.controller.DomainRuleExecutionObservationController;
import org.praxisplatform.config.controller.DomainRuleHostStatusController;
import org.praxisplatform.config.controller.DomainRuleSnapshotController;
import org.praxisplatform.config.controller.DomainRuleRolloutPolicyController;
import org.praxisplatform.config.repository.DomainRuleCompositionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleExecutionObservationRepository;
import org.praxisplatform.config.repository.DomainRuleHostStatusRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyHeadRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRolloutRepository;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.DomainRuleExecutionObservationService;
import org.praxisplatform.config.service.DomainRuleHostStatusService;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleImplementationScope;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
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
      .withBean(DomainRuleSnapshotEventRepository.class, () -> mock(DomainRuleSnapshotEventRepository.class))
      .withBean(DomainRuleExecutionObservationRepository.class,
          () -> mock(DomainRuleExecutionObservationRepository.class))
      .withBean(DomainRuleHostStatusRepository.class,
          () -> mock(DomainRuleHostStatusRepository.class));

  @Test
  void exposesPublicReaderAndHttpControllerWhenPersistenceBoundaryExists() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(PublishedRuleSnapshotHeadReader.class);
      assertThat(context).hasSingleBean(DomainRuleSnapshotController.class);
      assertThat(context).hasSingleBean(DomainRuleExecutionObservationService.class);
      assertThat(context).hasSingleBean(DomainRuleExecutionObservationController.class);
      assertThat(context).hasSingleBean(DomainRuleHostStatusService.class);
      assertThat(context).hasSingleBean(DomainRuleHostStatusController.class);
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

  @Test
  void exposesGovernedRolloutPolicyLifecycleWhenItsPersistenceBoundaryExists() {
    contextRunner
        .withBean(DomainRuleRolloutPolicyRepository.class,
            () -> mock(DomainRuleRolloutPolicyRepository.class))
        .withBean(DomainRuleRolloutPolicyHeadRepository.class,
            () -> mock(DomainRuleRolloutPolicyHeadRepository.class))
        .withBean(DomainRuleRolloutPolicyEventRepository.class,
            () -> mock(DomainRuleRolloutPolicyEventRepository.class))
        .withBean(DomainRuleSnapshotRolloutRepository.class,
            () -> mock(DomainRuleSnapshotRolloutRepository.class))
        .run(context -> {
          assertThat(context).hasSingleBean(DomainRuleRolloutPolicyService.class);
          assertThat(context).hasSingleBean(DomainRuleRolloutPolicyController.class);
        });
  }
}
