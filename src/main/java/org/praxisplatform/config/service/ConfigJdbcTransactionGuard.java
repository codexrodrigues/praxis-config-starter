package org.praxisplatform.config.service;

import java.sql.Connection;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal identity check shared by Config JDBC ledgers participating in lifecycle JPA. */
final class ConfigJdbcTransactionGuard {
  private final DataSource dataSource;
  private final EntityManagerFactory entityManagerFactory;

  ConfigJdbcTransactionGuard(DataSource dataSource, TransactionOperations transactions) {
    this.dataSource = dataSource;
    this.entityManagerFactory = transactions instanceof TransactionTemplate template
        && template.getTransactionManager() instanceof JpaTransactionManager manager
        && manager.getDataSource() == dataSource ? manager.getEntityManagerFactory() : null;
  }

  ConnectionHolder require() {
    try {
      if (dataSource == null || !TransactionSynchronizationManager.isActualTransactionActive()
          || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)
          || holder.isRollbackOnly()
          || (!holder.isSynchronizedWithTransaction() && !isJpaConnection(holder.getConnection()))
          || holder.getConnection().getAutoCommit()
          || holder.getConnection().getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
        throw unavailable();
      }
      return holder;
    } catch (Exception exception) { throw unavailable(); }
  }

  private boolean isJpaConnection(Connection connection) {
    if (entityManagerFactory == null) return false;
    Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
    if (!(resource instanceof EntityManagerHolder holder) || !holder.isSynchronizedWithTransaction()
        || !holder.getEntityManager().getTransaction().isActive()
        || holder.getEntityManager().getTransaction().getRollbackOnly()) return false;
    return holder.getEntityManager().unwrap(Session.class).doReturningWork(actual -> actual == connection);
  }

  private UiLayoutLifecycleException unavailable() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "The active Config transaction is unavailable.");
  }
}
