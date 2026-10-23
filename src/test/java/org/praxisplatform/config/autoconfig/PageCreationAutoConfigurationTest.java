package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.PageCreationController;
import org.praxisplatform.config.controller.UiLayoutRevisionRequestBodyAdvice;
import org.praxisplatform.config.repository.*;
import org.praxisplatform.config.service.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

@Tag("unit")
class PageCreationAutoConfigurationTest {
  @Test
  void completeHostCreatesOneGraphWithOriginalDelegatesAndBodyGuard() {
    runner(null, true)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(PageRuntimeReadService.class)
                  .hasSingleBean(org.praxisplatform.config.controller.PageRuntimeController.class)
                  .hasSingleBean(PageCreationService.class)
                  .hasSingleBean(PageCreationController.class)
                  .hasSingleBean(UiLayoutLifecycleCommandService.class)
                  .hasSingleBean(UiLayoutLifecycleReadService.class)
                  .hasSingleBean(UiLayoutRevisionRequestBodyAdvice.class)
                  .hasSingleBean(UiLayoutLifecycleInvocationProvider.class)
                  .hasSingleBean(UiLayoutBaselineMetadataAdmission.class);
              var pageService = context.getBean(PageCreationService.class);
              assertThat(
                      pageService.invocationProvider(
                          context.getBean(UiLayoutLifecycleInvocationProvider.class)))
                  .isNotSameAs(context.getBean(UiLayoutLifecycleInvocationProvider.class));
            });
  }

  @Test
  void missingPageHostKeepsLifecycleButDoesNotExposePageCreation() {
    runner(null, false)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(PageRuntimeReadService.class)
                    .doesNotHaveBean(org.praxisplatform.config.controller.PageRuntimeController.class)
                    .doesNotHaveBean(PageCreationService.class)
                    .doesNotHaveBean(PageCreationController.class)
                    .hasSingleBean(UiLayoutLifecycleCommandService.class));
  }

  @Test
  void incompleteLifecycleNeverExposesAServiceWithUnresolvableSuppliers() {
    for (Class<?> missing :
        List.of(
            UiLayoutReleaseReviewRepository.class,
            UiLayoutReleaseApprovalRepository.class,
            UiLayoutReleaseHeadRepository.class,
            UiLayoutReleaseEventRepository.class)) {
      runner(missing, true)
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .doesNotHaveBean(PageRuntimeReadService.class)
                    .doesNotHaveBean(org.praxisplatform.config.controller.PageRuntimeController.class)
                    .doesNotHaveBean(PageCreationService.class)
                      .doesNotHaveBean(PageCreationController.class)
                      .doesNotHaveBean(UiLayoutLifecycleCommandService.class)
                      .doesNotHaveBean(UiLayoutRevisionRequestBodyAdvice.class));
    }
  }

  private ApplicationContextRunner runner(Class<?> missing, boolean pageHost) {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UiLayoutResolutionAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(
                UiLayoutValidationBudgetPolicy.class,
                () -> (op, invocation) -> Duration.ofSeconds(30))
            .withBean(
                "configNamedParameterJdbcTemplate",
                NamedParameterJdbcTemplate.class,
                () -> new NamedParameterJdbcTemplate(mock(javax.sql.DataSource.class)))
            .withBean(
                "uiLayoutLifecycleConfigTransactionOperations",
                TransactionOperations.class,
                () -> mock(TransactionOperations.class));
    for (Class<?> type :
        List.of(
            UiLayoutLifecycleInvocationProvider.class,
            UiLayoutLifecycleAdmission.class,
            UiLayoutLifecycleStructureValidator.class,
            UiLayoutDraftWorkspaceSource.class,
            UiLayoutBaselineMetadataAdmission.class,
            UiLayoutReleaseValidator.class,
            UiLayoutDraftCreationLock.class,
            UiLayoutRevisionAllocationLock.class,
            UiLayoutDefinitionRepository.class,
            UiLayoutRevisionRepository.class,
            UiLayoutAssignmentRevisionRepository.class,
            UiLayoutDraftRepository.class,
            UiLayoutReleaseRepository.class,
            UiLayoutReleaseMemberRepository.class,
            UiLayoutReleaseReviewRepository.class,
            UiLayoutReleaseApprovalRepository.class,
            UiLayoutReleaseHeadRepository.class,
            UiLayoutReleaseEventRepository.class))
      if (type != missing) runner = mockBean(runner, type);
    return pageHost
        ? runner.withBean(PageCreationHost.class, () -> mock(PageCreationHost.class))
        : runner;
  }

  private <T> ApplicationContextRunner mockBean(ApplicationContextRunner runner, Class<T> type) {
    return runner.withBean(type, () -> mock(type));
  }
}
