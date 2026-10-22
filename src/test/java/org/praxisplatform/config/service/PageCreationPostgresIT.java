package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.SSLSocket;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** P2 gate on the exclusively assigned Neon branch with real JPA/JDBC and restricted runtime. */
@Tag("neon")
@EnabledIfSystemProperty(named = "praxis.page.p2.neon.it", matches = "true")
class PageCreationPostgresIT {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static DriverManagerDataSource dataSource;
  private static SessionFactory factory;
  private static TransactionTemplate transactions;
  private static JpaRepositoryFactory repositories;
  private static JdbcTemplate jdbc;
  private static JsonNode assignment;
  private static final java.util.concurrent.atomic.AtomicLong executedStatements =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong statementNanos =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong isolationCalls =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong isolationNanos =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong autoCommitCalls =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong autoCommitNanos =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong connectionCalls =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong connectionNanos =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong commitResponseDelayMillis =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong delayedCommitResponses =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicReference<String> sqlFault =
      new java.util.concurrent.atomic.AtomicReference<>();
  private static final UiLayoutTarget ANCHOR =
      new UiLayoutTarget("praxis-app-shell", "99999999-9999-4999-8999-999999999999");
  private static final UiLayoutTarget SOURCE = UiLayoutPageRevisionReproductionTest.PAGE;
  private final String tenant = "p2-" + UUID.randomUUID();
  private volatile String actor = "alice", version = "c1", app = "app-a", namespace = "/pages";
  private volatile String selectedTenant = tenant, environment = "test";

  private record GroupScope(
      String tenant, String application, String environment, String namespace) {}

  private volatile boolean denyCopy, denyDependency, denyLookup, denyAncestorRead, revokeAtCommit;
  private volatile String fault;
  private boolean metadataCommitPhase, metadataMarkerRegistered, copyCheckedAtCommit;
  private boolean deadlineCommitEntered,
      denyDraft,
      revokeDraftAtLookupCommit,
      lookupMarkerRegistered;
  private Duration validationBudget = Duration.ofSeconds(40);
  private long grantDelayMillis;
  private int delayedGrantCalls;
  private ObjectNode groupDocument;
  private org.springframework.context.annotation.AnnotationConfigApplicationContext application;

  @AfterEach
  void closeApplication() {
    commitResponseDelayMillis.set(0);
    if (application != null) application.close();
  }

  private PageCreationService pages;
  private UiLayoutLifecycleCommandService commands;
  private UiLayoutLifecycleReadService reads;
  private PageCreationHost host;
  private final java.util.concurrent.atomic.AtomicInteger captures =
      new java.util.concurrent.atomic.AtomicInteger();

  private java.security.Principal principal() {
    String user = actor;
    return () -> user;
  }

  private UiLayoutLifecycleInvocation invocation(UiLayoutTarget root) {
    return new UiLayoutLifecycleInvocation(
        actor,
        selectedTenant,
        "unit",
        environment,
        version,
        new UiLayoutCompositionRegistration(root, List.of(root)));
  }

  private PageCreationContext context(UiLayoutLifecycleInvocation i) {
    return new PageCreationContext(app, namespace, i);
  }

  @BeforeAll
  static void connectAssignedRuntimeOnly() throws Exception {
    String file = System.getenv("PRAXIS_PAGE_P2_NEON_RUNTIME_FILE");
    if (file == null) throw new IllegalStateException("Explicit Neon runtime assignment required");
    assignment = JSON.readTree(Files.readString(Path.of(file)));
    requireAssignment(assignment);
    String url = assignment.path("jdbcUrl").asText();

    dataSource =
        new DriverManagerDataSource(
            url, assignment.path("username").asText(), assignment.path("password").asText()) {
          @Override
          public Connection getConnection() throws java.sql.SQLException {
            long started = System.nanoTime();
            Connection c = super.getConnection();
            connectionCalls.incrementAndGet();
            connectionNanos.addAndGet(System.nanoTime() - started);
            try {
              c.setSchema(assignment.path("schema").asText());
              c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
              return instrument(c);
            } catch (java.sql.SQLException e) {
              c.close();
              throw e;
            }
          }
        };
    jdbc = new JdbcTemplate(dataSource);
    try (var connection = dataSource.getConnection()) {
      var field = org.postgresql.core.QueryExecutorBase.class.getDeclaredField("pgStream");
      field.setAccessible(true);
      var stream =
          (org.postgresql.core.PGStream)
              field.get(
                  connection.unwrap(org.postgresql.jdbc.PgConnection.class).getQueryExecutor());
      assertThat(stream.getSocket()).isInstanceOf(SSLSocket.class);
      var tls = ((SSLSocket) stream.getSocket()).getSession();
      assertThat(tls.getPeerHost()).isEqualTo(assignment.path("endpoint").asText());
      try (var statement = connection.createStatement();
          var row =
              statement.executeQuery(
                  "select"
                      + " current_user,current_schema(),current_database(),current_setting('server_version'),rolsuper,rolcreatedb,rolcreaterole,rolbypassrls"
                      + " from pg_roles where rolname=current_user")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo(assignment.path("username").asText());
        assertThat(row.getString(2)).isEqualTo(assignment.path("schema").asText());
        assertThat(row.getString(3)).isEqualTo("neondb");
        for (int n = 5; n <= 8; n++) assertThat(row.getBoolean(n)).isFalse();
        System.out.println(
            "P2 Neon project="
                + assignment.path("projectId").asText()
                + " branch="
                + assignment.path("branchId").asText()
                + " role="
                + row.getString(1)
                + " schema="
                + row.getString(2)
                + " PostgreSQL="
                + row.getString(4)
                + " clientTLS="
                + tls.getProtocol()
                + " peer="
                + tls.getPeerPrincipal().getName());
      }
    }
    assertThat(
            jdbc.queryForObject(
                "select has_schema_privilege(current_user,current_schema(),'CREATE')",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select has_table_privilege(current_user,'ui_layout_revision','UPDATE')",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select"
                    + " has_table_privilege(current_user,'ui_layout_baseline_metadata_evidence','DELETE')",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select has_column_privilege(current_user,'ui_layout_definition','id','UPDATE')",
                Boolean.class))
        .isTrue();
    Configuration config = new Configuration();
    for (var type :
        List.of(
            UiLayoutDraft.class,
            UiLayoutDefinition.class,
            UiLayoutRevision.class,
            UiLayoutAssignmentRevision.class,
            UiLayoutRelease.class,
            UiLayoutReleaseMember.class,
            UiLayoutReleaseReview.class,
            UiLayoutReleaseApproval.class,
            UiLayoutReleaseHead.class,
            UiLayoutReleaseEvent.class)) config.addAnnotatedClass(type);
    config.setProperty("hibernate.hbm2ddl.auto", "validate");
    config.setProperty("hibernate.default_schema", assignment.path("schema").asText());
    config.setProperty("hibernate.show_sql", "false");
    config.getProperties().put("hibernate.connection.datasource", dataSource);
    factory = config.buildSessionFactory();
    var transactionManager = new JpaTransactionManager(factory);
    transactionManager.setDataSource(dataSource);
    transactionManager.setJpaDialect(new HibernateJpaDialect());
    transactions = new TransactionTemplate(transactionManager);
    transactions.setTimeout(45);
    repositories =
        new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
  }

