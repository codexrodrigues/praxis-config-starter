package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL transaction advisory lock bound to the Config datasource. */
public class JdbcUiLayoutDraftCreationLock implements UiLayoutDraftCreationLock {
  private final JdbcTemplate configJdbcTemplate;

  public JdbcUiLayoutDraftCreationLock(
      @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate configNamedParameterJdbcTemplate) {
    this.configJdbcTemplate = configNamedParameterJdbcTemplate.getJdbcTemplate();
  }

  @Override
  public void lock(String tenantId, String environment, UiLayoutTarget rootTarget, String actorRef, String idempotencyKey) {
    if (configJdbcTemplate.getDataSource() == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(configJdbcTemplate.getDataSource())) {
      throw new IllegalStateException("Draft creation requires the active Config datasource transaction.");
    }
    String identity = lengthPrefixed(tenantId) + lengthPrefixed(environment)
        + lengthPrefixed(rootTarget.componentType()) + lengthPrefixed(rootTarget.componentId())
        + lengthPrefixed(actorRef) + lengthPrefixed(idempotencyKey);
    configJdbcTemplate.queryForObject(
        "select pg_advisory_xact_lock(hashtext(?), hashtext(?))",
        (resultSet, rowNumber) -> 0,
        "praxis-ui-layout-draft-create",
        identity);
  }

  private String lengthPrefixed(String value) {
    return value.length() + ":" + value;
  }
}
