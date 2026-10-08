package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.security.Principal;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Existing admitted schema only. No Flyway/DDL/container. Real JPA lookup on a separate connection.
 * Tests association visibility, not the complete producer/capture transaction or host authentication.
 */
@Tag("postgres")
@EnabledIfSystemProperty(named = "praxis.ui-layout.pg.it", matches = "true")
class UiLayoutDraftCreationLookupPostgresIT {
  static DriverManagerDataSource dataSource;
  static SessionFactory factory;
  Connection writer;
  EntityManager reader;
  UiLayoutLifecycleReadService reads;
  UUID id;
  String key;
  final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "lk14-fixture-root");
  final Principal actor = () -> "lk14-fixture-actor";

  @BeforeAll static void requireExistingMigratedSchema() throws Exception {
    String schema = required("PRAXIS_UI_LAYOUT_PG_SCHEMA");
    if (!schema.matches("b1a_it_[a-z0-9_]+")) throw new IllegalStateException("Dedicated existing b1a_it_* schema required.");
    dataSource = new DriverManagerDataSource(required("PRAXIS_UI_LAYOUT_PG_JDBC_URL"),
        required("PRAXIS_UI_LAYOUT_PG_USER"), required("PRAXIS_UI_LAYOUT_PG_PASSWORD")) {
      @Override public Connection getConnection() throws java.sql.SQLException {
        var connection = super.getConnection();
        try {
          connection.setSchema(schema); connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          try (var statement = connection.createStatement()) {
            statement.execute("SET lock_timeout = '2s'"); statement.execute("SET statement_timeout = '5s'");
          }
          return connection;
        } catch (java.sql.SQLException failure) { connection.close(); throw failure; }
      }
    };
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      try (var result = statement.executeQuery("SELECT current_schema(), current_setting('server_encoding'), "
          + "(SELECT count(*) FROM flyway_schema_history WHERE version IN ('65','68') AND success)")) {
        assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo(schema);
        assertThat(result.getString(2)).isEqualTo("UTF8"); assertThat(result.getInt(3)).isEqualTo(2);
      }
      try (var result = statement.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='uq_ui_layout_draft_creation_idempotency'")) {
        assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(1);
      }
    }
    var configuration = new Configuration().addAnnotatedClass(UiLayoutDraft.class);
    configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
    configuration.setProperty("hibernate.default_schema", schema);
    configuration.setProperty("hibernate.show_sql", "false");
    configuration.getProperties().put("hibernate.connection.datasource", dataSource);
    factory = configuration.buildSessionFactory();
  }
  @AfterAll static void closeFactory() { if (factory != null) factory.close(); }

  @BeforeEach void openIndependentWriterAndJpaReader() throws Exception {
    id = UUID.randomUUID(); key = "lk14-" + UUID.randomUUID();
    writer = dataSource.getConnection(); writer.setAutoCommit(false);
    reader = factory.createEntityManager();
    var repository = new JpaRepositoryFactory(reader).getRepository(UiLayoutDraftRepository.class);
    var invocation = new UiLayoutLifecycleInvocation(actor.getName(), "lk14-fixture-tenant", "fixture-unit", "lab", "ctx",
        new UiLayoutCompositionRegistration(root, List.of(root)));
    var mapper = new ObjectMapper().findAndRegisterModules();
    reads = new UiLayoutLifecycleReadService((principal, version, target) -> invocation,
        (operation, current) -> assertThat(operation).isEqualTo(UiLayoutLifecycleOperation.READ_DRAFT),
        repository, mock(UiLayoutReleaseRepository.class), mock(UiLayoutReleaseReviewRepository.class),
        mock(UiLayoutReleaseHeadRepository.class), mock(UiLayoutDefinitionRepository.class),
        mock(UiLayoutRevisionRepository.class), mock(UiLayoutAssignmentRevisionRepository.class),
        mapper, new CanonicalJsonHashService(mapper), mock(UiLayoutLifecycleStructureValidator.class), (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
  }
  @AfterEach void cleanupOnlyOwnDraftAndClose() throws Exception {
    try {
      if (writer != null) {
        writer.rollback();
        try (var statement = writer.prepareStatement("DELETE FROM ui_layout_draft WHERE id=? AND tenant_id='lk14-fixture-tenant' AND created_by='lk14-fixture-actor'")) {
          statement.setObject(1, id); statement.executeUpdate(); writer.commit();
        }
      }
    } finally {
      // A reader close failure must not leave the independent writer connection open.
      try {
        if (reader != null) reader.close();
      } finally {
        if (writer != null) writer.close();
      }
    }
  }
  void insertUncommittedDraft() throws Exception {
    try (var statement = writer.prepareStatement("INSERT INTO ui_layout_draft "
        + "(id,tenant_id,environment,root_component_type,root_component_id,draft_document,draft_etag,state,created_by,creation_idempotency_key,created_at,updated_at,row_version) "
        + "VALUES (?,'lk14-fixture-tenant','lab','praxis-table','lk14-fixture-root','{}',?,'DRAFT','lk14-fixture-actor',?,now(),now(),0)")) {
      statement.setObject(1, id); statement.setObject(2, UUID.randomUUID()); statement.setString(3, key);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }
  void associationNotObserved() {
    reader.clear();
    assertThatThrownBy(() -> reads.draftByCreationKey(actor, "ctx", root, key))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .satisfies(failure -> assertThat(((UiLayoutLifecycleException) failure).getCode()).isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND));
  }
  @Test void independentLookupObservesOnlyCommittedDraftAssociation() throws Exception {
    insertUncommittedDraft(); associationNotObserved();
    writer.commit(); reader.clear();
    var receipt = reads.draftByCreationKey(actor, "ctx", root, key);
    assertThat(receipt.draftRef()).isEqualTo(id.toString()); assertThat(receipt.state()).isEqualTo("DRAFT");
  }
  @Test void rollbackNeverBecomesAConfirmedAssociation() throws Exception {
    insertUncommittedDraft(); associationNotObserved();
    writer.rollback(); associationNotObserved();
  }
  static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " required for admitted opt-in gate.");
    return value;
  }
}
