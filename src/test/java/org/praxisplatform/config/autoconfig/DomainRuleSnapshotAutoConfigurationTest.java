package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.DomainRuleSnapshotController;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.service.DomainRuleSnapshotReader;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class DomainRuleSnapshotAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DomainRuleSnapshotAutoConfiguration.class))
      .withBean(ObjectMapper.class, ObjectMapper::new)
      .withBean(DomainRuleDefinitionRepository.class, () -> mock(DomainRuleDefinitionRepository.class))
      .withBean(DomainRuleSnapshotRepository.class, () -> mock(DomainRuleSnapshotRepository.class))
      .withBean(DomainRuleSnapshotHeadRepository.class, () -> mock(DomainRuleSnapshotHeadRepository.class))
      .withBean(DomainRuleSnapshotEventRepository.class, () -> mock(DomainRuleSnapshotEventRepository.class));

  @Test
  void exposesPublicReaderAndHttpControllerWhenPersistenceBoundaryExists() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(DomainRuleSnapshotReader.class);
      assertThat(context).hasSingleBean(DomainRuleSnapshotController.class);
    });
  }
}
