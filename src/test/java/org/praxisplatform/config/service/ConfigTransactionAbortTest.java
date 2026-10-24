package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Savepoint;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring propagation with mocked JDBC, not an embedded/local database. */
@Tag("unit")
class ConfigTransactionAbortTest {
  private DataSource source;
  private Connection connection;
  private TransactionTemplate transaction;
  private ConfigJdbcTransactionGuard guard;

  @BeforeEach
  void setup() throws Exception {
    source = mock(DataSource.class);
    connection = mock(Connection.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    var statement = mock(java.sql.PreparedStatement.class);
    var result = mock(java.sql.ResultSet.class);
    when(connection.prepareStatement("SELECT current_setting('transaction_isolation')")).thenReturn(statement);
    when(statement.executeQuery()).thenAnswer(call -> {
      when(result.next()).thenReturn(true, false);
      return result;
    });
    when(result.getString(1)).thenReturn("read committed");
    var metadata = mock(DatabaseMetaData.class);
    when(metadata.supportsSavepoints()).thenReturn(true);
    when(connection.getMetaData()).thenReturn(metadata);
    when(connection.setSavepoint(anyString())).thenReturn(mock(Savepoint.class));
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    guard = new ConfigJdbcTransactionGuard(source, transaction);
  }

  @Test
  void swallowedConflictCannotCommitItsOuterTransaction() throws Exception {
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      guard.abortTransaction();
      // Simulate a caller swallowing the operation's conflict; the global marker remains.
    })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
    verify(connection, never()).commit();
    verify(connection).rollback();
  }

  @Test
  void nestedRollbackCannotErasePoisonEvenWhenHolderWasAlreadyRollbackOnly() throws Exception {
    var nested = new TransactionTemplate(transaction.getTransactionManager());
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    assertThatThrownBy(() -> transaction.executeWithoutResult(outer -> {
      nested.executeWithoutResult(inner -> {
        ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).setRollbackOnly();
        guard.abortTransaction();
        inner.setRollbackOnly();
      });
      // Spring resets ConnectionHolder rollback-only when rolling back to a savepoint. The
      // irreversible synchronization must still prevent commit of the owning transaction.
      assertThat(((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).isRollbackOnly()).isFalse();
    })).isInstanceOf(UiLayoutLifecycleException.class)
        .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    verify(connection).rollback(any(Savepoint.class));
    verify(connection).rollback();
    verify(connection, never()).commit();
  }

  @Test
  void poisonDoesNotLeakIntoTheNextTransaction() throws Exception {
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> guard.abortTransaction()))
        .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
    transaction.executeWithoutResult(status -> assertThat(guard.require()).isNotNull());
    verify(connection, times(1)).commit();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  @Test
  void requiresNewPoisonDoesNotReplaceTheOuterTransaction() throws Exception {
    Connection nestedConnection = mock(Connection.class);
    when(nestedConnection.getAutoCommit()).thenReturn(false);
    when(source.getConnection()).thenReturn(connection, nestedConnection);
    var nested = new TransactionTemplate(transaction.getTransactionManager());
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.executeWithoutResult(outer -> {
      assertThatThrownBy(() -> nested.executeWithoutResult(inner -> guard.abortTransaction()))
          .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
      assertThat(guard.require().getConnection()).isSameAs(connection);
    });
    verify(nestedConnection).rollback();
    verify(nestedConnection, never()).commit();
    verify(connection).commit();
  }

  @Test
  void alreadyRollbackOnlyJpaOwnerIsStillPoisonedAndCorrelated() throws Exception {
    var factory = mock(jakarta.persistence.EntityManagerFactory.class);
    var manager = mock(jakarta.persistence.EntityManager.class);
    var owner = mock(jakarta.persistence.EntityTransaction.class);
    var session = mock(org.hibernate.Session.class);
    when(manager.getTransaction()).thenReturn(owner);
    when(owner.isActive()).thenReturn(true);
    when(owner.getRollbackOnly()).thenReturn(true);
    when(manager.unwrap(org.hibernate.Session.class)).thenReturn(session);
    doAnswer(call -> ((org.hibernate.jdbc.ReturningWork<?>) call.getArgument(0)).execute(connection))
        .when(session).doReturningWork(any());
    var entityHolder = new org.springframework.orm.jpa.EntityManagerHolder(manager);
    entityHolder.setSynchronizedWithTransaction(true);
    var jpa = new org.springframework.orm.jpa.JpaTransactionManager();
    jpa.setEntityManagerFactory(factory);
    jpa.setDataSource(source);
    var jpaGuard = new ConfigJdbcTransactionGuard(source, new TransactionTemplate(jpa));
    TransactionSynchronizationManager.bindResource(factory, entityHolder);
    try {
      assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
        ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).setRollbackOnly();
        jpaGuard.abortTransaction();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
      })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
      verify(owner).setRollbackOnly();
      verify(connection, never()).commit();
    } finally {
      TransactionSynchronizationManager.unbindResource(factory);
    }
  }

  @Test
  void noTransactionOrForeignHolderCannotBePoisonedAsConfig() {
    assertThatThrownBy(guard::abortTransaction).isInstanceOf(UiLayoutLifecycleException.class);
  }
}
