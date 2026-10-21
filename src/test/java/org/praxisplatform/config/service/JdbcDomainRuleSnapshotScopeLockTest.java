package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("unit")
class JdbcDomainRuleSnapshotScopeLockTest {
  private final DataSource source = mock(DataSource.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final JdbcDomainRuleSnapshotScopeLock lock =
      new JdbcDomainRuleSnapshotScopeLock(new NamedParameterJdbcTemplate(jdbc));

  @AfterEach void cleanThread() {
    if (TransactionSynchronizationManager.hasResource(source)) TransactionSynchronizationManager.unbindResource(source);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test void refusesMissingForeignAndReadOnlyTransactions() {
    when(jdbc.getDataSource()).thenReturn(source);
    assertThatThrownBy(() -> lock.lock("tenant", "dev", "rules")).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> lock.lock("tenant", "dev", "rules")).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.bindResource(source, new Object());
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> lock.lock("tenant", "dev", "rules")).isInstanceOf(IllegalStateException.class);
    verify(jdbc, never()).queryForObject(anyString(), any(RowMapper.class), any(), any());
  }

  @Test void rejectsARealBoundConnectionAtRepeatableRead() throws Exception {
    when(jdbc.getDataSource()).thenReturn(source);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var connection = mock(java.sql.Connection.class);
    when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
    TransactionSynchronizationManager.bindResource(source, new org.springframework.jdbc.datasource.ConnectionHolder(connection));
    assertThatThrownBy(() -> lock.lock("tenant", "dev", "rules"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
    verify(jdbc, never()).queryForObject(anyString(), any(RowMapper.class), any(), any());
  }

  @Test void serializesAbsentAndExistingHeadsInTheSameUnambiguousNamespace() throws Exception {
    when(jdbc.getDataSource()).thenReturn(source);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var connection = mock(java.sql.Connection.class);
    when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_READ_COMMITTED);
    var holder = new org.springframework.jdbc.datasource.ConnectionHolder(connection);
    // JpaTransactionManager binds the holder without setting the JDBC synchronized flag.
    assertThat(holder.isSynchronizedWithTransaction()).isFalse();
    TransactionSynchronizationManager.bindResource(source, holder);
    lock.lock("a|b", "c", "rules");
    verify(jdbc).queryForObject(eq("select pg_advisory_xact_lock(hashtext(?), hashtext(?))"),
        any(RowMapper.class), eq("praxis.config.domain-rule-snapshot/1"), eq("3:a|b1:c5:rules"));
    assertThat(JdbcDomainRuleSnapshotScopeLock.scopeIdentity("a|b", "c", "rules"))
        .isNotEqualTo(JdbcDomainRuleSnapshotScopeLock.scopeIdentity("a", "b|c", "rules"));
  }
}
