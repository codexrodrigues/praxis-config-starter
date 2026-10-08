package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("unit")
class JdbcUiLayoutRevisionAllocationLockTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(ExplicitLifecycleLockConfiguration.class);
  private final DataSource applicationDataSource = mock(DataSource.class);
  private final DataSource configDataSource = mock(DataSource.class);
  private final JdbcTemplate applicationJdbcTemplate = mock(JdbcTemplate.class);
  private final JdbcTemplate configJdbcTemplate = mock(JdbcTemplate.class);

  @AfterEach
  void clearTransactionState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.hasResource(applicationDataSource)) TransactionSynchronizationManager.unbindResource(applicationDataSource);
    if (TransactionSynchronizationManager.hasResource(configDataSource)) TransactionSynchronizationManager.unbindResource(configDataSource);
  }

  @Test
  void explicitSpringWiringUsesOnlyTheConfigTemplateInsideTheConfigTransaction() {
    when(configJdbcTemplate.getDataSource()).thenReturn(configDataSource);
    when(applicationJdbcTemplate.getDataSource()).thenReturn(applicationDataSource);
    when(configJdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(), any())).thenReturn(0);

    contextRunner
        .withBean("applicationJdbcTemplate", JdbcTemplate.class, () -> applicationJdbcTemplate)
        .withBean("configJdbcTemplate", JdbcTemplate.class, () -> configJdbcTemplate)
        .withBean("configNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class,
            () -> new NamedParameterJdbcTemplate(configJdbcTemplate))
        .run(context -> {
          UiLayoutRevisionAllocationLock lock = context.getBean(UiLayoutRevisionAllocationLock.class);
          TransactionSynchronizationManager.initSynchronization();
          TransactionSynchronizationManager.setActualTransactionActive(true);
          TransactionSynchronizationManager.bindResource(configDataSource, new Object());

          lock.lock("tenant-a", "lab", new UiLayoutTarget("praxis-dynamic-page", "procurement-master-detail"));

          verify(configJdbcTemplate).queryForObject(anyString(), any(RowMapper.class), any(), any());
          verify(applicationJdbcTemplate, never()).queryForObject(anyString(), any(RowMapper.class), any(), any());
        });
  }

  @Test
  void rejectsAnApplicationTransactionThatDoesNotBindTheConfigDatasource() {
    when(configJdbcTemplate.getDataSource()).thenReturn(configDataSource);

    contextRunner
        .withBean("applicationJdbcTemplate", JdbcTemplate.class, () -> applicationJdbcTemplate)
        .withBean("configJdbcTemplate", JdbcTemplate.class, () -> configJdbcTemplate)
        .withBean("configNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class,
            () -> new NamedParameterJdbcTemplate(configJdbcTemplate))
        .run(context -> {
          UiLayoutRevisionAllocationLock lock = context.getBean(UiLayoutRevisionAllocationLock.class);
          TransactionSynchronizationManager.initSynchronization();
          TransactionSynchronizationManager.setActualTransactionActive(true);
          TransactionSynchronizationManager.bindResource(applicationDataSource, new Object());

          assertThatThrownBy(() -> lock.lock("tenant-a", "lab", new UiLayoutTarget("praxis-dynamic-page", "procurement-master-detail")))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("Config datasource transaction");
          verify(configJdbcTemplate, never()).queryForObject(anyString(), any(RowMapper.class), any(), any());
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class ExplicitLifecycleLockConfiguration {
    @Bean
    UiLayoutRevisionAllocationLock uiLayoutRevisionAllocationLock(
        @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate configNamedParameterJdbcTemplate) {
      return new JdbcUiLayoutRevisionAllocationLock(configNamedParameterJdbcTemplate);
    }
  }
}
