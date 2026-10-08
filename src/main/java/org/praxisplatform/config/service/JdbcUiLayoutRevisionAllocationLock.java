package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PostgreSQL transaction advisory lock for the Config datasource only.
 *
 * <p>This is deliberately not a component. A later host-owned lifecycle configuration must bind
 * it explicitly with the same Config transaction manager that proxies the lifecycle service.</p>
 */
public class JdbcUiLayoutRevisionAllocationLock implements UiLayoutRevisionAllocationLock {
  private final JdbcTemplate configJdbcTemplate;

  public JdbcUiLayoutRevisionAllocationLock(
      @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate configNamedParameterJdbcTemplate) {
    this.configJdbcTemplate = configNamedParameterJdbcTemplate.getJdbcTemplate();
  }

  @Override
  public void lock(String tenantId, String environment, UiLayoutTarget target) {
    if (configJdbcTemplate.getDataSource() == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(configJdbcTemplate.getDataSource())) {
      throw new IllegalStateException("Revision allocation requires the active Config datasource transaction.");
    }
    configJdbcTemplate.queryForObject(
        "select pg_advisory_xact_lock(hashtext(?), hashtext(?))",
        (resultSet, rowNumber) -> 0,
        tenantId,
        environment + "|" + target.componentType() + "|" + target.componentId());
  }
}
