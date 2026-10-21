package org.praxisplatform.config.service;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL transaction lock on the Config datasource, including first publication/activation. */
public final class JdbcDomainRuleSnapshotScopeLock implements DomainRuleSnapshotScopeLock {
  private static final String NAMESPACE = "praxis.config.domain-rule-snapshot/1";
  private final JdbcTemplate jdbc;

  public JdbcDomainRuleSnapshotScopeLock(
      @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate configJdbc) {
    this.jdbc = Objects.requireNonNull(configJdbc, "configJdbc is required").getJdbcTemplate();
  }

  @Override
  public void lock(String tenantId, String environment, String ruleSetKey) {
    if (jdbc.getDataSource() == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
      throw new IllegalStateException("RuleSet lifecycle requires the active writable Config datasource transaction");
    }
    try {
      var resource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
      if (!(resource instanceof org.springframework.jdbc.datasource.ConnectionHolder holder)
          || holder.getConnection().getTransactionIsolation() != java.sql.Connection.TRANSACTION_READ_COMMITTED)
        throw new IllegalStateException("RuleSet lifecycle requires the Config READ COMMITTED transaction");
    } catch (java.sql.SQLException unavailable) {
      throw new IllegalStateException("RuleSet transaction isolation could not be verified", unavailable);
    }
    jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?), hashtext(?))",
        (resultSet, rowNumber) -> 0, NAMESPACE, scopeIdentity(tenantId, environment, ruleSetKey));
  }

  static String scopeIdentity(String tenantId, String environment, String ruleSetKey) {
    return part(tenantId) + part(environment) + part(ruleSetKey);
  }

  private static String part(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("RuleSet lock scope is required");
    return value.length() + ":" + value;
  }
}
