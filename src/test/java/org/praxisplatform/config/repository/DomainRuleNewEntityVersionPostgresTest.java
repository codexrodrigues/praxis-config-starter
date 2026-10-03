package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicyHead;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.domain.DomainRuleSnapshotRollout;
import org.praxisplatform.config.dto.DomainRuleRolloutCreateRequest;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL proof that assigned UUIDs and nullable new @Version values persist correctly. */
@DataJpaTest
@ContextConfiguration(classes = PraxisConfigStarterApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
@Tag("integration")
class DomainRuleNewEntityVersionPostgresTest {
  private static final String TENANT = "tenant-version-proof";
  private static final String ENVIRONMENT = "prod";
  private static final EmbeddedPostgres POSTGRES = startPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "");
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add("spring.flyway.enabled", () -> "false");
  }

  @AfterAll
  static void stopPostgres() throws IOException {
    POSTGRES.close();
  }

  @Autowired DomainRuleRolloutPolicyRepository policies;
  @Autowired DomainRuleRolloutPolicyHeadRepository policyHeads;
  @Autowired DomainRuleRolloutPolicyEventRepository policyEvents;
  @Autowired DomainRuleSnapshotRolloutRepository rollouts;
  @Autowired DomainRuleSnapshotRolloutEventRepository rolloutEvents;
  @Autowired DomainRuleCandidateProbeRepository probes;
  @Autowired DomainRuleSnapshotRepository snapshots;
  @Autowired DomainRuleSnapshotHeadRepository snapshotHeads;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DataSource dataSource;

  @Test
  void firstPolicyBootstrapInsertsVersionZeroThenRejectsStaleHead() {
    String ruleSet = key();
    DomainRuleGovernancePrincipal principal = principal();
    TransactionTemplate tx = transactions();
    tx.executeWithoutResult(ignored -> policyService().requireActiveOrBootstrap(ruleSet, principal));

    DomainRuleRolloutPolicyHead stale = policyHeads
        .findByTenantIdAndEnvironmentAndRuleSetKey(TENANT, ENVIRONMENT, ruleSet).orElseThrow();
    assertThat(stale.getRowVersion()).isZero();
    assertVersion("domain_rule_rollout_policy_head", stale.getId(), 0L);

    tx.executeWithoutResult(ignored -> {
      DomainRuleRolloutPolicyHead current = policyHeads
          .findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(TENANT, ENVIRONMENT, ruleSet)
          .orElseThrow();
      current.setHeadEtag(UUID.randomUUID());
      policyHeads.saveAndFlush(current);
    });
    assertVersion("domain_rule_rollout_policy_head", stale.getId(), 1L);
    stale.setHeadEtag(UUID.randomUUID());
    assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> policyHeads.saveAndFlush(stale)))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertVersion("domain_rule_rollout_policy_head", stale.getId(), 1L);
  }

  @Test
  void activePolicyWithoutHeadCreatesVersionZeroWithoutRecreatingPolicy() {
    String ruleSet = key();
    TransactionTemplate tx = transactions();
    tx.executeWithoutResult(ignored -> policyService().requireActiveOrBootstrap(ruleSet, principal()));
    long policyCount = policies.count();
    tx.executeWithoutResult(ignored -> {
      DomainRuleRolloutPolicyHead head = policyHeads
          .findByTenantIdAndEnvironmentAndRuleSetKey(TENANT, ENVIRONMENT, ruleSet).orElseThrow();
      policyHeads.delete(head);
      policyHeads.flush();
    });

    tx.executeWithoutResult(ignored -> policyService().requireActiveOrBootstrap(ruleSet, principal()));
    DomainRuleRolloutPolicyHead restored = policyHeads
        .findByTenantIdAndEnvironmentAndRuleSetKey(TENANT, ENVIRONMENT, ruleSet).orElseThrow();
    assertThat(policies.count()).isEqualTo(policyCount);
    assertThat(restored.getActivePolicyId()).isNotNull();
    assertVersion("domain_rule_rollout_policy_head", restored.getId(), 0L);
  }

  @Test
  void stagedRolloutInsertsVersionZeroThenRejectsStaleRollout() {
    String ruleSet = key();
    String candidateKey = "candidate-" + UUID.randomUUID();
    UUID headEtag = UUID.randomUUID();
    TransactionTemplate tx = transactions();
    tx.executeWithoutResult(ignored -> {
      DomainRuleSnapshot active = snapshots.saveAndFlush(snapshot(ruleSet, "active-" + UUID.randomUUID(), 1));
      snapshots.saveAndFlush(snapshot(ruleSet, candidateKey, 2));
      snapshotHeads.saveAndFlush(DomainRuleSnapshotHead.builder()
          .id(UUID.randomUUID()).tenantId(TENANT).environment(ENVIRONMENT).ruleSetKey(ruleSet)
          .activeSnapshotId(active.getId()).activationRevision(1L).headEtag(headEtag)
          .updatedAt(Instant.now()).build());
    });

    var result = tx.execute(ignored -> rolloutService().create(
        new DomainRuleRolloutCreateRequest(candidateKey, null), principal(), '"' + headEtag.toString() + '"'));
    assertThat(result).isNotNull();
    DomainRuleSnapshotRollout stale = rollouts.findById(result.rolloutId()).orElseThrow();
    assertThat(stale.getRowVersion()).isZero();
    assertVersion("domain_rule_snapshot_rollout", stale.getId(), 0L);

    tx.executeWithoutResult(ignored -> {
      DomainRuleSnapshotRollout current = rollouts.findById(stale.getId()).orElseThrow();
      current.setUpdatedAt(Instant.now().plusSeconds(1));
      rollouts.saveAndFlush(current);
    });
    assertVersion("domain_rule_snapshot_rollout", stale.getId(), 1L);
    stale.setUpdatedAt(Instant.now().plusSeconds(2));
    assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> rollouts.saveAndFlush(stale)))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertVersion("domain_rule_snapshot_rollout", stale.getId(), 1L);
  }

  private DomainRuleSnapshot snapshot(String ruleSet, String key, int revision) {
    return DomainRuleSnapshot.builder().id(UUID.randomUUID()).tenantId(TENANT)
        .environment(ENVIRONMENT).snapshotKey(key).ruleSetKey(ruleSet)
        .ruleSetVersion(revision).publicationRevision(revision).snapshotPayload("{}")
        .contentHash("A".repeat(64)).publishedBy("version-proof").publishedAt(Instant.now())
        .build();
  }

  private DomainRuleRolloutPolicyService policyService() {
    return new DomainRuleRolloutPolicyService(policies, policyHeads, policyEvents,
        snapshotHeads, rollouts, Clock.systemUTC());
  }

  private DomainRuleRolloutService rolloutService() {
    return new DomainRuleRolloutService(policies, rollouts, probes, rolloutEvents, snapshots,
        snapshotHeads, policyService(), new ObjectMapper(), Clock.systemUTC());
  }

  private void assertVersion(String table, UUID id, long expected) {
    Long actual = new JdbcTemplate(dataSource).queryForObject(
        "select row_version from " + table + " where id = ?", Long.class, id);
    assertThat(actual).isEqualTo(expected);
  }

  private TransactionTemplate transactions() {
    return new TransactionTemplate(transactionManager);
  }

  private DomainRuleGovernancePrincipal principal() {
    return new DomainRuleGovernancePrincipal(TENANT, "version-proof", ENVIRONMENT);
  }

  private String key() {
    return "version-proof-" + UUID.randomUUID();
  }

  private static EmbeddedPostgres startPostgres() {
    EmbeddedPostgres postgres = null;
    try {
      postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
          .setRegisterShutdownHook(false).start();
      try (Connection connection = postgres.getPostgresDatabase().getConnection();
          Statement statement = connection.createStatement()) {
        for (String migration : List.of(
            "/db/migration/V30__create_domain_rule_snapshot_control_plane.sql",
            "/db/migration/V32__enforce_domain_rule_snapshot_scope_references.sql",
            "/db/migration/V33__bind_snapshot_to_approved_composition.sql",
            "/db/migration/V44__allow_explicit_rule_snapshot_activation.sql",
            "/db/migration/V53__create_domain_rule_staged_rollout.sql",
            "/db/migration/V54__govern_domain_rule_rollout_policy.sql")) {
          statement.execute(readResource(migration));
        }
      }
      return postgres;
    } catch (Exception failure) {
      if (postgres != null) {
        try { postgres.close(); } catch (IOException ignored) { /* Preserve the original failure. */ }
      }
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static String readResource(String path) throws IOException {
    try (InputStream stream = DomainRuleNewEntityVersionPostgresTest.class.getResourceAsStream(path)) {
      if (stream == null) throw new IOException("Missing PostgreSQL migration " + path);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
