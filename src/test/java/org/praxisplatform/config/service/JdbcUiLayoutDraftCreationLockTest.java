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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("unit")
class JdbcUiLayoutDraftCreationLockTest {
  private final DataSource configDataSource = mock(DataSource.class);
  private final JdbcTemplate configJdbcTemplate = mock(JdbcTemplate.class);
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-dynamic-page", "orders-page");

  @AfterEach
  void clearTransactionState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.hasResource(configDataSource)) {
      TransactionSynchronizationManager.unbindResource(configDataSource);
    }
  }

  @Test
  void usesOneLengthPrefixedScopedIdentityInsideTheConfigTransaction() {
    when(configJdbcTemplate.getDataSource()).thenReturn(configDataSource);
    when(configJdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(), any())).thenReturn(0);
    UiLayoutDraftCreationLock lock = new JdbcUiLayoutDraftCreationLock(
        new NamedParameterJdbcTemplate(configJdbcTemplate));
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.bindResource(configDataSource, new Object());

    lock.lock("tenant|a", "lab", root, "author", "retry-key");

    verify(configJdbcTemplate).queryForObject(anyString(), any(RowMapper.class),
        org.mockito.ArgumentMatchers.eq("praxis-ui-layout-draft-create"),
        org.mockito.ArgumentMatchers.eq("8:tenant|a3:lab19:praxis-dynamic-page11:orders-page6:author9:retry-key"));
  }

  @Test
  void refusesToRunWithoutTheBoundConfigTransaction() {
    when(configJdbcTemplate.getDataSource()).thenReturn(configDataSource);
    UiLayoutDraftCreationLock lock = new JdbcUiLayoutDraftCreationLock(
        new NamedParameterJdbcTemplate(configJdbcTemplate));

    assertThatThrownBy(() -> lock.lock("tenant-a", "lab", root, "author", "retry-key"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Config datasource transaction");
    verify(configJdbcTemplate, never()).queryForObject(anyString(), any(RowMapper.class), any(), any());
  }
}
