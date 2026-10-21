package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;
import javax.net.ssl.SSLSocket;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.repository.*;
import org.praxisplatform.rules.contract.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.DefaultJpaContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit remote gate: missing assignment fails, never skips or starts an embedded database. */
@Tag("neon")
class DomainRuleSnapshotPublicationNeonIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static PGSimpleDataSource runtime;
  private static AnnotationConfigApplicationContext context;
  private static TransactionTemplate transactions;
  private static DomainRuleSnapshotService snapshots;
  private static DomainRuleRolloutService rollouts;
  private static DomainRuleRolloutPolicyService policies;
  private static DomainRuleSnapshotRepository snapshotRows;
  private static DomainRuleSnapshotHeadRepository heads;
  private static DomainRuleSnapshotRolloutRepository rolloutRows;
  private static DomainRuleSnapshotEventRepository events;
  private static DomainRuleEntityRefresh refresh;
  private static DomainRuleSnapshotScopeLock scopeLock;

  @BeforeAll static void connectOnlyToTheExplicitlyAssignedChildDatabase() throws Exception {
    var owner = assignedSource("OWNER");
    runtime = assignedSource("RUNTIME");
    attest(owner, required("PRAXIS_CONFIG_G2_NEON_EXPECTED_OWNER_ROLE"), true);
    attest(runtime, required("PRAXIS_CONFIG_G2_NEON_EXPECTED_RUNTIME_ROLE"), false);
    String branch = required("PRAXIS_CONFIG_G2_NEON_EXPECTED_BRANCH_ID");
    var attestation = JSON.readTree(Files.readString(Path.of(required("PRAXIS_CONFIG_G2_NEON_ATTESTATION_FILE"))));
    assertThat(attestation.path("branchId").asText()).isEqualTo(branch);
    assertThat(attestation.path("endpoint").asText()).isEqualTo(required("PRAXIS_CONFIG_G2_NEON_EXPECTED_HOST"));
    // Only official migrations, only the assigned existing database. No CREATE DATABASE/ROLE or cleanup.
    var migration = Flyway.configure().dataSource(owner).locations("classpath:db/migration").load();
    migration.migrate();
    migration.validate();
    assertThat(new JdbcTemplate(owner).queryForObject(
        "select count(*) from flyway_schema_history where version='69' and success", Integer.class)).isEqualTo(1);
    context = new AnnotationConfigApplicationContext(JpaConfiguration.class);
    transactions = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
    transactions.setTimeout(45);
    snapshotRows = context.getBean(DomainRuleSnapshotRepository.class);
    heads = context.getBean(DomainRuleSnapshotHeadRepository.class);
    rolloutRows = context.getBean(DomainRuleSnapshotRolloutRepository.class);
    events = context.getBean(DomainRuleSnapshotEventRepository.class);
    var manager = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
    refresh = new DomainRuleEntityRefresh(new DefaultJpaContext(Set.of(manager)));
    scopeLock = new JdbcDomainRuleSnapshotScopeLock(new NamedParameterJdbcTemplate(runtime));
    policies = new DomainRuleRolloutPolicyService(context.getBean(DomainRuleRolloutPolicyRepository.class),
        context.getBean(DomainRuleRolloutPolicyHeadRepository.class), context.getBean(DomainRuleRolloutPolicyEventRepository.class),
        heads, rolloutRows, Clock.systemUTC(), scopeLock, refresh);
    rollouts = new DomainRuleRolloutService(context.getBean(DomainRuleRolloutPolicyRepository.class), rolloutRows,
        context.getBean(DomainRuleCandidateProbeRepository.class), context.getBean(DomainRuleSnapshotRolloutEventRepository.class),
        snapshotRows, heads, policies, JSON, Clock.systemUTC(), DomainRuleImplementationCatalog.denyAll(), scopeLock, refresh);
    snapshots = snapshotService(rollouts);
  }

  @AfterAll static void closeOnlyTheTestContext() { if (context != null) context.close(); }

  @Test void realLifecycleConcurrencyAndAtomicityUnderRuntimePrivileges() throws Exception {
    Fixture f = fixture();
    var published = tx(() -> snapshots.publish(f.request(1), f.tenant(), "g2", null, "*", "publisher"));
    String key = published.snapshot().snapshotKey();
    String bytes = tx(() -> snapshotRows.findByTenantIdAndEnvironmentAndSnapshotKey(f.tenant(), "g2", key).orElseThrow().getSnapshotPayload());
    assertThat(tx(() -> heads.findByTenantIdAndEnvironmentAndRuleSetKey(f.tenant(), "g2", f.ruleSet()))).isEmpty();
    assertThat(new JdbcTemplate(runtime).queryForObject(
        "select count(*) from domain_rule_snapshot_event where tenant_id=? and event_type='PUBLISHED' and activation_revision is null and head_etag is null",
        Integer.class, f.tenant())).isEqualTo(1);
    assertObservationBlocked(f, published, 1);
    var policy = governPolicy(f);
    // Two first-rollout creators serialize even when no head row exists.
    var creations = race(() -> tx(() -> rollouts.create(new DomainRuleRolloutCreateRequest(key, null), f.operator(), null, "*")));
    assertThat(creations.stream().filter(DomainRuleRolloutResponse.class::isInstance).count()).isEqualTo(1);
    assertThat(creations.stream().filter(DomainRuleSnapshotControlPlaneException.class::isInstance).count()).isEqualTo(1);
    var rollout = (DomainRuleRolloutResponse) creations.stream().filter(DomainRuleRolloutResponse.class::isInstance).findFirst().orElseThrow();
    assertThat(rollout.expectedActiveSnapshotKey()).isNull();
    assertThat(rollout.expectedHeadEtag()).isNull();
    assertThatThrownBy(() -> tx(() -> snapshots.activatePublished(key, "operator", f.tenant(), "g2", null, "*", rollout.rolloutId())))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class).hasMessageContaining("quorum");
    preload(f, published, rollout.rolloutId());
    // A failure after head insertion must roll back head/event and leave the rollout uncompleted.
    var failing = snapshotService(new DomainRuleSnapshotActivationGate() {
      @Override public void requireAllowed(UUID id, DomainRuleSnapshot target, DomainRuleSnapshotHead head, String actor) {
        rollouts.requireAllowed(id, target, head, actor);
      }
      @Override public void activationCompleted(UUID id, DomainRuleSnapshot target, String actor) {
        throw new IllegalStateException("injected completion failure");
      }
    });
    assertThatThrownBy(() -> tx(() -> failing.activatePublished(key, "operator", f.tenant(), "g2", null, "*", rollout.rolloutId())))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("injected completion");
    assertThat(tx(() -> heads.findByTenantIdAndEnvironmentAndRuleSetKey(f.tenant(), "g2", f.ruleSet()))).isEmpty();
    assertThat(tx(() -> rolloutRows.findById(rollout.rolloutId()).orElseThrow().getStatus())).isEqualTo("PREPARING");
    // Two bootstrap operators: one selection, one stale-absence response, no duplicate event.
    var attempts = race(() -> tx(() -> snapshots.activatePublished(key, "operator", f.tenant(), "g2", null, "*", rollout.rolloutId())));
    assertThat(attempts.stream().filter(DomainRuleSnapshotActivationResponse.class::isInstance).count()).isEqualTo(1);
    assertThat(attempts.stream().filter(DomainRuleSnapshotControlPlaneException.class::isInstance).count()).isEqualTo(1);
    var active = (DomainRuleSnapshotActivationResponse) attempts.stream().filter(DomainRuleSnapshotActivationResponse.class::isInstance).findFirst().orElseThrow();
    assertThat(active.activationRevision()).isEqualTo(1);
    assertThat(tx(() -> rolloutRows.findById(rollout.rolloutId()).orElseThrow().getStatus())).isEqualTo("ACTIVATED");
    assertThat(tx(() -> snapshotRows.findByTenantIdAndEnvironmentAndSnapshotKey(f.tenant(), "g2", key).orElseThrow().getSnapshotPayload())).isEqualTo(bytes);
    assertObservationAccepted(f, published, 1);
    preloadedContextMustRecheckAfterConcurrentSelection(f, active);
    concurrentPublicationIsAppendOnly(f);
    assertThat(policy.policy().enforcementMode()).isEqualTo("REQUIRED");
    System.out.println("CONFIG_G2_NEON_CASE=publication-bootstrap-quorum-atomicity-concurrency,PASS");
  }

  @Test void initialCandidatesPolicyChangesAndPublicationVersusBootstrapRemainSerialized() throws Exception {
    Fixture f = fixture();
    var nextVersion = new java.util.concurrent.atomic.AtomicInteger();
    var initial = race(() -> tx(() -> snapshots.publish(f.request(nextVersion.incrementAndGet()),
        f.tenant(), "g2", null, "*", "publisher")));
    assertThat(initial).allMatch(DomainRuleSnapshotStoredResponse.class::isInstance);
    var candidates = initial.stream().map(DomainRuleSnapshotStoredResponse.class::cast).toList();
    assertThat(candidates).extracting(value -> value.snapshot().publicationRevision()).containsExactlyInAnyOrder(1, 2);
    var candidate = candidates.stream().max(java.util.Comparator.comparingInt(value -> value.snapshot().publicationRevision())).orElseThrow();
    governPolicy(f);
    var replacement = tx(() -> policies.create(new DomainRuleRolloutPolicyCreateRequest(f.ruleSet(), "replacement-g2", "REQUIRED", 1,
        BigDecimal.ONE, true, 120L, 600L), new DomainRuleGovernancePrincipal(f.tenant(), "policy-author", "g2")));
    tx(() -> policies.approve(replacement.policy().policyId(), new DomainRuleGovernancePrincipal(f.tenant(), "policy-reviewer", "g2")));
    var policyRace = race(
        () -> tx(() -> rollouts.create(new DomainRuleRolloutCreateRequest(candidate.snapshot().snapshotKey(), null), f.operator(), null, "*")),
        () -> tx(() -> policies.activate(replacement.policy().policyId(), quote(replacement.headEtag()), f.operator())));
    assertThat(policyRace.getFirst()).isInstanceOf(DomainRuleRolloutResponse.class);
    assertThat(policyRace.get(1)).isInstanceOfAny(DomainRuleRolloutPolicyMutationResponse.class, DomainRuleSnapshotControlPlaneException.class);
    var rollout = (DomainRuleRolloutResponse) policyRace.getFirst();
    UUID currentPolicy = tx(() -> context.getBean(DomainRuleRolloutPolicyRepository.class)
        .findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(f.tenant(), "g2", f.ruleSet()).orElseThrow().getId());
    assertThat(tx(() -> rolloutRows.findById(rollout.rolloutId()).orElseThrow().getPolicyId())).isEqualTo(currentPolicy);
    preload(f, candidate, rollout.rolloutId());
    var selectionRace = race(
        () -> tx(() -> snapshots.activatePublished(candidate.snapshot().snapshotKey(), "operator", f.tenant(), "g2", null, "*", rollout.rolloutId())),
        () -> tx(() -> snapshots.publish(f.request(3), f.tenant(), "g2", null, "*", "publisher")));
    assertThat(selectionRace.getFirst()).isInstanceOf(DomainRuleSnapshotActivationResponse.class);
    Object publication = selectionRace.get(1);
    if (publication instanceof DomainRuleSnapshotControlPlaneException stale)
      assertThat(stale.status()).isEqualTo(org.springframework.http.HttpStatus.PRECONDITION_FAILED);
    else assertThat(publication).isInstanceOf(DomainRuleSnapshotStoredResponse.class);
    var selected = tx(() -> heads.findByTenantIdAndEnvironmentAndRuleSetKey(f.tenant(), "g2", f.ruleSet()).orElseThrow());
    assertThat(selected.getActivationRevision()).isEqualTo(1);
    assertThat(tx(() -> snapshotRows.findById(selected.getActiveSnapshotId()).orElseThrow().getSnapshotKey()))
        .isEqualTo(candidate.snapshot().snapshotKey());
    for (var immutable : candidates) {
      var stored = tx(() -> snapshots.findSnapshot(f.tenant(), "g2", immutable.snapshot().snapshotKey()).orElseThrow());
      assertThat(stored.snapshotContentHash()).isEqualTo(immutable.snapshotContentHash());
    }
    policyReplacementFailureAfterFlushMustRestoreAuthority(f);
    System.out.println("CONFIG_G2_NEON_CASE=initial-candidates-policy-publication-bootstrap-races,PASS");
  }

  private static void policyReplacementFailureAfterFlushMustRestoreAuthority(Fixture f) {
    var policyRows = context.getBean(DomainRuleRolloutPolicyRepository.class);
    var policyHeads = context.getBean(DomainRuleRolloutPolicyHeadRepository.class);
    var before = tx(() -> policyHeads.findByTenantIdAndEnvironmentAndRuleSetKey(f.tenant(), "g2", f.ruleSet()).orElseThrow());
    var replacement = tx(() -> policies.create(new DomainRuleRolloutPolicyCreateRequest(f.ruleSet(), "rollback-proof-g2", "REQUIRED", 1,
        BigDecimal.ONE, true, 120L, 600L), new DomainRuleGovernancePrincipal(f.tenant(), "policy-author", "g2")));
    tx(() -> policies.approve(replacement.policy().policyId(), new DomainRuleGovernancePrincipal(f.tenant(), "policy-reviewer", "g2")));
    var eventRows = context.getBean(DomainRuleRolloutPolicyEventRepository.class);
    int count = tx(() -> eventRows.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByCreatedAtAsc(f.tenant(), "g2", f.ruleSet()).size());
    var failingRows = (DomainRuleRolloutPolicyRepository) java.lang.reflect.Proxy.newProxyInstance(
        DomainRuleSnapshotPublicationNeonIT.class.getClassLoader(), new Class<?>[] {DomainRuleRolloutPolicyRepository.class}, (proxy, method, arguments) -> {
          if ("save".equals(method.getName()) && arguments[0] instanceof DomainRuleRolloutPolicy target
              && replacement.policy().policyId().equals(target.getId()) && Boolean.TRUE.equals(target.getActive())) {
            // JDBC shares the Config transaction but does not auto-flush the new managed target.
            assertThat(new JdbcTemplate(runtime).queryForObject(
                "select count(*) from domain_rule_rollout_policy where tenant_id=? and environment='g2' and rule_set_key=? and active",
                Integer.class, f.tenant(), f.ruleSet())).isZero();
            throw new IllegalStateException("injected after previous policy flush");
          }
          try { return method.invoke(policyRows, arguments); }
          catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
    var failing = new DomainRuleRolloutPolicyService(failingRows, policyHeads, eventRows, heads,
        rolloutRows, Clock.systemUTC(), scopeLock, refresh);
    assertThatThrownBy(() -> tx(() -> failing.activate(replacement.policy().policyId(), quote(replacement.headEtag()), f.operator())))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("injected after previous policy flush");
    var after = tx(() -> policyHeads.findByTenantIdAndEnvironmentAndRuleSetKey(f.tenant(), "g2", f.ruleSet()).orElseThrow());
    assertThat(after.getActivePolicyId()).isEqualTo(before.getActivePolicyId());
    assertThat(after.getHeadEtag()).isEqualTo(before.getHeadEtag());
    assertThat(after.getActivationRevision()).isEqualTo(before.getActivationRevision());
    assertThat(tx(() -> policyRows.findById(before.getActivePolicyId()).orElseThrow().getActive())).isTrue();
    assertThat(tx(() -> policyRows.findById(replacement.policy().policyId()).orElseThrow().getStatus())).isEqualTo("APPROVED");
    assertThat(tx(() -> eventRows.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByCreatedAtAsc(f.tenant(), "g2", f.ruleSet()).size())).isEqualTo(count);
  }

  private static void preloadedContextMustRecheckAfterConcurrentSelection(Fixture f, DomainRuleSnapshotActivationResponse first) throws Exception {
    var second = tx(() -> snapshots.publish(f.request(2), f.tenant(), "g2", quote(first.headEtag()), null, "publisher"));
    var rollout = tx(() -> rollouts.create(new DomainRuleRolloutCreateRequest(second.snapshot().snapshotKey(), null), f.operator(), quote(first.headEtag()), null));
    preload(f, second, rollout.rolloutId());
    var loaded = new CountDownLatch(1);
    var selected = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var stale = pool.submit(() -> tx(() -> {
        heads.findByTenantIdAndEnvironmentAndRuleSetKey(f.tenant(), "g2", f.ruleSet()).orElseThrow();
        loaded.countDown();
        if (!selected.await(30, TimeUnit.SECONDS)) throw new AssertionError("selection timeout");
        return snapshots.publish(f.request(3), f.tenant(), "g2", quote(first.headEtag()), null, "publisher");
      }));
      assertThat(loaded.await(30, TimeUnit.SECONDS)).isTrue();
      tx(() -> snapshots.activatePublished(second.snapshot().snapshotKey(), "operator", f.tenant(), "g2", quote(first.headEtag()), null, rollout.rolloutId()));
      selected.countDown();
      assertThatThrownBy(() -> stale.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(DomainRuleSnapshotControlPlaneException.class);
    } finally { selected.countDown(); }
  }

  private static void concurrentPublicationIsAppendOnly(Fixture f) throws Exception {
    var head = tx(() -> heads.findByTenantIdAndEnvironmentAndRuleSetKey(f.tenant(), "g2", f.ruleSet()).orElseThrow());
    var attempts = race(() -> tx(() -> snapshots.publish(f.request(3), f.tenant(), "g2", quote(head.getHeadEtag().toString()), null, "publisher")));
    assertThat(attempts.stream().filter(DomainRuleSnapshotStoredResponse.class::isInstance).count()).isEqualTo(1);
    assertThat(attempts.stream().filter(DomainRuleSnapshotControlPlaneException.class::isInstance).count()).isEqualTo(1);
    assertThat(tx(() -> heads.findByTenantIdAndEnvironmentAndRuleSetKey(f.tenant(), "g2", f.ruleSet()).orElseThrow().getHeadEtag())).isEqualTo(head.getHeadEtag());
  }

  private static void preload(Fixture f, DomainRuleSnapshotStoredResponse candidate, UUID rolloutId) {
    tx(() -> {
      var row = snapshotRows.findByTenantIdAndEnvironmentAndSnapshotKey(f.tenant(), "g2", candidate.snapshot().snapshotKey()).orElseThrow();
      String catalog = JSON.readTree(row.getCompositionManifest()).path("implementationCatalogDigest").asText();
      var compatibility = RuleRuntimeCompatibility.current();
      // The test compiles with an actual empty executable registry; this case is declarative only.
      new org.praxisplatform.rules.snapshot.PraxisRuleSnapshotCompiler(org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry.empty())
          .compile(candidate.snapshot(), "neon-g2/1");
      return rollouts.probe(rolloutId, new DomainRuleCandidateProbeRequest(candidate.snapshot().snapshotKey(), candidate.snapshotContentHash(), true,
          "neon-g2/1", compatibility.engineContractVersion(), compatibility.jsonLogicDialectVersion(), compatibility.jsonLogicCorpusSha256(),
          catalog, null, Instant.now()), new DomainRuleGovernancePrincipal(f.tenant(), "service:real-test-host", "g2"));
    });
  }

  private static DomainRuleRolloutPolicyMutationResponse governPolicy(Fixture f) {
    var created = tx(() -> policies.create(new DomainRuleRolloutPolicyCreateRequest(f.ruleSet(), "required-g2", "REQUIRED", 1,
        BigDecimal.ONE, true, 120L, 600L), new DomainRuleGovernancePrincipal(f.tenant(), "policy-author", "g2")));
    tx(() -> policies.approve(created.policy().policyId(), new DomainRuleGovernancePrincipal(f.tenant(), "policy-reviewer", "g2")));
    return tx(() -> policies.activate(created.policy().policyId(), quote(created.headEtag()), f.operator()));
  }

  private static Fixture fixture() {
    String tenant = "config-g2-" + UUID.randomUUID();
    String key = "decision-" + UUID.randomUUID();
    var definition = tx(() -> {
      var source = context.getBean(DomainRuleDefinitionRepository.class).saveAndFlush(DomainRuleDefinition.builder()
          .id(UUID.randomUUID()).tenantId(tenant).environment("g2").ruleKey(key).version(1).ruleType("validation").status("approved")
          .definition("{\"kind\":\"policy\"}").parameters("{}").governance("{\"classification\":\"corporate\"}")
          .createdByType("authenticated").createdBy("source-author").createdAt(Instant.now().minusSeconds(20))
          .approvedBy("source-reviewer").approvedAt(Instant.now().minusSeconds(10)).updatedAt(Instant.now()).build());
      context.getBean(DomainRuleDefinitionApprovalRepository.class).insertIfAbsent(UUID.randomUUID(), tenant, "g2", source.getId(),
          new DomainRuleDefinitionFingerprint(JSON).sha256(source), "source-reviewer", Instant.now().minusSeconds(10));
      return source;
    });
    var fixture = new Fixture(tenant, key, definition.getId());
    for (int version = 1; version <= 3; version++) {
      var request = fixture.manifestRequest(version);
      tx(() -> snapshots.approveComposition(request, tenant, "g2", "composition-reviewer-a"));
      tx(() -> snapshots.approveComposition(request, tenant, "g2", "composition-reviewer-b"));
    }
    return fixture;
  }

  private record Fixture(String tenant, String ruleSet, UUID sourceId) {
    DomainRuleGovernancePrincipal operator() { return new DomainRuleGovernancePrincipal(tenant, "operator", "g2"); }
    RuleSetDefinition definition(int version) {
      return new RuleSetDefinition(new RuleSetRef("proof", "publication", ruleSet, "evaluate", version), List.of("request"),
          List.of(new DecisionSlot("eligible", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE, OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT)),
          List.of(new DecisionBinding("eligible", "eligible", DecisionSource.PRODUCT, null, RuleExecutorRef.jsonLogic(JSON.createObjectNode().put("var", "request.eligible")),
              List.of(), 10, true, RuleDecision.DENY, "NOT_ELIGIBLE", List.of("request.eligible"))), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    }
    DomainRuleCompositionManifestRequest manifestRequest(int version) {
      return new DomainRuleCompositionManifestRequest(definition(version), List.of(sourceId), "neon-g2", "neon-g2/1", "2026-01-01T00:00:00Z", null);
    }
    DomainRuleSnapshotPublicationRequest request(int version) {
      var manifest = snapshots.prepareCompositionManifest(manifestRequest(version), tenant, "g2");
      return new DomainRuleSnapshotPublicationRequest(definition(version), List.of(sourceId), "neon-g2", "neon-g2/1", "2026-01-01T00:00:00Z", null, manifest.compositionDigest());
    }
  }

  private static void assertObservationBlocked(Fixture f, DomainRuleSnapshotStoredResponse snapshot, long revision) {
    assertThatThrownBy(() -> observe(f, snapshot, revision)).hasMessageContaining("does not select");
  }
  private static void assertObservationAccepted(Fixture f, DomainRuleSnapshotStoredResponse snapshot, long revision) {
    assertThat(observe(f, snapshot, revision).acceptedCount()).isEqualTo(1);
  }
  private static DomainRuleExecutionObservationBatchResponse observe(Fixture f, DomainRuleSnapshotStoredResponse snapshot, long revision) {
    return tx(() -> new DomainRuleExecutionObservationService(snapshotRows, events, context.getBean(DomainRuleExecutionObservationRepository.class), Clock.systemUTC())
        .ingest(new DomainRuleExecutionObservationBatchRequest(List.of(new DomainRuleExecutionObservationRequest(UUID.randomUUID(),
            snapshot.snapshot().snapshotKey(), snapshot.snapshotContentHash(), revision, "ALLOW", 10, Instant.now()))),
            new DomainRuleGovernancePrincipal(f.tenant(), "service:real-test-host", "g2")));
  }

  private static DomainRuleSnapshotService snapshotService(DomainRuleSnapshotActivationGate gate) {
    return new DomainRuleSnapshotService(context.getBean(DomainRuleDefinitionRepository.class), snapshotRows, heads, events,
        context.getBean(DomainRuleCompositionApprovalRepository.class), context.getBean(DomainRuleDefinitionApprovalRepository.class),
        new DomainRuleDefinitionFingerprint(JSON), JSON, DomainRuleImplementationCatalog.denyAll(), gate, null, scopeLock, refresh);
  }
  private static <T> T tx(Callable<T> action) {
    return transactions.execute(status -> { try { return action.call(); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); } });
  }
  private static List<Object> race(Callable<?> action) throws Exception { return race(action, action); }
  private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      java.util.function.Function<Callable<?>, Callable<Object>> worker = action -> () -> {
        start.await(); try { return action.call(); } catch (DomainRuleSnapshotControlPlaneException e) { return e; }
      };
      var left = pool.submit(worker.apply(first)); var right = pool.submit(worker.apply(second)); start.countDown();
      return List.of(left.get(45, TimeUnit.SECONDS), right.get(45, TimeUnit.SECONDS));
    }
  }
  private static String quote(String value) { return '"' + value + '"'; }

  private static PGSimpleDataSource assignedSource(String roleKind) throws Exception {
    Path file = Path.of(required("PRAXIS_CONFIG_G2_NEON_" + roleKind + "_URI_FILE"));
    assertThat(Files.getPosixFilePermissions(file)).isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    URI uri = URI.create(Files.readString(file).strip());
    assertThat(uri.getScheme()).isIn("postgresql", "postgres");
    assertThat(uri.getHost()).isEqualTo(required("PRAXIS_CONFIG_G2_NEON_EXPECTED_HOST")).endsWith(".neon.tech").doesNotContain("-pooler");
    assertThat(uri.getRawQuery()).contains("sslmode=verify-full").contains("channel_binding=require");
    String database = URLDecoder.decode(uri.getRawPath().substring(1), StandardCharsets.UTF_8);
    assertThat(database).isEqualTo(required("PRAXIS_CONFIG_G2_NEON_EXPECTED_DATABASE"));
    String[] credentials = uri.getRawUserInfo().split(":", 2);
    var source = new PGSimpleDataSource();
    source.setServerNames(new String[] {uri.getHost()}); source.setPortNumbers(new int[] {uri.getPort() < 0 ? 5432 : uri.getPort()});
    source.setDatabaseName(database); source.setUser(URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
    source.setPassword(URLDecoder.decode(credentials[1], StandardCharsets.UTF_8));
    source.setSslMode("verify-full"); source.setProperty(PGProperty.SSL_FACTORY, "org.postgresql.ssl.DefaultJavaSSLFactory");
    source.setProperty(PGProperty.CHANNEL_BINDING, "require"); source.setConnectTimeout(10); source.setSocketTimeout(45);
    return source;
  }
  private static void attest(PGSimpleDataSource source, String role, boolean owner) throws Exception {
    try (Connection connection = source.getConnection()) {
      var executor = connection.unwrap(org.postgresql.jdbc.PgConnection.class).getQueryExecutor();
      var field = org.postgresql.core.QueryExecutorBase.class.getDeclaredField("pgStream"); field.setAccessible(true);
      var stream = (org.postgresql.core.PGStream) field.get(executor);
      assertThat(stream.getSocket()).isInstanceOf(SSLSocket.class);
      var session = ((SSLSocket) stream.getSocket()).getSession();
      assertThat(session.isValid()).isTrue(); assertThat(session.getPeerHost()).isEqualTo(required("PRAXIS_CONFIG_G2_NEON_EXPECTED_HOST"));
      try (var statement = connection.createStatement(); var rows = statement.executeQuery(
          "select current_database(),current_user,session_user,current_setting('server_version_num')::integer,rolsuper,rolcreatedb,rolcreaterole from pg_roles where rolname=current_user")) {
        assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(required("PRAXIS_CONFIG_G2_NEON_EXPECTED_DATABASE"));
        assertThat(rows.getString(2)).isEqualTo(role); assertThat(rows.getString(3)).isEqualTo(role);
        assertThat(rows.getInt(4) / 10000).isEqualTo(Integer.parseInt(required("PRAXIS_CONFIG_G2_NEON_EXPECTED_PG_MAJOR")));
        assertThat(rows.getBoolean(5)).isFalse(); assertThat(rows.getBoolean(6)).isEqualTo(owner); assertThat(rows.getBoolean(7)).isEqualTo(owner);
      }
      if (!owner) {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("select has_schema_privilege(current_user,'public','CREATE')")) {
          assertThat(row.next()).isTrue(); assertThat(row.getBoolean(1)).isFalse();
        }
      }
    }
  }
  private static String required(String key) {
    String value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalStateException("Missing explicit Neon assignment: " + key);
    return value;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = DomainRuleSnapshotRepository.class)
  static class JpaConfiguration {
    @Bean DataSource dataSource() { return runtime; }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
      var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
      factory.setPackagesToScan("org.praxisplatform.config.domain"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.show_sql", "false",
          "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
      return factory;
    }
    @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
  }
}
