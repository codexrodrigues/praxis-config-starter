package org.praxisplatform.config.service;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import javax.sql.DataSource;
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
    this.entityManagerFactory =
        transactions instanceof TransactionTemplate template
                && template.getTransactionManager() instanceof JpaTransactionManager manager
                && manager.getDataSource() == dataSource
            ? manager.getEntityManagerFactory()
            : null;
  }

  ConnectionHolder require() {
    return requireConnection(true);
  }

  /** A PostgreSQL SELECT pins transaction isolation; SHOW alone does not. */
  ConnectionHolder admitImmutableReadTransaction() {
    ConnectionHolder holder = requireConnection(false);
    Connection connection = holder.getConnection();
    requireImmutableReadTransaction(holder, connection);
    try (var statement =
            connection.prepareStatement("SELECT current_setting('transaction_isolation')");
        var result = statement.executeQuery()) {
      if (!result.next() || !"read committed".equals(result.getString(1)) || result.next()) {
        throw unavailable();
      }
    } catch (Exception exception) {
      throw unavailable();
    }
    return requireImmutableReadTransaction(holder, connection);
  }

  /** Only the admitted read set may reuse isolation proof, in its exact active transaction. */
  ConnectionHolder requireImmutableReadTransaction(
      ConnectionHolder expectedHolder, Connection expectedConnection) {
    try {
      ConnectionHolder currentHolder = requireConnection(false);
      if (!TransactionSynchronizationManager.isSynchronizationActive()
          || currentHolder != expectedHolder
          || currentHolder.getConnection() != expectedConnection
          || (entityManagerFactory != null && !isJpaConnection(expectedConnection))) {
        throw unavailable();
      }
      return currentHolder;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private ConnectionHolder requireConnection(boolean inspectIsolation) {
    try {
      if (dataSource == null
          || !TransactionSynchronizationManager.isActualTransactionActive()
          || !(TransactionSynchronizationManager.getResource(dataSource)
              instanceof ConnectionHolder holder)
          || holder.isRollbackOnly()
          || (!holder.isSynchronizedWithTransaction() && !isJpaConnection(holder.getConnection()))
          || holder.getConnection().getAutoCommit()
          || (inspectIsolation
              && holder.getConnection().getTransactionIsolation()
                  != Connection.TRANSACTION_READ_COMMITTED)) {
        throw unavailable();
      }
      return holder;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private boolean isJpaConnection(Connection connection) {
    if (entityManagerFactory == null) return false;
    Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
    if (!(resource instanceof EntityManagerHolder holder)
        || !holder.isSynchronizedWithTransaction()
        || !holder.getEntityManager().getTransaction().isActive()
        || holder.getEntityManager().getTransaction().getRollbackOnly()) return false;
    return holder
        .getEntityManager()
        .unwrap(Session.class)
        .doReturningWork(actual -> actual == connection);
  }

  private UiLayoutLifecycleException unavailable() {
    return new UiLayoutLifecycleException(
        UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "The active Config transaction is unavailable.");
  }
}
