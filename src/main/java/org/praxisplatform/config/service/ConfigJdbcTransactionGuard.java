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
import org.springframework.transaction.support.TransactionSynchronization;

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
    // Without synchronization there is no transaction lifetime in which to retain proof.
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return requireConnection(true);
    ConnectionHolder holder = requireConnection(false);
    Connection connection = holder.getConnection();
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof IsolationProof proof && proof.dataSource == dataSource) {
        if (proof.completed || proof.holder != holder || proof.connection != connection) throw unavailable();
        if (!proof.pinned || !proof.reusable) pinIsolation(holder, connection, proof);
        return holder;
      }
    }
    var proof = new IsolationProof(dataSource, holder, connection,
        TransactionSynchronizationManager.getSynchronizations().isEmpty());
    pinIsolation(holder, connection, proof);
    TransactionSynchronizationManager.registerSynchronization(proof);
    return holder;
  }

  private void pinIsolation(ConnectionHolder holder, Connection connection, IsolationProof proof) {
    // PostgreSQL rejects isolation changes after a SELECT. SHOW does not pin that invariant.
    try (var statement = connection.prepareStatement("SELECT current_setting('transaction_isolation')");
        var result = statement.executeQuery()) {
      if (!result.next() || !"read committed".equals(result.getString(1)) || result.next()) {
        throw unavailable();
      }
    } catch (Exception exception) {
      throw unavailable();
    }
    requireIdentity(holder, connection);
    proof.pinned = true;
  }

  /** Infrastructure proof only: authority and immutable records have independent lifetimes. */
  private static final class IsolationProof implements TransactionSynchronization {
    final DataSource dataSource;
    final ConnectionHolder holder;
    final Connection connection;
    boolean pinned;
    boolean completed;
    boolean reusable;

    IsolationProof(DataSource dataSource, ConnectionHolder holder, Connection connection, boolean reusable) {
      this.dataSource = dataSource;
      this.holder = holder;
      this.connection = connection;
      // A first access from an already-running synchronization cannot receive that
      // callback. Only enlist before all other callbacks to retain proof safely.
      this.reusable = reusable;
    }

    // Spring invokes this before the physical rollback. A later synchronization may
    // re-enter a ledger here, so no newly acquired proof can survive this boundary.
    @Override public void savepointRollback(Object savepoint) {
      pinned = false;
      reusable = false;
    }
    @Override public void afterCompletion(int status) { pinned = false; completed = true; }
  }

  /** Flush local ORM writes before an authoritative JDBC read in this exact Config transaction. */
  void flush() {
    require();
    entityManager().flush();
  }

  /** A locking query alone does not refresh an already managed head. */
  void refresh(Object entity) {
    require();
    entityManager().refresh(entity, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
  }

  java.util.Set<Object> managedDefinitions() {
    require();
    var result = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
    entityManager().unwrap(org.hibernate.engine.spi.SessionImplementor.class)
        .getPersistenceContextInternal().getEntitiesByKey().values().stream()
        .filter(org.praxisplatform.config.domain.UiLayoutDefinition.class::isInstance)
        .forEach(result::add);
    return result;
  }

  /** Retire sealed batch records and scan-loaded definitions; preserve caller-managed definitions. */
  void detachBatch(UiLayoutFrozenRecordBatch batch, java.util.Set<Object> previouslyManagedDefinitions) {
    var manager = entityManager();
    java.util.stream.Stream.of(batch.releases().values(), batch.drafts().values(),
        batch.revisions().values(), batch.definitions().values(), batch.assignments().values())
        .flatMap(java.util.Collection::stream).forEach(entity -> {
          if (entity instanceof org.praxisplatform.config.domain.UiLayoutDraft draft
              && !"RELEASED".equals(draft.getState())) throw unavailable();
          if (!previouslyManagedDefinitions.contains(entity) && manager.contains(entity)) manager.detach(entity);
        });
    batch.members().values().stream().flatMap(java.util.Collection::stream)
        .forEach(entity -> { if (manager.contains(entity)) manager.detach(entity); });
  }

  /**
   * Poison the whole transaction, including when a caller catches a conflict or rolls back a
   * savepoint. The synchronization is not a grant or a held-lock registry, and survives a local
   * savepoint rollback. Spring suspends it with its transaction for REQUIRES_NEW.
   */
  void abortTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || !(TransactionSynchronizationManager.getResource(dataSource)
            instanceof ConnectionHolder holder)) throw unavailable();
    try {
      if (holder.getConnection().getAutoCommit()
          || (!holder.isSynchronizedWithTransaction() && !isJpaConnection(holder.getConnection(), true))
          || (entityManagerFactory != null && !isJpaConnection(holder.getConnection(), true))) throw unavailable();
    } catch (java.sql.SQLException exception) { throw unavailable(); }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void beforeCommit(boolean readOnly) {
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.PRECONDITION_FAILED,
            "The Config transaction conflicted; reload and retry in a new transaction.");
      }
    });
    holder.setRollbackOnly();
    if (entityManagerFactory != null) entityManager().getTransaction().setRollbackOnly();
  }

  private jakarta.persistence.EntityManager entityManager() {
    if (entityManagerFactory == null
        || !(TransactionSynchronizationManager.getResource(entityManagerFactory)
            instanceof EntityManagerHolder holder)
        || !holder.isSynchronizedWithTransaction()
        || !holder.getEntityManager().getTransaction().isActive()) throw unavailable();
    return holder.getEntityManager();
  }

  /** Uses the same pinned isolation proof as other ledgers in this Config transaction. */
  ConnectionHolder admitImmutableReadTransaction() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
    return require();
  }

  /** Recheck identity; after savepoint rollback, inspect isolation freshly on every access. */
  ConnectionHolder requireImmutableReadTransaction(
      ConnectionHolder expectedHolder, Connection expectedConnection) {
    requireIdentity(expectedHolder, expectedConnection);
    return require();
  }

  /** Called only after an identity check; new read-set owners inherit rollback invalidation. */
  boolean mayReuseImmutableReads() {
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof IsolationProof proof && proof.dataSource == dataSource) {
        return proof.pinned && !proof.completed && proof.reusable;
      }
    }
    return false;
  }

  private ConnectionHolder requireIdentity(
      ConnectionHolder expectedHolder, Connection expectedConnection) {
    try {
      ConnectionHolder currentHolder = requireConnection(false);
      if (!TransactionSynchronizationManager.isSynchronizationActive()
          || currentHolder != expectedHolder
          || currentHolder.getConnection() != expectedConnection) {
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
          || (entityManagerFactory != null
              ? !isJpaConnection(holder.getConnection())
              : !holder.isSynchronizedWithTransaction())
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
    return isJpaConnection(connection, false);
  }

  private boolean isJpaConnection(Connection connection, boolean acceptRollbackOnly) {
    if (entityManagerFactory == null) return false;
    Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
    if (!(resource instanceof EntityManagerHolder holder)
        || !holder.isSynchronizedWithTransaction()
        || !holder.getEntityManager().getTransaction().isActive()
        || (!acceptRollbackOnly && holder.getEntityManager().getTransaction().getRollbackOnly())) return false;
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
