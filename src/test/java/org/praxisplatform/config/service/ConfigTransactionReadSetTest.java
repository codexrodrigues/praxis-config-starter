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
          assertThat(read(0)).contains("value-18");
          String large = "x".repeat((int) ConfigTransactionReadSet.MAX_BYTES / 2 + 1);
          read(owner, "large1", () -> Optional.of(large));
          read(owner, "large2", () -> Optional.of(large));
          assertThat(read("large1")).contains("value-19");
          String oversized = "x".repeat((int) ConfigTransactionReadSet.MAX_BYTES + 1);
          assertThat(read(owner, "oversized", () -> Optional.of(oversized))).contains(oversized);
          assertThat(read("oversized")).contains("value-20");
        });
  }

  @Test
  void outsideTransactionAlwaysLoadsWithoutCreatingSynchronization() {
    assertThat(read("key")).contains("value-1");
    assertThat(read("key")).contains("value-2");
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }
}
