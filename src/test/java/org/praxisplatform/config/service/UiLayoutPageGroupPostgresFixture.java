package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLSocket;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.praxisplatform.config.domain.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated P3 Neon fixture. It never selects another assignment or creates schema at test time. */
abstract class UiLayoutPageGroupPostgresFixture {
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static DriverManagerDataSource dataSource;
  static SessionFactory factory;
  static TransactionTemplate transactions;
  static JpaRepositoryFactory repositories;
  static JdbcTemplate jdbc;
  static JsonNode assignment;
  static final AtomicLong sqlCalls = new AtomicLong();
  static final AtomicLong sqlNanos = new AtomicLong();
  static final AtomicLong isolationCalls = new AtomicLong();
  static final AtomicLong isolationNanos = new AtomicLong();
  static volatile SqlProbe probe;

  interface SqlProbe {
    default void managed(int count) {}
    default void beforeCommit(Connection connection) throws Exception {}
    default void afterCommit(Connection connection, Throwable failure) throws Exception {}
    default void before(Connection connection, String sql, Map<Integer, Object> binds) throws Exception {}
    default void after(Connection connection, String sql, Map<Integer, Object> binds, Object result) throws Exception {}
  }

  @BeforeAll
  static void connectAssignedRuntimeOnly() throws Exception {
    String file = System.getenv("PRAXIS_PAGE_P3A_NEON_RUNTIME_FILE");
    if (file == null) throw new IllegalStateException("Explicit P3 Neon runtime assignment required");
    assignment = JSON.readTree(Files.readString(Path.of(file)));
    requireAssignment(assignment);
    dataSource = new DriverManagerDataSource(assignment.path("jdbcUrl").asText(),
        assignment.path("username").asText(), assignment.path("password").asText()) {
      @Override public Connection getConnection() throws java.sql.SQLException {
        Connection connection = super.getConnection();
        try {
          connection.setSchema(assignment.path("schema").asText());
          connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          return instrument(connection);
        } catch (java.sql.SQLException exception) {
          connection.close();
          throw exception;
        }
      }
    };
    jdbc = new JdbcTemplate(dataSource);
    try (var connection = dataSource.getConnection()) {
      var field = org.postgresql.core.QueryExecutorBase.class.getDeclaredField("pgStream");
      field.setAccessible(true);
      var stream = (org.postgresql.core.PGStream) field.get(
          connection.unwrap(org.postgresql.jdbc.PgConnection.class).getQueryExecutor());
      assertThat(stream.getSocket()).isInstanceOf(SSLSocket.class);
      var tls = ((SSLSocket) stream.getSocket()).getSession();
      assertThat(tls.getPeerHost()).isEqualTo(assignment.path("endpoint").asText());
      try (var statement = connection.createStatement(); var row = statement.executeQuery("""
          SELECT current_user,current_schema(),current_database(),current_setting('server_version'),
                 rolsuper,rolcreatedb,rolcreaterole,rolbypassrls FROM pg_roles WHERE rolname=current_user
          """)) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo("p3a_group_511_runtime");
        assertThat(row.getString(2)).isEqualTo("p3a_group_511_20261022");
        assertThat(row.getString(3)).isEqualTo("neondb");
        assertThat(row.getString(4)).startsWith("17.11");
        for (int column = 5; column <= 8; column++) assertThat(row.getBoolean(column)).isFalse();
        System.out.println("P3-A Neon project=" + assignment.path("projectId").asText()
            + " branch=" + assignment.path("branchId").asText() + " endpoint=" + assignment.path("endpoint").asText()
            + " role=" + row.getString(1) + " schema=" + row.getString(2) + " PostgreSQL=" + row.getString(4)
            + " TLS=" + tls.getProtocol() + " peer=" + tls.getPeerPrincipal().getName());
      }
    }
    assertThat(jdbc.queryForObject("SELECT has_schema_privilege(current_user,current_schema(),'CREATE')", Boolean.class)).isFalse();
    assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'ui_layout_revision','UPDATE')", Boolean.class)).isFalse();
    assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'ui_page_catalog','DELETE')", Boolean.class)).isFalse();
    Configuration config = new Configuration();
    for (var type : List.of(UiLayoutDraft.class, UiLayoutDefinition.class, UiLayoutRevision.class,
        UiLayoutAssignmentRevision.class, UiLayoutRelease.class, UiLayoutReleaseMember.class,
        UiLayoutReleaseReview.class, UiLayoutReleaseApproval.class, UiLayoutReleaseHead.class,
        UiLayoutReleaseEvent.class)) config.addAnnotatedClass(type);
    config.setProperty("hibernate.hbm2ddl.auto", "validate");
    config.setProperty("hibernate.default_schema", assignment.path("schema").asText());
    config.setProperty("hibernate.show_sql", "false");
    config.getProperties().put("hibernate.connection.datasource", dataSource);
    factory = config.buildSessionFactory();
    factory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class).getServiceRegistry()
        .getService(org.hibernate.event.service.spi.EventListenerRegistry.class)
        .getEventListenerGroup(org.hibernate.event.spi.EventType.POST_LOAD)
        .appendListener(event -> {
          SqlProbe current = probe;
          if (current != null) current.managed(event.getSession().getPersistenceContextInternal().getNumberOfManagedEntities());
        });
    var manager = new JpaTransactionManager(factory);
    manager.setDataSource(dataSource);
    manager.setJpaDialect(new HibernateJpaDialect());
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(45);
    repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
  }

  static void requireAssignment(JsonNode value) {
    if (!"ancient-leaf-72055027".equals(value.path("projectId").asText())
        || !"br-holy-water-a4ihmkkj".equals(value.path("branchId").asText())
        || !"ep-hidden-bread-a4x3zddv.us-east-1.aws.neon.tech".equals(value.path("endpoint").asText())
        || !"p3a_group_511_20261022".equals(value.path("schema").asText())
        || !"p3a_group_511_runtime".equals(value.path("username").asText())
        || value.path("password").asText().isBlank()) throw new IllegalArgumentException("P3 assignment mismatch");
    var uri = java.net.URI.create(value.path("jdbcUrl").asText().replaceFirst("^jdbc:", ""));
    var query = uri.getRawQuery();
    if (!value.path("endpoint").asText().equals(uri.getHost()) || uri.getUserInfo() != null
        || !"/neondb".equals(uri.getPath()) || query == null
        || !Arrays.asList(query.split("&")).contains("sslmode=verify-full")
        || !Arrays.asList(query.split("&")).contains("sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory")
        || query.contains("password=") || query.contains("user=")) throw new IllegalArgumentException("P3 TLS assignment mismatch");
  }

  private static Connection instrument(Connection connection) {
    return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
          try {
            long started = System.nanoTime();
            Object result;
            SqlProbe connectionProbe = probe;
            boolean committing = method.getName().equals("commit");
            if (committing && connectionProbe != null) connectionProbe.beforeCommit(connection);
            try {
              result = method.invoke(connection, arguments);
            } catch (InvocationTargetException failure) {
              if (committing && connectionProbe != null) connectionProbe.afterCommit(connection, failure.getCause());
              throw failure;
            }
            if (committing && connectionProbe != null) connectionProbe.afterCommit(connection, null);
            if (method.getName().equals("getTransactionIsolation")) {
              isolationCalls.incrementAndGet(); isolationNanos.addAndGet(System.nanoTime() - started);
            }
            if (!method.getName().equals("prepareStatement") || !(result instanceof PreparedStatement statement)) return result;
            String sql = (String) arguments[0];
            var bindings = new HashMap<Integer, Object>();
            return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (statementProxy, statementMethod, statementArguments) -> {
                  try {
                    if (statementMethod.getName().startsWith("set") && statementArguments != null
                        && statementArguments.length >= 2 && statementArguments[0] instanceof Integer index) {
                      bindings.put(index, statementArguments[1]);
                    }
                    if (!statementMethod.getName().startsWith("execute")) return statementMethod.invoke(statement, statementArguments);
                    SqlProbe current = probe;
                    if (current != null) current.before(connection, sql, Collections.unmodifiableMap(new HashMap<>(bindings)));
                    long sqlStarted = System.nanoTime();
                    Object executed = statementMethod.invoke(statement, statementArguments);
                    sqlCalls.incrementAndGet(); sqlNanos.addAndGet(System.nanoTime() - sqlStarted);
                    if (current != null) current.after(connection, sql, Collections.unmodifiableMap(new HashMap<>(bindings)), executed);
                    return executed;
                  } catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
          } catch (InvocationTargetException exception) { throw exception.getCause(); }
        });
  }

  static <T> T repo(Class<T> type) { return repositories.getRepository(type); }

  @AfterAll
  static void close() {
    probe = null;
    if (factory != null) factory.close();
  }
}
