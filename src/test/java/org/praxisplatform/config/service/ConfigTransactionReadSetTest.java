package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transaction propagation tests use mocked JDBC handles, not a local or embedded database. */
@Tag("unit")
class ConfigTransactionReadSetTest {
  private DataSource source;
  private TransactionTemplate transaction;
  private ConfigJdbcTransactionGuard guard;
  private final Object owner = new Object();
  private final AtomicInteger loads = new AtomicInteger();

  @BeforeEach
  void setup() throws Exception {
    source = mock(DataSource.class);
    when(source.getConnection()).thenAnswer(call -> connection());
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    guard = new ConfigJdbcTransactionGuard(source, transaction);
  }

  private Connection connection() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    var statement = mock(java.sql.PreparedStatement.class);
    var result = mock(java.sql.ResultSet.class);
    when(connection.prepareStatement("SELECT current_setting('transaction_isolation')"))
        .thenReturn(statement);
    when(statement.executeQuery())
        .thenAnswer(
            call -> {
              when(result.next()).thenReturn(true, false);
              return result;
            });
    when(result.getString(1)).thenReturn("read committed");
    return connection;
  }

  private Optional<String> read(Object key) {
    return read(owner, key, () -> Optional.of("value-" + loads.incrementAndGet()));
  }

  private Optional<String> read(
      Object currentOwner, Object key, Supplier<Optional<String>> loader) {
    return ConfigTransactionReadSet.read(currentOwner, guard, key, loader);
  }

  @Test
  void prefetchChecksBeforeAndAfterLoaderAndRetainsAnEntireBoundedPrefix() throws Exception {
    Connection actual = connection();
    when(source.getConnection()).thenReturn(actual);
    transaction.executeWithoutResult(status -> {
      var rows = new java.util.LinkedHashMap<Object, String>();
      for (int index = 0; index < 128; index++) rows.put(index, "raw-" + index);
      assertThat(ConfigTransactionReadSet.prefetch(owner, guard,
          () -> new ConfigTransactionReadSet.Prefetched(128, rows))).isEqualTo(128);
      for (int index = 0; index < 128; index++) assertThat(read(index)).contains("raw-" + index);
      assertThat(loads).hasValue(0);
      var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(source);
      assertThatThrownBy(() -> ConfigTransactionReadSet.prefetch(owner, guard, () -> {
        holder.setRollbackOnly();
        return new ConfigTransactionReadSet.Prefetched(1, java.util.Map.of("late", "raw"));
      })).isInstanceOf(UiLayoutLifecycleException.class);
      var invoked = new AtomicInteger();
      assertThatThrownBy(() -> ConfigTransactionReadSet.prefetch(owner, guard, () -> {
        invoked.incrementAndGet();
        return new ConfigTransactionReadSet.Prefetched(1, java.util.Map.of());
      })).isInstanceOf(UiLayoutLifecycleException.class);
      assertThat(invoked).hasValue(0);
      status.setRollbackOnly();
    });
    verify(actual, times(1)).prepareStatement("SELECT current_setting('transaction_isolation')");
    verify(actual, never()).getTransactionIsolation();
  }

  @Test
  void repeatedPrefetchReplacementDoesNotDoubleCountBytesOrRetainMissingRows() {
    transaction.executeWithoutResult(status -> {
      String half = "x".repeat((int) ConfigTransactionReadSet.MAX_BYTES / 2);
      var rows = new java.util.LinkedHashMap<Object, String>();
      rows.put("a", half); rows.put("b", half);
      for (int repeat = 0; repeat < 3; repeat++) {
        ConfigTransactionReadSet.prefetch(owner, guard, () -> new ConfigTransactionReadSet.Prefetched(2, rows));
        assertThat(read("a")).contains(half);
        assertThat(read("b")).contains(half);
      }
      ConfigTransactionReadSet.prefetch(owner, guard,
          () -> new ConfigTransactionReadSet.Prefetched(1, java.util.Map.of()));
      assertThat(read("missing")).contains("value-1");
    });
  }

  @Test
  void reusesOnlyPositiveImmutableBytesWithinTheSameTransactionAndOwner() {
    transaction.executeWithoutResult(
        status -> {
          assertThat(read("key")).contains("value-1");
          assertThat(read("key")).contains("value-1");
          assertThat(read(new Object(), "key", () -> Optional.of("other"))).contains("other");
          assertThat(read(owner, "missing", Optional::empty)).isEmpty();
          assertThat(read(owner, "missing", () -> Optional.of("now-present")))
              .contains("now-present");
          assertThat(read("key")).contains("value-1");
        });
    transaction.executeWithoutResult(status -> assertThat(read("key")).contains("value-2"));
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  @Test
  void suspendsForRequiresNewAndResumesTheOriginalConnectionReadSet() {
    TransactionTemplate nested = new TransactionTemplate(transaction.getTransactionManager());
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.executeWithoutResult(
        status -> {
          var originalConnection = guard.require().getConnection();
          assertThat(read("key")).contains("value-1");
          nested.executeWithoutResult(
              inner -> {
                assertThat(guard.require().getConnection()).isNotSameAs(originalConnection);
                assertThat(read("key")).contains("value-2");
                assertThat(read("key")).contains("value-2");
              });
          assertThat(guard.require().getConnection()).isSameAs(originalConnection);
          assertThat(read("key")).contains("value-1");
        });
    transaction.executeWithoutResult(status -> assertThat(read("key")).contains("value-3"));
  }

  @Test
  void nestedSavepointRollbackDiscardsPositiveBytesWithoutRenewingTransactionAdmission()
      throws Exception {
    Connection actual = connection();
    var metadata = mock(java.sql.DatabaseMetaData.class);
    when(metadata.supportsSavepoints()).thenReturn(true);
    when(actual.getMetaData()).thenReturn(metadata);
    when(actual.setSavepoint(anyString())).thenReturn(mock(java.sql.Savepoint.class));
    when(source.getConnection()).thenReturn(actual);
    TransactionTemplate nested = new TransactionTemplate(transaction.getTransactionManager());
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    transaction.executeWithoutResult(
        status -> {
          assertThat(read("before")).contains("value-1");
          nested.executeWithoutResult(
              inner -> {
                assertThat(inner.hasSavepoint()).isTrue();
                assertThat(read("inside")).contains("value-2");
                assertThat(read("inside")).contains("value-2");
                inner.setRollbackOnly();
              });
          assertThat(read("inside")).contains("value-3");
          assertThat(read("before")).contains("value-4");
        });
    verify(actual).rollback(any(java.sql.Savepoint.class));
    verify(actual, times(1)).prepareStatement("SELECT current_setting('transaction_isolation')");
    verify(actual, never()).getTransactionIsolation();
  }

  @Test
  void rejectsChangedConnectionAndRollbackOnlyEvenOnAHit() throws Exception {
    transaction.executeWithoutResult(
        status -> {
          assertThat(read("key")).isPresent();
          Object original = TransactionSynchronizationManager.unbindResource(source);
          try {
            ConnectionHolder replacement;
            try {
              replacement = new ConnectionHolder(connection());
            } catch (Exception exception) {
              throw new AssertionError(exception);
            }
            replacement.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.bindResource(source, replacement);
            assertThatThrownBy(() -> read("key")).isInstanceOf(UiLayoutLifecycleException.class);
          } finally {
            TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.bindResource(source, original);
          }
          ((ConnectionHolder) original).setRollbackOnly();
          assertThatThrownBy(() -> read("key")).isInstanceOf(UiLayoutLifecycleException.class);
          status.setRollbackOnly();
        });
  }

  @Test
  void evictsRatherThanTruncatingOrSkippingLoadsAtEntryAndByteBounds() {
    transaction.executeWithoutResult(
        status -> {
          for (int index = 0; index < ConfigTransactionReadSet.MAX_ENTRIES + 1; index++)
            read(index);
          assertThat(read(0)).contains("value-" + (ConfigTransactionReadSet.MAX_ENTRIES + 2));
          String large = "x".repeat((int) ConfigTransactionReadSet.MAX_BYTES / 2 + 1);
          read(owner, "large1", () -> Optional.of(large));
          read(owner, "large2", () -> Optional.of(large));
          assertThat(read("large1")).contains("value-" + (ConfigTransactionReadSet.MAX_ENTRIES + 3));
          String oversized = "x".repeat((int) ConfigTransactionReadSet.MAX_BYTES + 1);
          assertThat(read(owner, "oversized", () -> Optional.of(oversized))).contains(oversized);
          assertThat(read("oversized")).contains("value-" + (ConfigTransactionReadSet.MAX_ENTRIES + 4));
        });
  }

  @Test
  void evenSynchronizedJdbcHolderCannotMaskChangedJpaConnectionOrRollbackOnly() throws Exception {
    Connection actual = connection();
    when(source.getConnection()).thenReturn(actual);
    var factory = mock(jakarta.persistence.EntityManagerFactory.class);
    var entityManager = mock(jakarta.persistence.EntityManager.class);
    var entityTransaction = mock(jakarta.persistence.EntityTransaction.class);
    var session = mock(org.hibernate.Session.class);
    var entityHolder = new org.springframework.orm.jpa.EntityManagerHolder(entityManager);
    entityHolder.setSynchronizedWithTransaction(true);
    when(entityManager.getTransaction()).thenReturn(entityTransaction);
    when(entityTransaction.isActive()).thenReturn(true);
    when(entityManager.unwrap(org.hibernate.Session.class)).thenReturn(session);
    doAnswer(call -> ((org.hibernate.jdbc.ReturningWork<?>) call.getArgument(0)).execute(actual))
        .when(session)
        .doReturningWork(any());
    var manager = new org.springframework.orm.jpa.JpaTransactionManager();
    manager.setEntityManagerFactory(factory);
    manager.setDataSource(source);
    guard = new ConfigJdbcTransactionGuard(source, new TransactionTemplate(manager));
    TransactionSynchronizationManager.bindResource(factory, entityHolder);
    try {
      transaction.executeWithoutResult(
          status -> {
            assertThat(guard.require().getConnection()).isSameAs(actual);
            assertThat(read("key")).isPresent();
            when(entityTransaction.getRollbackOnly()).thenReturn(true);
            assertThatThrownBy(guard::require).isInstanceOf(UiLayoutLifecycleException.class);
            assertThatThrownBy(() -> read("key")).isInstanceOf(UiLayoutLifecycleException.class);
            when(entityTransaction.getRollbackOnly()).thenReturn(false);
            doAnswer(
                    call ->
                        ((org.hibernate.jdbc.ReturningWork<?>) call.getArgument(0))
                            .execute(mock(Connection.class)))
                .when(session)
                .doReturningWork(any());
            assertThatThrownBy(guard::require).isInstanceOf(UiLayoutLifecycleException.class);
            assertThatThrownBy(() -> read("key")).isInstanceOf(UiLayoutLifecycleException.class);
          });
    } finally {
      TransactionSynchronizationManager.unbindResource(factory);
    }
  }

  @Test
  void isolationIsProvenOnceWithSelectAndNeverInspectedWithShowOnHits() throws Exception {
    Connection actual = connection();
    when(source.getConnection()).thenReturn(actual);
    transaction.executeWithoutResult(
        status -> {
          read("key");
          read("key");
          read("another");
        });
    verify(actual, times(1)).prepareStatement("SELECT current_setting('transaction_isolation')");
    verify(actual, never()).getTransactionIsolation();
  }

  @Test
  void wrongIsolationAndFailedAdmissionRetainNothing() throws Exception {
    for (boolean queryFails : new boolean[] {false, true}) {
      Connection actual = connection();
      var statement = actual.prepareStatement("SELECT current_setting('transaction_isolation')");
      if (queryFails) {
        when(statement.executeQuery()).thenThrow(new java.sql.SQLException("Unreachable"));
      } else {
        var result = mock(java.sql.ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn("repeatable read");
        when(statement.executeQuery()).thenReturn(result);
      }
      when(source.getConnection()).thenReturn(actual);
      transaction.executeWithoutResult(
          status -> {
            assertThatThrownBy(() -> read("key")).isInstanceOf(UiLayoutLifecycleException.class);
            assertThat(TransactionSynchronizationManager.getSynchronizations())
                .noneMatch(sync -> sync instanceof ConfigTransactionReadSet);
            status.setRollbackOnly();
          });
    }
    assertThat(loads.get()).isZero();
  }

  @Test
  void outsideTransactionAlwaysLoadsWithoutCreatingSynchronization() {
    assertThat(read("key")).contains("value-1");
    assertThat(read("key")).contains("value-2");
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }
}
