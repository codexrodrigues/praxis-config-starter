package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in PostgreSQL gate for the actual allocation adapter. It never cleans an isolated schema.
 */
@Tag("postgres")
@EnabledIfSystemProperty(named = "praxis.ui-layout.pg.it", matches = "true")
class UiLayoutLifecyclePostgresIT {
  private static final Duration LOCK_WAIT = Duration.ofMillis(300);
  private static String jdbcUrl;
  private static String username;
  private static String password;
  private static String schema;
  private static ExecutorService executor;
  private static UiLayoutRevisionAllocationLock allocationLock;
  private static TransactionTemplate configTransaction;

  @BeforeAll
  static void migrateDedicatedSchema() {
    jdbcUrl = requiredEnvironment("PRAXIS_UI_LAYOUT_PG_JDBC_URL");
    username = requiredEnvironment("PRAXIS_UI_LAYOUT_PG_USER");
    password = requiredEnvironment("PRAXIS_UI_LAYOUT_PG_PASSWORD");
    schema = requiredEnvironment("PRAXIS_UI_LAYOUT_PG_SCHEMA");
    if (!schema.matches("b1a_it_[a-z0-9_]+")) {
      throw new IllegalStateException("PRAXIS_UI_LAYOUT_PG_SCHEMA must be a dedicated b1a_it_* schema.");
    }
    Flyway.configure().dataSource(jdbcUrl, username, password).schemas(schema).defaultSchema(schema)
        .createSchemas(true).locations("classpath:db/migration").load().migrate();
    DriverManagerDataSource configDataSource = new DriverManagerDataSource(jdbcUrl, username, password);
    allocationLock = new JdbcUiLayoutRevisionAllocationLock(
        new NamedParameterJdbcTemplate(new JdbcTemplate(configDataSource)));
    configTransaction = new TransactionTemplate(new DataSourceTransactionManager(configDataSource));
    executor = Executors.newFixedThreadPool(2);
  }

  @AfterAll
  static void stopWorkers() {
    if (executor != null) executor.shutdownNow();
  }

  @Test
  void v63LifecycleTablesArePresentInTheDedicatedSchema() throws Exception {
    assertThat(regclass("ui_layout_release")).isEqualTo(schema + ".ui_layout_release");
    assertThat(regclass("ui_layout_release_approval")).isEqualTo(schema + ".ui_layout_release_approval");
    assertThat(regclass("ui_layout_release_event")).isEqualTo(schema + ".ui_layout_release_event");
  }

  @Test
  void allocationAdapterSerializesTheFirstAndLaterRevisionAllocation() throws Exception {
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    Future<?> first = executor.submit(() -> lockUntilReleased(firstLocked, releaseFirst));
    assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

    Future<?> second = executor.submit(() -> lockAndCommit());
    Thread.sleep(LOCK_WAIT);
    assertThat(second.isDone()).isFalse();

    releaseFirst.countDown();
    first.get(5, TimeUnit.SECONDS);
    second.get(5, TimeUnit.SECONDS);
  }

  private static String regclass(String table) throws Exception {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement("select to_regclass(?)")) {
      statement.setString(1, schema + "." + table);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getString(1);
      }
    }
  }

  private static void lockUntilReleased(CountDownLatch locked, CountDownLatch release) {
    try {
      configTransaction.executeWithoutResult(status -> {
        allocationLock.lock("tenant-a", "lab", target());
        locked.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out releasing the advisory lock.");
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(exception);
        }
      });
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void lockAndCommit() {
    try {
      configTransaction.executeWithoutResult(status -> allocationLock.lock("tenant-a", "lab", target()));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static UiLayoutTarget target() {
    return new UiLayoutTarget("praxis-dynamic-page", "procurement-master-detail");
  }

  private static Connection connection() throws Exception {
    return DriverManager.getConnection(jdbcUrl, username, password);
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the opt-in PostgreSQL gate.");
    return value;
  }
}
