package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.service.DomainRuleDefinitionFingerprint;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.snapshot.PraxisRuleSnapshotCompiler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Database proof that existing-head mutations serialize on the canonical scoped row. */
@DataJpaTest
@ContextConfiguration(classes = PraxisConfigStarterApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
@Tag("integration")
class DomainRuleSnapshotHeadRepositoryConcurrencyTest {
    private static final String TENANT = "tenant-a";
    private static final String ENVIRONMENT = "prod";
    private static final String RULE_SET = "payroll";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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

    @Autowired DomainRuleSnapshotHeadRepository repository;
    @Autowired DomainRuleSnapshotRepository snapshotRepository;
    @Autowired DomainRuleSnapshotEventRepository eventRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearSnapshotControlPlane() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(ignored -> {
            eventRepository.deleteAllInBatch();
            repository.deleteAllInBatch();
            snapshotRepository.deleteAllInBatch();
        });
    }

    @Test
    void pessimisticScopeLockSerializesConcurrentHeadRevisions() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(ignored -> {
            DomainRuleSnapshot snapshot = storedSnapshot("snapshot-lock", 1, null);
            snapshotRepository.saveAndFlush(snapshot);
            repository.saveAndFlush(head(snapshot.getId(), 1L, UUID.randomUUID()));
        });
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                DomainRuleSnapshotHead selected = lock();
                firstLocked.countDown();
                await(releaseFirst);
                advance(selected);
                repository.saveAndFlush(selected);
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                DomainRuleSnapshotHead selected = lock();
                advance(selected);
                repository.saveAndFlush(selected);
            }));

            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        DomainRuleSnapshotHead stored = repository
                .findByTenantIdAndEnvironmentAndRuleSetKey(TENANT, ENVIRONMENT, RULE_SET)
                .orElseThrow();
        assertThat(stored.getActivationRevision()).isEqualTo(3L);
        assertThat(stored.getRowVersion()).isEqualTo(2L);
    }

    @Test
    void rollbackRotatesTheHeadAndAppendsOneAuditableEventOnRealPostgres() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        UUID originalEtag = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            DomainRuleSnapshot v1 = storedSnapshot("snapshot-v1", 1, null);
            DomainRuleSnapshot v2 = storedSnapshot("snapshot-v2", 2, v1.getId());
            snapshotRepository.save(v1);
            snapshotRepository.saveAndFlush(v2);
            repository.saveAndFlush(head(v2.getId(), 2L, originalEtag));
        });

        DomainRuleSnapshotService service = service();
        var response = transactions.execute(ignored -> service.rollback(
                "snapshot-v1", "operator-a", TENANT, ENVIRONMENT, quote(originalEtag)));

        assertThat(response).isNotNull();
        assertThat(response.activationType()).isEqualTo("ROLLED_BACK");
        assertThat(response.activationRevision()).isEqualTo(3L);
        assertThat(response.headEtag()).isNotEqualTo(quote(originalEtag));
        DomainRuleSnapshotHead stored = repository
                .findByTenantIdAndEnvironmentAndRuleSetKey(TENANT, ENVIRONMENT, RULE_SET)
                .orElseThrow();
        assertThat(stored.getActiveSnapshotId()).isEqualTo(
                snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
                        TENANT, ENVIRONMENT, "snapshot-v1").orElseThrow().getId());
        assertThat(stored.getActivationRevision()).isEqualTo(3L);
        assertThat(stored.getHeadEtag()).isNotEqualTo(originalEtag);
        assertThat(stored.getRowVersion()).isEqualTo(1L);
        assertThat(eventRepository.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("ROLLED_BACK");
            assertThat(event.getActivationRevision()).isEqualTo(3L);
            assertThat(event.getHeadEtag()).isEqualTo(stored.getHeadEtag());
            assertThat(event.getActor()).isEqualTo("operator-a");
        });
    }

    private DomainRuleSnapshotHead lock() {
        return repository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
                TENANT, ENVIRONMENT, RULE_SET).orElseThrow();
    }

    private void advance(DomainRuleSnapshotHead head) {
        head.setActivationRevision(head.getActivationRevision() + 1);
        head.setHeadEtag(UUID.randomUUID());
        head.setUpdatedAt(Instant.now());
    }

    private DomainRuleSnapshotHead head(UUID activeSnapshotId, long activationRevision, UUID etag) {
        return DomainRuleSnapshotHead.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT)
                .environment(ENVIRONMENT)
                .ruleSetKey(RULE_SET)
                .activeSnapshotId(activeSnapshotId)
                .activationRevision(activationRevision)
                .headEtag(etag)
                .updatedAt(Instant.now())
                .build();
    }

    private DomainRuleSnapshot storedSnapshot(String snapshotKey, int revision, UUID supersedesId) {
        PublishedRuleSnapshot snapshot = publishedSnapshot(snapshotKey, revision);
        String compositionManifest = "{\"test\":true}";
        String compositionDigest = PraxisCanonicalJson.sha256(readJson(compositionManifest));
        snapshot = withCompositionApprovals(snapshot, compositionDigest);
        String contentHash = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.empty())
                .compile(snapshot, snapshot.requiredHostContractVersion())
                .snapshotContentHash();
        return DomainRuleSnapshot.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT)
                .environment(ENVIRONMENT)
                .snapshotKey(snapshotKey)
                .ruleSetKey(RULE_SET)
                .ruleSetVersion(revision)
                .publicationRevision(revision)
                .snapshotPayload(writeJson(snapshot))
                .contentHash(contentHash)
                .compositionManifest(compositionManifest)
                .compositionDigest(compositionDigest)
                .supersedesSnapshotId(supersedesId)
                .publishedBy("release-manager")
                .publishedAt(Instant.now().minusSeconds(60))
                .build();
    }

    private PublishedRuleSnapshot publishedSnapshot(String snapshotKey, int revision) {
        var expression = OBJECT_MAPPER.createObjectNode().put("var", "payroll.amount");
        RuleSetDefinition ruleSet = new RuleSetDefinition(
                new RuleSetRef("human-resources", "payroll", RULE_SET, "determine", revision),
                List.of("payroll"),
                List.of(new DecisionSlot(
                        "payroll.amount", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                        OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT)),
                List.of(new DecisionBinding(
                        "payroll.amount", "payroll.amount", DecisionSource.PRODUCT, null,
                        RuleExecutorRef.jsonLogic(expression), List.of(), 10, true,
                        RuleDecision.INCONCLUSIVE, "PAYROLL_AMOUNT_UNAVAILABLE",
                        List.of("payroll.amount"))),
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED);
        String evidenceHash = String.valueOf((char) ('A' + revision - 1)).repeat(64);
        Instant now = Instant.now();
        return new PublishedRuleSnapshot(
                PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                snapshotKey,
                TENANT,
                ENVIRONMENT,
                "praxis-api-quickstart",
                revision,
                now.minusSeconds(120).toString(),
                revision == 1 ? null : "snapshot-v" + (revision - 1),
                "quickstart/1.0",
                now.minusSeconds(3600).toString(),
                now.plusSeconds(3600).toString(),
                List.of(new RuleSnapshotSource(
                        "definition-" + revision, "payroll:amount", revision, evidenceHash)),
                List.of(new RuleSnapshotApproval(
                        "definition-approval-" + revision,
                        "RULE_DEFINITION_APPROVER",
                        "reviewer-a",
                        now.minusSeconds(180).toString(),
                        evidenceHash)),
                ruleSet);
    }

    private PublishedRuleSnapshot withCompositionApprovals(
            PublishedRuleSnapshot snapshot, String compositionDigest) {
        Instant now = Instant.now();
        var approvals = List.of(
                snapshot.approvals().getFirst(),
                new RuleSnapshotApproval(
                        "composition-a", "RULE_COMPOSITION_APPROVER", "reviewer-a",
                        now.minusSeconds(60).toString(), compositionDigest),
                new RuleSnapshotApproval(
                        "composition-b", "RULE_COMPOSITION_APPROVER", "reviewer-b",
                        now.minusSeconds(30).toString(), compositionDigest));
        return new PublishedRuleSnapshot(
                snapshot.snapshotContractVersion(), snapshot.snapshotKey(), snapshot.tenantId(),
                snapshot.environment(), snapshot.ownerServiceKey(), snapshot.publicationRevision(),
                snapshot.publishedAtUtc(), snapshot.supersedesSnapshotKey(),
                snapshot.requiredHostContractVersion(), snapshot.validFromUtc(), snapshot.validUntilUtc(),
                snapshot.sources(), approvals, snapshot.ruleSet());
    }

    private DomainRuleSnapshotService service() {
        return new DomainRuleSnapshotService(
                mock(DomainRuleDefinitionRepository.class),
                snapshotRepository,
                repository,
                eventRepository,
                mock(DomainRuleCompositionApprovalRepository.class),
                mock(DomainRuleDefinitionApprovalRepository.class),
                new DomainRuleDefinitionFingerprint(OBJECT_MAPPER),
                OBJECT_MAPPER,
                DomainRuleImplementationCatalog.denyAll());
    }

    private static String quote(UUID etag) {
        return '"' + etag.toString() + '"';
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static EmbeddedPostgres startPostgres() {
        EmbeddedPostgres postgres = null;
        try {
            postgres = EmbeddedPostgres.builder()
                    .setCleanDataDirectory(true)
                    .setRegisterShutdownHook(false)
                    .start();
            try (Connection connection = postgres.getPostgresDatabase().getConnection();
                    Statement statement = connection.createStatement()) {
                for (String migration : List.of(
                        "/db/migration/V30__create_domain_rule_snapshot_control_plane.sql",
                        "/db/migration/V32__enforce_domain_rule_snapshot_scope_references.sql",
                        "/db/migration/V33__bind_snapshot_to_approved_composition.sql",
                        "/db/migration/V44__allow_explicit_rule_snapshot_activation.sql")) {
                    statement.execute(readResource(migration));
                }
            }
            return postgres;
        } catch (Exception exception) {
            if (postgres != null) {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // Preserve the schema preparation failure.
                }
            }
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = DomainRuleSnapshotHeadRepositoryConcurrencyTest.class
                .getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing PostgreSQL migration " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the first head lock");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the head lock", interrupted);
        }
    }
}