  private static Connection instrument(Connection connection) {
    return (Connection)
        java.lang.reflect.Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                long connectionMethodStarted = System.nanoTime();
                Object result = method.invoke(connection, arguments);
                if (method.getName().equals("commit")) {
                  long delay = commitResponseDelayMillis.getAndSet(0);
                  if (delay > 0) {
                    delayedCommitResponses.incrementAndGet();
                    pause(delay);
                  }
                }
                if (method.getName().equals("getTransactionIsolation")) {
                  isolationCalls.incrementAndGet();
                  isolationNanos.addAndGet(System.nanoTime() - connectionMethodStarted);
                }
                if (method.getName().equals("getAutoCommit")) {
                  autoCommitCalls.incrementAndGet();
                  autoCommitNanos.addAndGet(System.nanoTime() - connectionMethodStarted);
                }
                if (result instanceof java.sql.PreparedStatement statement
                    && arguments != null
                    && arguments[0] instanceof String sql) {
                  return java.lang.reflect.Proxy.newProxyInstance(
                      java.sql.PreparedStatement.class.getClassLoader(),
                      new Class<?>[] {java.sql.PreparedStatement.class},
                      (statementProxy, statementMethod, statementArguments) -> {
                        try {
                          long started = System.nanoTime();
                          Object statementResult =
                              statementMethod.invoke(statement, statementArguments);
                          if (statementMethod.getName().startsWith("execute")) {
                            executedStatements.incrementAndGet();
                            statementNanos.addAndGet(System.nanoTime() - started);
                          }
                          String table = sqlFault.get();
                          if (table != null
                              && statementMethod.getName().startsWith("execute")
                              && sql.toLowerCase(java.util.Locale.ROOT)
                                  .matches(
                                      "(?s).*insert into (?:[a-z0-9_]+\\.)?" + table + "[ (].*")
                              && sqlFault.compareAndSet(table, null)) {
                            throw new java.sql.SQLException(
                                "Injected failure after " + table + " insert", "XX000");
                          }
                          return statementResult;
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                      });
                }
                return result;
              } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  static void requireAssignment(JsonNode value) {
    String endpoint = value.path("endpoint").asText(), schema = value.path("schema").asText();
    String role = value.path("username").asText(), url = value.path("jdbcUrl").asText();
    if (!value.path("projectId").asText().matches("[a-z][a-z0-9-]{5,80}")
        || !value.path("branchId").asText().matches("br-[a-z0-9-]{5,80}")
        || !endpoint.matches("ep-[a-z0-9-]+\\.[a-z0-9-]+\\.aws\\.neon\\.tech")
        || !schema.matches("p2_page_510_20261022")
        || !role.matches("p2_page_510_runtime")
        || value.path("password").asText().isBlank())
      throw new IllegalArgumentException("Explicit isolated Neon assignment is invalid");
    var uri = java.net.URI.create(url.replaceFirst("^jdbc:", ""));
    var query = uri.getRawQuery();
    if (!url.startsWith("jdbc:postgresql://")
        || !endpoint.equals(uri.getHost())
        || uri.getUserInfo() != null
        || !"/neondb".equals(uri.getPath())
        || query == null
        || !java.util.Arrays.asList(query.split("&")).contains("sslmode=verify-full")
        || !java.util.Arrays.asList(query.split("&"))
            .contains("sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory")
        || query.contains("password=")
        || query.contains("user="))
      throw new IllegalArgumentException("Neon assignment transport is invalid");
  }

  @AfterAll
  static void close() {
    if (factory != null) factory.close();
  }

  private static <T> T repo(Class<T> type) {
    return repositories.getRepository(type);
  }

  @BeforeEach
  void setupCreation() throws Exception {
    setup(false);
  }

  private void setup(boolean groupsFixture) throws Exception {
    var document = UiLayoutPageRevisionReproductionTest.page();
    Map<GroupScope, UiLayoutTarget> groupRoots =
        Map.of(new GroupScope(tenant, "app-a", "test", "/pages"), ANCHOR);
    UiLayoutLifecycleInvocationProvider provider =
        (p, v, t) -> {
          if (!v.equals(version))
            throw new UiLayoutLifecycleException(
                UiLayoutLifecycleException.Code.CONTEXT_STALE, "Fixture context changed");
          if (!p.getName().equals(actor)
              || !(groupsFixture
                  ? t.equals(
                      groupRoots.get(new GroupScope(selectedTenant, app, environment, namespace)))
                  : t.equals(SOURCE)))
            throw new UiLayoutLifecycleException(
                UiLayoutLifecycleException.Code.DENIED, "Fixture root unavailable");
          return invocation(t);
        };
    UiLayoutLifecycleAdmission admission =
        (operation, i) -> {
          if (operation == UiLayoutLifecycleOperation.READ_DRAFT && denyDraft)
            throw new UiLayoutLifecycleException(
                UiLayoutLifecycleException.Code.DENIED, "Draft visibility revoked");
        };
    UiLayoutLifecycleStructureValidator structure =
        new UiLayoutLifecycleStructureValidator() {
          public void validateTarget(
              UiLayoutLifecycleInvocation i, UiLayoutTarget t, UiLayoutValidationContext v) {
            v.require(i);
          }

          public void validateAuthoringBaseline(
              UiLayoutLifecycleInvocation i,
              UiLayoutTarget t,
              UiLayoutAuthoringDocumentDescriptor d,
              JsonNode b,
              UiLayoutValidationContext v) {
            UiLayoutNativeStructureChecks.requireBaseline(
                i, t, d, b, UiLayoutPageRevisionReproductionTest.PATCH, v);
          }

          public void validateAuthoringRevision(
              UiLayoutLifecycleInvocation i,
              UiLayoutTarget t,
              UiLayoutAuthoringDocumentDescriptor bd,
              JsonNode b,
              UiLayoutAuthoringDocumentDescriptor cd,
              JsonNode c,
              UiLayoutPatchDocumentDescriptor p,
              JsonNode patch,
              UiLayoutValidationContext v) {
            UiLayoutNativeStructureChecks.requireRevision(i, t, bd, b, cd, c, p, patch, v);
          }

          public void validatePatch(
              UiLayoutLifecycleInvocation i,
              UiLayoutTarget t,
              JsonNode p,
              UiLayoutValidationContext v) {}

          public void validateAssignment(
              UiLayoutLifecycleInvocation i,
              UiLayoutTarget t,
              UiLayoutResolutionCandidate.LayerClass l,
              UiLayoutAudienceSelector s,
              UiLayoutValidationContext v) {}

          public void validateReleaseTargets(
              UiLayoutLifecycleInvocation i, List<UiLayoutTarget> t, UiLayoutValidationContext v) {
            assertThat(t).hasSize(1);
          }
        };
    UiLayoutDraftWorkspaceSource source =
        new UiLayoutDraftWorkspaceSource() {
          public UiLayoutDraftWorkspaceSeed capture(
              UiLayoutLifecycleInvocation i, UiLayoutValidationContext v) {
            if (!groupsFixture) throw new UnsupportedOperationException();
            return new UiLayoutDraftWorkspaceSeed(
                List.of(
                    new UiLayoutDraftWorkspaceSeed.TargetSeed(
                        ANCHOR,
                        new UiLayoutAuthoringDocumentDescriptor(
                            "praxis.navigation-groups.editor", "fixture:groups", "1"),
                        UiLayoutPageRevisionReproductionTest.PATCH,
                        "fixture:groups",
                        groupDocument,
                        metadata(i, groupDocument))));
          }

          public void requirePageChild(
              UiLayoutLifecycleInvocation i,
              UiLayoutTarget p,
              UiLayoutPageChildSeed seed,
              UiLayoutValidationContext v) {
            if (denyDependency)
              throw new UiLayoutLifecycleException(
                  UiLayoutLifecycleException.Code.DENIED, "Dependency revoked");
            var expected =
                UiLayoutPageRevisionReproduction.children(document).get(seed.request().widgetKey());
            assertThat(seed.document()).isEqualTo(expected);
            assertThat(seed.request().source().sourceId())
                .isEqualTo("fixture:" + seed.request().widgetKey());
          }
        };
    UiLayoutBaselineMetadataAdmission metadata =
        (i, t, d, s, b, m) -> {
          if ("last-metadata-context".equals(fault) || "last-metadata-copy".equals(fault)) {
            if (metadataCommitPhase) {
              if ("last-metadata-context".equals(fault)) version = "revoked-context";
              else if (copyCheckedAtCommit) denyCopy = true;
            } else if (!metadataMarkerRegistered) {
              metadataMarkerRegistered = true;
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                      return Integer.MIN_VALUE;
                    }

                    @Override
                    public void beforeCommit(boolean readOnly) {
                      metadataCommitPhase = true;
                    }
                  });
            }
          }
          if (denyDependency)
            throw new UiLayoutLifecycleException(
                UiLayoutLifecycleException.Code.DENIED, "Dependency revoked");
          if ("capture".equals(fault) && !t.equals(SOURCE))
            throw new UiLayoutLifecycleException(
                UiLayoutLifecycleException.Code.DENIED, "Injected metadata failure");
        };
    host =
        new PageCreationHost() {
          public PageCreationContext resolve(java.security.Principal p, String v) {
            if (!p.getName().equals(actor) || !v.equals(version))
              throw new UiLayoutLifecycleException(
                  UiLayoutLifecycleException.Code.CONTEXT_STALE, "Context stale");
            return context(invocation(ANCHOR));
          }

          public PageCreationContext resolveLifecycle(UiLayoutLifecycleInvocation i) {
            if (!i.actorRef().equals(actor)
                || !i.contextVersion().equals(version)
                || !i.tenantId().equals(tenant))
              throw new UiLayoutLifecycleException(
                  UiLayoutLifecycleException.Code.CONTEXT_STALE, "Context stale");
            return context(i);
          }

          public void require(
              PageCreationOperation op, PageCreationContext c, PageSourceReference source) {
            if (grantDelayMillis > 0) {
              delayedGrantCalls++;
              pause(grantDelayMillis);
            }
            if (op == PageCreationOperation.COPY_SOURCE && metadataCommitPhase)
              copyCheckedAtCommit = true;
            if (op == PageCreationOperation.READ_CREATION
                && revokeDraftAtLookupCommit
                && !lookupMarkerRegistered) {
              lookupMarkerRegistered = true;
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                      return Integer.MIN_VALUE;
                    }

                    @Override
                    public void beforeCommit(boolean readOnly) {
                      denyDraft = true;
                    }
                  });
            }
            if ((op == PageCreationOperation.COPY_SOURCE && denyCopy)
                || (op == PageCreationOperation.READ_CREATION && denyLookup)
                || (denyAncestorRead
                    && source instanceof PageSourceReference.Native
                    && (op == PageCreationOperation.READ_SOURCE
                        || op == PageCreationOperation.COPY_SOURCE)))
              throw new UiLayoutLifecycleException(
                  UiLayoutLifecycleException.Code.DENIED, "Grant denied");
          }

          public UiLayoutDraftWorkspaceSeed.TargetSeed captureNative(
              PageCreationContext c, PageSourceReference.Native selected) {
            captures.incrementAndGet();
            var i = c.invocation();
            if ("deadline-commit".equals(fault))
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                      return -10;
                    }

                    @Override
                    public void beforeCommit(boolean readOnly) {
                      deadlineCommitEntered = true;
                      pause(validationBudget.toMillis() + 50);
                    }
                  });
            if ("rollback-only".equals(fault)) {
              var holder =
                  (org.springframework.jdbc.datasource.ConnectionHolder)
                      TransactionSynchronizationManager.getResource(dataSource);
              holder.setRollbackOnly();
            }
            if (revokeAtCommit)
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    public int getOrder() {
                      return -10;
                    }

                    public void beforeCommit(boolean readOnly) {
                      denyCopy = true;
                    }
                  });
            if ("commit".equals(fault))
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    public void beforeCommit(boolean readOnly) {
                      throw new IllegalStateException("Injected commit failure");
                    }
                  });
            var children = new ArrayList<UiLayoutPageChildSeed>();
            UiLayoutPageRevisionReproduction.children(document)
                .forEach(
                    (key, child) ->
                        children.add(
                            new UiLayoutPageChildSeed(
                                new UiLayoutPageChildSource(
                                    key,
                                    new UiLayoutPageChildSource.NativeSource(
                                        "registered-native", "fixture:" + key, "r1")),
                                new UiLayoutAuthoringDocumentDescriptor(
                                    child.path("kind").asText(), "fixture:child", "1"),
                                UiLayoutPageRevisionReproductionTest.PATCH,
                                "fixture:" + key,
                                child,
                                metadata(i, child))));
            return new UiLayoutDraftWorkspaceSeed.TargetSeed(
                SOURCE,
                UiLayoutPageRevisionReproductionTest.DESCRIPTOR,
                UiLayoutPageRevisionReproductionTest.PATCH,
                "fixture:page",
                document,
                metadata(i, document),
                children);
          }

          public void requireNative(
              PageCreationContext c,
              PageSourceReference.Native selected,
              UiLayoutDraftWorkspaceSeed.TargetSeed seed) {
            if (denyDependency)
              throw new UiLayoutLifecycleException(
                  UiLayoutLifecycleException.Code.DENIED, "Producer/domain dependency denied");
            assertThat(selected).isEqualTo(new PageSourceReference.Native("fixture:page", "r1"));
            assertThat(seed.document()).isEqualTo(document);
            assertThat(seed.target()).isEqualTo(SOURCE);
          }
        };
    var hashes = new CanonicalJsonHashService(JSON);
    var named = new NamedParameterJdbcTemplate(dataSource);
    UiLayoutValidationBudgetPolicy budget = (op, i) -> validationBudget;
    application = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
    application.registerBean(ObjectMapper.class, () -> JSON);
    application.registerBean(CanonicalJsonHashService.class, () -> hashes);
    application.registerBean(
        "configNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class, () -> named);
    application.registerBean(
        "uiLayoutLifecycleConfigTransactionOperations",
        org.springframework.transaction.support.TransactionOperations.class,
        () -> transactions);
    if (!groupsFixture) application.registerBean(PageCreationHost.class, () -> host);
    application.registerBean(UiLayoutLifecycleInvocationProvider.class, () -> provider);
    application.registerBean(UiLayoutLifecycleAdmission.class, () -> admission);
    application.registerBean(UiLayoutLifecycleStructureValidator.class, () -> structure);
    application.registerBean(UiLayoutDraftWorkspaceSource.class, () -> source);
    application.registerBean(UiLayoutBaselineMetadataAdmission.class, () -> metadata);
    application.registerBean(UiLayoutValidationBudgetPolicy.class, () -> budget);
    application.registerBean(UiLayoutReleaseValidator.class, () -> (r, m) -> {});
    application.registerBean(
        UiLayoutRevisionAllocationLock.class, () -> new JdbcUiLayoutRevisionAllocationLock(named));
    application.registerBean(
        UiLayoutDraftCreationLock.class, () -> new JdbcUiLayoutDraftCreationLock(named));
    for (Class<?> type :
        List.of(
            UiLayoutDefinitionRepository.class,
            UiLayoutRevisionRepository.class,
            UiLayoutAssignmentRevisionRepository.class,
            UiLayoutDraftRepository.class,
            UiLayoutReleaseRepository.class,
            UiLayoutReleaseMemberRepository.class,
            UiLayoutReleaseReviewRepository.class,
            UiLayoutReleaseApprovalRepository.class,
            UiLayoutReleaseHeadRepository.class,
            UiLayoutReleaseEventRepository.class)) registerRepository(type);
    application.register(
        org.praxisplatform.config.autoconfig.UiLayoutResolutionAutoConfiguration.class);
    application.refresh();
    pages = groupsFixture ? null : application.getBean(PageCreationService.class);
    commands = application.getBean(UiLayoutLifecycleCommandService.class);
    reads = application.getBean(UiLayoutLifecycleReadService.class);
    assertThat(application.getBeansOfType(PageCreationService.class))
        .hasSize(groupsFixture ? 0 : 1);
    assertThat(application.getBeansOfType(UiLayoutLifecycleInvocationProvider.class)).hasSize(1);
    assertThat(application.getBean(UiLayoutLifecycleInvocationProvider.class)).isSameAs(provider);
  }

  private <T> void registerRepository(Class<T> type) {
    application.registerBean(type, () -> repo(type));
  }

  private UiLayoutBaselineMetadataSeed metadata(UiLayoutLifecycleInvocation i, JsonNode document) {
    return UiLayoutMetadataTestFixtures.nativeMetadata(
        document, i.actorRef(), i.administrativeUnit(), i.contextVersion());
  }

  private PageCreationCommand command() {
    return new PageCreationCommand(
        UUID.randomUUID(), new PageSourceReference.Native("fixture:page", "r1"), "Copied page");
  }

  private long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where tenant_id=?", Long.class, tenant);
  }

  @Test
  void repeatedImmutableReadsUseOneSelectOnTheActualConfigConnection() {
    var created = pages.create(principal(), version, command()).receipt();
    var store =
        new PageCatalogStore(
            new NamedParameterJdbcTemplate(dataSource),
            transactions,
            new PageCreationCodec(),
            new CanonicalJsonHashService(JSON));
    long started = System.nanoTime(),
        sql = executedStatements.get(),
        isoCalls = isolationCalls.get(),
        isoTime = isolationNanos.get(),
        autoCalls = autoCommitCalls.get(),
        autoTime = autoCommitNanos.get(),
        opens = connectionCalls.get(),
        openTime = connectionNanos.get();
    transactions.executeWithoutResult(
        status -> {
          for (int index = 0; index < 20; index++) {
            assertThat(store.byPage(context(invocation(ANCHOR)), created.pageId())).isPresent();
          }
        });
    assertThat(executedStatements.get() - sql).isEqualTo(2); // admission SELECT + one ledger SELECT
    assertThat(isolationCalls.get() - isoCalls).isZero();
    System.out.println(
        "P2 immutable-readset 20reads wallMs="
            + (System.nanoTime() - started) / 1_000_000
            + " preparedStatements="
            + (executedStatements.get() - sql)
            + " isolationCalls="
            + (isolationCalls.get() - isoCalls)
            + " isolationMs="
            + (isolationNanos.get() - isoTime) / 1_000_000
            + " autoCommitCalls="
            + (autoCommitCalls.get() - autoCalls)
            + " autoCommitMs="
            + (autoCommitNanos.get() - autoTime) / 1_000_000
            + " connectionOpens="
            + (connectionCalls.get() - opens)
            + " connectionOpenMs="
            + (connectionNanos.get() - openTime) / 1_000_000);
  }

  @Test
  void createsAtomicPageAndReplaysReconcilesWithoutRecapture() {
    var command = command();
    var first = pages.create(principal(), version, command);
    var replay = pages.create(principal(), version, command);
    assertThat(first.created()).isTrue();
    assertThat(replay.created()).isFalse();
    assertThat(replay.receipt()).isEqualTo(first.receipt());
    assertThat(pages.byCreationKey(principal(), version, command.creationKey()))
        .isEqualTo(first.receipt());
    assertThat(captures.get()).isEqualTo(1);
    assertThat(count("ui_page_catalog")).isEqualTo(1);
    assertThat(count("ui_page_creation_evidence")).isEqualTo(1);
    assertThat(count("ui_layout_draft")).isEqualTo(1);
    assertThat(count("ui_layout_baseline_metadata_evidence")).isEqualTo(1);
    assertThat(count("ui_layout_release_head")).isZero();
    assertThat(count("ui_layout_assignment_revision")).isZero();
    var workspace =
        reads.draft(principal(), version, first.receipt().target(), first.receipt().draftId());
    assertThat(workspace.targets()).hasSize(1);
    assertThatThrownBy(
            () ->
                pages.create(
                    principal(),
                    version,
                    new PageCreationCommand(command.creationKey(), command.source(), "Different")))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE));
  }

  @Test
  void concurrentSameCommandHasOneDurableIdentity() throws Exception {
    var command = command();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                assertThat(start.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return pages.create(principal(), version, command);
              });
      var b =
          pool.submit(
              () -> {
                assertThat(start.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return pages.create(principal(), version, command);
              });
      start.countDown();
      assertThat(a.get(60, java.util.concurrent.TimeUnit.SECONDS).receipt().pageId())
          .isEqualTo(b.get(60, java.util.concurrent.TimeUnit.SECONDS).receipt().pageId());
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    assertThat(count("ui_page_catalog")).isEqualTo(1);
    assertThat(count("ui_layout_draft")).isEqualTo(1);
    assertThat(captures.get()).isEqualTo(1);
  }

  @Test
  void injectedCommitAndRevocationRollbackEverythingFromFreshConnection() {
    for (boolean revoke : List.of(false, true)) {
      fault = revoke ? null : "commit";
      revokeAtCommit = revoke;
      denyCopy = false;
      assertThatThrownBy(() -> pages.create(principal(), version, command()))
          .isInstanceOf(UiLayoutLifecycleException.class);
      assertThat(count("ui_page_catalog")).isZero();
      assertThat(count("ui_page_creation_evidence")).isZero();
      assertThat(count("ui_layout_draft")).isZero();
      assertThat(count("ui_layout_baseline_metadata_evidence")).isZero();
    }
  }

  @Test
  void everyDurableWriteAndSilentRollbackOnlyRollBackTheCompleteCreation() {
    for (String table :
        List.of(
            "ui_page_catalog",
            "ui_page_creation_evidence",
            "ui_layout_draft",
            "ui_layout_baseline_metadata_evidence")) {
      sqlFault.set(table);
      assertThatThrownBy(() -> pages.create(principal(), version, command()))
          .isInstanceOf(UiLayoutLifecycleException.class);
      assertThat(sqlFault.get()).as("Fault reached after insert into %s", table).isNull();
      assertNoCreationFromIndependentConnection();
    }
    fault = "rollback-only";
    assertThatThrownBy(() -> pages.create(principal(), version, command()))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertNoCreationFromIndependentConnection();
  }

  private void assertNoCreationFromIndependentConnection() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    for (String table :
        List.of(
            "ui_page_catalog",
            "ui_page_creation_evidence",
            "ui_layout_draft",
            "ui_layout_baseline_metadata_evidence",
            "ui_layout_release_head",
            "ui_layout_assignment_revision")) {
      assertThat(count(table)).as(table).isZero();
    }
  }

  @Test
  void concurrentDifferentCommandsHaveOneWinnerAndOneConflict() throws Exception {
    var original = command();
    var divergent = new PageCreationCommand(original.creationKey(), original.source(), "Divergent");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      var results = new ArrayList<java.util.concurrent.Future<Object>>();
      for (var request : List.of(original, divergent))
        results.add(
            pool.submit(
                () -> {
                  assertThat(start.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                  try {
                    return pages.create(principal(), version, request);
                  } catch (UiLayoutLifecycleException failure) {
                    return failure.getCode();
                  }
                }));
      start.countDown();
      var outcomes =
          List.of(
              results.get(0).get(60, java.util.concurrent.TimeUnit.SECONDS),
              results.get(1).get(60, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(outcomes.stream().filter(PageCreationService.CreationOutcome.class::isInstance))
          .hasSize(1);
      assertThat(outcomes).contains(UiLayoutLifecycleException.Code.INVALID_STATE);
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    assertThat(count("ui_page_catalog")).isEqualTo(1);
    assertThat(count("ui_layout_draft")).isEqualTo(1);
  }

  @Test
  void creationLookupDoesNotRequireCurrentAccessToHistoricalSource() {
    var request = command();
    var original = pages.create(principal(), version, request).receipt();
    denyAncestorRead = true;
    assertThat(pages.byCreationKey(principal(), version, request.creationKey()))
        .isEqualTo(original);
    assertThatThrownBy(() -> pages.create(principal(), version, request))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
  }

  @Test
  void existingPageCanStartNewDraftUnderNewAuthorizedActorAndContext() {
    var created = pages.create(principal(), version, command()).receipt();
    actor = "bob";
    version = "c2";
    var draft =
        commands.createDraft(principal(), version, created.target(), UUID.randomUUID().toString());
    assertThat(draft.draft().draftRef()).isNotEqualTo(created.draftId().toString());
    assertThat(count("ui_layout_draft")).isEqualTo(2);
    assertThat(captures.get()).isEqualTo(1);
  }

  private void assertReleasedDraftIsSealed(UUID draftId) {
    for (String change :
        List.of(
            "draft_document = jsonb_set(draft_document,'{tampered}','true'::jsonb)",
            "draft_etag = gen_random_uuid()",
            "state = 'DRAFT'")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update ui_layout_draft set " + change + " where id = ? and tenant_id = ?",
                      draftId,
                      tenant))
          .hasRootCauseInstanceOf(java.sql.SQLException.class)
          .rootCause()
          .hasMessageContaining("ui layout revisions and releases are immutable");
    }
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from ui_layout_draft where id = ? and tenant_id = ?", draftId, tenant))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(() -> jdbc.execute("truncate ui_layout_draft"))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
    System.out.println(
        "P2 released-draft runtime UPDATE body/etag/state rejected by immutable trigger;"
            + " DELETE/TRUNCATE denied");
  }

  @Test
  void publishedHistoricalCopyUsesFixedReleaseWithoutAncestorReadGrant() {
    var original = pages.create(principal(), version, command()).receipt();
    var workspace = reads.draft(principal(), version, original.target(), original.draftId());
    var evolvedDocument = (ObjectNode) workspace.targets().getFirst().working().document();
    ((ObjectNode) evolvedDocument.at("/config/definition/widgets/0/definition/inputs/config"))
        .put("title", "Published evolved form");
    var revision =
        commands.createRevision(
            principal(),
            version,
            original.target(),
            original.draftId(),
            workspace.draft().draftEtag(),
            new UiLayoutLifecycleCommandService.RevisionInput(
                UUID.randomUUID(),
                original.target(),
                evolvedDocument.toString(),
                "Selected version"));
    var currentReceipt = pages.byCreationKey(principal(), version, original.creationKey());
    assertThat(currentReceipt.pageId()).isEqualTo(original.pageId());
    assertThat(currentReceipt.draftEtag())
        .isEqualTo("\"" + revision.nextDraft().draft().draftEtag() + "\"");
    assertThat(currentReceipt.draftEtag()).isNotEqualTo(original.draftEtag());
    var assigned =
        commands.createAssignment(
            principal(),
            version,
            original.target(),
            original.draftId(),
            revision.nextDraft().draft().draftEtag(),
            new UiLayoutLifecycleCommandService.AssignmentInput(
                UUID.randomUUID(),
                original.target(),
                "TENANT",
                new UiLayoutAudienceSelectorCommand(tenant, null, null, null, null, null),
                (short) 0,
                "page"));
    var frozen =
        transactions.execute(
            status -> {
              var result =
                  commands.freezeRelease(
                      principal(),
                      version,
                      original.target(),
                      original.draftId(),
                      assigned.nextDraft().draft().draftEtag(),
                      UUID.randomUUID());
              var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
              entityManager.flush();
              entityManager.flush();
              entityManager.clear();
              assertThat(entityManager.find(UiLayoutDraft.class, original.draftId()).getState())
                  .isEqualTo("RELEASED");
              return result;
            });
    UUID releaseId = UUID.fromString(frozen.release().releaseRef());
    assertReleasedDraftIsSealed(original.draftId());
    var selected =
        new PageSourceReference.Published(
            original.pageId(), releaseId, UUID.fromString(revision.revision().revisionRef()));
    assertThatThrownBy(
            () ->
                pages.create(
                    principal(),
                    version,
                    new PageCreationCommand(UUID.randomUUID(), selected, "Unpublished")))
        .isInstanceOf(UiLayoutLifecycleException.class);
    var submitted =
        commands.submit(
            principal(), version, original.target(), releaseId, frozen.review().reviewEtag());
    commands.approve(
        principal(),
        version,
        original.target(),
        releaseId,
        submitted.reviewEtag(),
        "Approved source");
    var head =
        commands.publish(
            principal(),
            version,
            original.target(),
            UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish(),
            releaseId,
            "Publish source");
    commands.withdraw(
        principal(),
        version,
        original.target(),
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(head.headEtag()),
        "Historical source remains copyable");
    actor = "bob";
    version = "c2";
    denyAncestorRead = true;
    long started = System.nanoTime(),
        statementsBefore = executedStatements.get(),
        sqlBefore = statementNanos.get();
    PageCreationReceipt copied;
    try {
      copied =
          pages
              .create(
                  principal(),
                  version,
                  new PageCreationCommand(UUID.randomUUID(), selected, "Independent copy"))
              .receipt();
    } finally {
      System.out.println(
          "P2 published-copy measurement elapsedMs="
              + (System.nanoTime() - started) / 1_000_000
              + " jdbcStatements="
              + (executedStatements.get() - statementsBefore)
              + " jdbcExecutionMs="
              + (statementNanos.get() - sqlBefore) / 1_000_000
              + " hostBudgetMs="
              + validationBudget.toMillis());
    }
    var copiedWorkspace = reads.draft(principal(), version, copied.target(), copied.draftId());
    assertThat(
            copiedWorkspace
                .targets()
                .getFirst()
                .baseline()
                .document()
                .at("/config/definition/widgets/0/definition/inputs/config/title")
                .asText())
        .isEqualTo("Published evolved form");
    assertThat(
            workspace
                .targets()
                .getFirst()
                .baseline()
                .document()
                .at("/config/definition/widgets/0/definition/inputs/config/title")
                .asText())
        .isEqualTo("Original");
    assertThat(copied.pageId()).isNotEqualTo(original.pageId());
    assertThat(copied.source()).isEqualTo(selected);
    assertThat(captures.get()).isEqualTo(1);
    var receiptText = new PageCreationCodec().tree(copied).toString();
    assertThat(receiptText).doesNotContain("fixture:page", "original", "identityMap");
    denyCopy = true;
    assertThatThrownBy(
            () ->
                pages.create(
                    principal(),
                    version,
                    new PageCreationCommand(UUID.randomUUID(), selected, "Denied selected copy")))
        .isInstanceOf(UiLayoutLifecycleException.class);
    denyCopy = false;
    denyDependency = true;
    assertThatThrownBy(
            () ->
                commands.createDraft(
                    principal(), version, copied.target(), UUID.randomUUID().toString()))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test
  void groupsUseExistingLifecycleWithoutPageHostAndRejectMalformedSeedsAndRevokedMetadata()
      throws Exception {
    application.close();
    groupDocument =
        (ObjectNode)
            JSON.readTree(
                """
                {"kind":"praxis.navigation-groups.editor","version":1,"config":{"groups":[
                  {"id":"22222222-2222-4222-8222-222222222222","label":"Operations","icon":"work","order":2},
                  {"id":"33333333-3333-4333-8333-333333333333","label":"Library","icon":"book","order":1}
                ]}}
                """);
    setup(true);
    var valid = groupDocument.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) groupDocument.at("/config/groups"))
        .add(groupDocument.at("/config/groups/0").deepCopy());
    assertThatThrownBy(() -> commands.createDraft(principal(), version, ANCHOR, "invalid-seed"))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertNoCreationFromIndependentConnection();
    groupDocument = valid;
    fault = "last-metadata-context";
    assertThatThrownBy(
            () -> commands.createDraft(principal(), version, ANCHOR, "last-metadata-revoked"))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE));
    assertThat(metadataCommitPhase).isTrue();
    assertNoCreationFromIndependentConnection();
    fault = null;
    version = "c1";
    metadataCommitPhase = false;
    metadataMarkerRegistered = false;
    var draft = commands.createDraft(principal(), version, ANCHOR, "groups-native");
    UUID draftId = UUID.fromString(draft.draft().draftRef());
    assertThat(
            commands.createDraft(principal(), version, ANCHOR, "groups-native").draft().draftRef())
        .isEqualTo(draftId.toString());
    for (String dimension : List.of("tenant", "app", "environment", "namespace")) {
      selectedTenant = dimension.equals("tenant") ? "unregistered-tenant" : tenant;
      app = dimension.equals("app") ? "unregistered-app" : "app-a";
      environment = dimension.equals("environment") ? "unregistered-env" : "test";
      namespace = dimension.equals("namespace") ? "/unregistered" : "/pages";
      assertThatThrownBy(() -> commands.createDraft(principal(), version, ANCHOR, "outside-scope"))
          .isInstanceOf(UiLayoutLifecycleException.class);
    }
    selectedTenant = tenant;
    app = "app-a";
    environment = "test";
    namespace = "/pages";
    assertThat(count("ui_layout_draft")).isEqualTo(1);
    var alternative = new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString());
    assertThatThrownBy(
            () -> commands.createDraft(principal(), version, alternative, "alternate-root"))
        .isInstanceOf(UiLayoutLifecycleException.class);
    var candidate = valid.deepCopy();
    var groups = (com.fasterxml.jackson.databind.node.ArrayNode) candidate.at("/config/groups");
    groups.remove(0);
    ((ObjectNode) groups.get(0)).put("label", "Renamed library").put("order", 0);
    groups
        .addObject()
        .put("id", UUID.randomUUID().toString())
        .put("label", "New group")
        .put("order", 1)
        .put("icon", "home");
    var revision =
        commands.createRevision(
            principal(),
            version,
            ANCHOR,
            draftId,
            draft.draft().draftEtag(),
            new UiLayoutLifecycleCommandService.RevisionInput(
                UUID.randomUUID(), ANCHOR, candidate.toString(), "Replace groups"));
    assertThat(
            reads
                .draft(principal(), version, ANCHOR, draftId)
                .targets()
                .getFirst()
                .working()
                .document())
        .isEqualTo(candidate);
    denyDependency = true;
    assertThatThrownBy(
            () ->
                commands.createRevision(
                    principal(),
                    version,
                    ANCHOR,
                    draftId,
                    revision.nextDraft().draft().draftEtag(),
                    new UiLayoutLifecycleCommandService.RevisionInput(
                        UUID.randomUUID(), ANCHOR, valid.toString(), "Revoked icon/source")))
        .isInstanceOf(UiLayoutLifecycleException.class);
    denyDependency = false;
    var assignment =
        commands.createAssignment(
            principal(),
            version,
            ANCHOR,
            draftId,
            revision.nextDraft().draft().draftEtag(),
            new UiLayoutLifecycleCommandService.AssignmentInput(
                UUID.randomUUID(),
                ANCHOR,
                "TENANT",
                new UiLayoutAudienceSelectorCommand(tenant, null, null, null, null, null),
                (short) 0,
                "groups"));
    denyDependency = true;
    assertThatThrownBy(
            () ->
                commands.freezeRelease(
                    principal(),
                    version,
                    ANCHOR,
                    draftId,
                    assignment.nextDraft().draft().draftEtag(),
                    UUID.randomUUID()))
        .isInstanceOf(UiLayoutLifecycleException.class);
    denyDependency = false;
    var frozen =
        commands.freezeRelease(
            principal(),
            version,
            ANCHOR,
            draftId,
            assignment.nextDraft().draft().draftEtag(),
            UUID.randomUUID());
    UUID release = UUID.fromString(frozen.release().releaseRef());
    var submitted =
        commands.submit(principal(), version, ANCHOR, release, frozen.review().reviewEtag());
    commands.approve(
        principal(), version, ANCHOR, release, submitted.reviewEtag(), "Approved groups");
    assertThatThrownBy(
            () ->
                commands.publish(
                    principal(),
                    version,
                    ANCHOR,
                    UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish(),
                    release,
                    "Still gated"))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    assertThat(count("ui_layout_release_head")).isZero();
    assertThat(count("ui_page_catalog")).isZero();
  }

  @Test
  void lookupRechecksDraftVisibilityAtCommitWithoutRequiringTheOldSource() {
    var request = command();
    var original = pages.create(principal(), version, request).receipt();
    denyAncestorRead = true;
    assertThat(pages.byCreationKey(principal(), version, request.creationKey()))
        .isEqualTo(original);
    revokeDraftAtLookupCommit = true;
    assertThatThrownBy(() -> pages.byCreationKey(principal(), version, request.creationKey()))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
    assertThat(denyDraft).isTrue();
    assertThat(count("ui_page_catalog")).isEqualTo(1);
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }

  @Test
  void lateSelectedCopyRevocationAfterFinalMetadataValidationRollsBack() {
    fault = "last-metadata-copy";
    assertThatThrownBy(() -> pages.create(principal(), version, command()))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
    assertThat(metadataCommitPhase).isTrue();
    assertThat(copyCheckedAtCommit).isTrue();
    assertThat(denyCopy).isTrue();
    assertNoCreationFromIndependentConnection();
  }

  @Test
  void repeatedGrantsAndCommitExtensionsCannotRenewTheOuterDeadline() {
    validationBudget = Duration.ofSeconds(5);
    grantDelayMillis = 2100;
    assertThatThrownBy(() -> pages.create(principal(), version, command()))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    assertThat(delayedGrantCalls).isGreaterThanOrEqualTo(2);
    assertThat(captures.get()).isZero();
    assertNoCreationFromIndependentConnection();
    validationBudget = Duration.ofSeconds(15);
    grantDelayMillis = 0;
    fault = "deadline-commit";
    assertThatThrownBy(() -> pages.create(principal(), version, command()))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    assertThat(deadlineCommitEntered).isTrue();
    assertNoCreationFromIndependentConnection();
  }

  @Test
  void committedButLateResponsesRemainUnacknowledgedAndReconcileWithTheSameKey() {
    var request = command();
    validationBudget = Duration.ofSeconds(15);
    long delayedBefore = delayedCommitResponses.get();
    commitResponseDelayMillis.set(15_050);
    assertThatThrownBy(() -> pages.create(principal(), version, request))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    assertThat(delayedCommitResponses.get()).isEqualTo(delayedBefore + 1);
    // The JDBC commit already succeeded: this is uncertain delivery, never rollback evidence.
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "select (select count(*) from ui_page_catalog where tenant_id=?),(select count(*)"
                    + " from ui_page_creation_evidence where tenant_id=?),(select count(*) from"
                    + " ui_layout_draft where tenant_id=?),(select count(*) from"
                    + " ui_layout_baseline_metadata_evidence where tenant_id=?)")) {
      for (int index = 1; index <= 4; index++) statement.setString(index, tenant);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        for (int index = 1; index <= 4; index++) assertThat(result.getLong(index)).isEqualTo(1);
      }
    } catch (java.sql.SQLException exception) {
      throw new AssertionError(exception);
    }
    validationBudget = Duration.ofSeconds(40);
    var recovered = pages.byCreationKey(principal(), version, request.creationKey());
    var replay = pages.create(principal(), version, request);
    assertThat(replay.created()).isFalse();
    assertThat(replay.receipt()).isEqualTo(recovered);
    assertThat(captures.get()).isEqualTo(1);

    validationBudget = Duration.ofSeconds(10);
    commitResponseDelayMillis.set(10_050);
    assertThatThrownBy(() -> pages.byCreationKey(principal(), version, request.creationKey()))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    assertThat(delayedCommitResponses.get()).isEqualTo(delayedBefore + 2);
    validationBudget = Duration.ofSeconds(40);
    assertThat(pages.byCreationKey(principal(), version, request.creationKey()))
        .isEqualTo(recovered);
    assertThat(count("ui_page_catalog")).isEqualTo(1);
    assertThat(captures.get()).isEqualTo(1);
    System.out.println(
        "P2 late real COMMIT response: create and lookup returned SOURCE_UNAVAILABLE; one durable"
            + " identity reconciled with the original creationKey, no recapture");
  }

  @Test
  void participatingCreationAndLookupKeepValidationUntilTheOuterTransactionCompletes() {
    var request = command();
    transactions.executeWithoutResult(
        status -> {
          var provisional = pages.create(principal(), version, request).receipt();
          assertThat(pages.byCreationKey(principal(), version, request.creationKey()))
              .isEqualTo(provisional);
          status.setRollbackOnly();
        });
    assertNoCreationFromIndependentConnection();
    assertThat(captures.get()).isEqualTo(1);
    captures.set(0); // A rolled-back creation has no durable capture to reuse.
    var committed =
        transactions.execute(
            status -> {
              var provisional = pages.create(principal(), version, request).receipt();
              assertThat(pages.byCreationKey(principal(), version, request.creationKey()))
                  .isEqualTo(provisional);
              return provisional;
            });
    assertThat(count("ui_page_catalog")).isEqualTo(1);
    assertThat(pages.byCreationKey(principal(), version, request.creationKey()))
        .isEqualTo(committed);
    assertThat(captures.get()).isEqualTo(1);

    validationBudget = Duration.ofSeconds(15);
    long delayedBefore = delayedCommitResponses.get();
    commitResponseDelayMillis.set(15_050);
    assertThatThrownBy(
            () ->
                transactions.execute(
                    status -> {
                      var provisional = pages.create(principal(), version, request);
                      assertThat(provisional.created()).isFalse();
                      assertThat(pages.byCreationKey(principal(), version, request.creationKey()))
                          .isEqualTo(provisional.receipt());
                      return provisional;
                    }))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    assertThat(delayedCommitResponses.get()).isEqualTo(delayedBefore + 1);
    validationBudget = Duration.ofSeconds(40);
    assertThat(pages.byCreationKey(principal(), version, request.creationKey()))
        .isEqualTo(committed);
    assertThat(count("ui_page_catalog")).isEqualTo(1);
    assertThat(captures.get()).isEqualTo(1);
    System.out.println(
        "P2 outer REQUIRED transaction: create and lookup commit together; late real"
            + " COMMIT response remains uncertain and reconciles the same identity");
  }

  @Test
  void crossApplicationKnownUuidAndRevokedLookupDoNotExposeContent() {
    var request = command();
    var created = pages.create(principal(), version, request).receipt();
    app = "app-b";
    assertThatThrownBy(() -> reads.draft(principal(), version, created.target(), created.draftId()))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertThatThrownBy(() -> pages.byCreationKey(principal(), version, request.creationKey()))
        .isInstanceOf(UiLayoutLifecycleException.class);
    app = "app-a";
    denyLookup = true;
    assertThatThrownBy(() -> pages.byCreationKey(principal(), version, request.creationKey()))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
  }
}
