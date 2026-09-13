package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.praxisplatform.config.dto.DomainRuleDefinitionStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRulePublicationRequest;
import org.praxisplatform.config.dto.DomainRuleStatusTransitionRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleDefinitionEvidenceGateService;
import org.praxisplatform.config.service.DomainRuleDefinitionFingerprint;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.OperationalPolicyService;
import org.praxisplatform.config.service.OperationalPolicyTarget;
import org.praxisplatform.config.service.OperationalPolicyResolution;
import org.praxisplatform.config.service.DomainRuleTestEvidencePolicyService;
import org.praxisplatform.config.service.GovernedColorPaletteContractValidator;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Service/proxy proof of the Config lifecycle mutex against the canonical PostgreSQL rule schema. */
@DataJpaTest(showSql = false)
@ContextConfiguration(classes = {PraxisConfigStarterApplication.class, DomainRuleLifecycleConcurrencyPostgresTest.Beans.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
@Tag("integration")
class DomainRuleLifecycleConcurrencyPostgresTest {
    private static final String ENVIRONMENT = "prod";
    private static final String LAYER = "form_config";
    private static final String TYPE = "praxis-dynamic-form";
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @org.springframework.boot.test.context.TestConfiguration
    static class Beans {
        @Bean(name = {"transactionManager", "configTransactionManager"})
        PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        DomainRuleService domainRuleService(
                DomainRuleDefinitionRepository definitions,
                DomainRuleMaterializationRepository materializations,
                DomainRuleEventRepository events,
                ObjectProvider<DomainRuleDefinitionEvidenceGateService> evidenceGate,
                org.springframework.data.jpa.repository.JpaContext jpaContext) {
            ObjectMapper mapper = new ObjectMapper();
            return new DomainRuleService(
                    definitions, materializations, events,
                    mock(DomainCatalogReleaseRepository.class),
                    mock(DomainKnowledgeChangeSetRepository.class),
                    mock(DomainRuleDefinitionApprovalRepository.class),
                    new DomainRuleDefinitionFingerprint(mapper), mapper, evidenceGate,
                    new GovernedColorPaletteContractValidator(mapper),
                    new org.praxisplatform.config.service.DomainRuleEntityRefresh(jpaContext));
        }

        @Bean
        DomainRuleChangeWorkspaceService domainRuleChangeWorkspaceService(
                DomainRuleChangeWorkspaceRepository workspaces,
                DomainRuleDefinitionRepository definitions,
                DomainRuleService rules) {
            ObjectMapper mapper = new ObjectMapper();
            return new DomainRuleChangeWorkspaceService(
                    workspaces,
                    mock(DomainRuleTestScenarioRepository.class),
                    definitions,
                    new DomainRuleDefinitionFingerprint(mapper),
                    mapper,
                    mock(DomainRuleTestRunRepository.class),
                    mock(DomainRuleTestRunResultRepository.class),
                    mock(DomainRuleWorkspaceReviewRepository.class),
                    rules,
                    new DomainRuleTestEvidencePolicyService(mapper));
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private DomainRuleService rules;
    @Autowired private DomainRuleMaterializationRepository materializations;
    @Autowired private DomainRuleChangeWorkspaceService workspaces;
    @Autowired private DomainRuleDefinitionRepository definitions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("configTransactionManager") private PlatformTransactionManager transactionManager;

    @AfterAll
    static void closePostgres() throws IOException {
        POSTGRES.close();
    }

    @Test
    void applicationCommittedFirstIsThenSupersededByDefinitionRetirement() throws Exception {
        Scope scope = scope();
        UUID definition = definition(scope, "orders-a");
        UUID draft = materialization(scope, definition, "orders:form", "draft");

        Throwable failure = race(
                () -> apply(draft, scope),
                () -> retire(definition, scope));

        assertThat(failure).isNull();
        assertThat(status("domain_rule_definition", definition)).isEqualTo("retired");
        assertThat(status("domain_rule_materialization", draft)).isEqualTo("superseded");
        assertThat(everApplied(draft)).isTrue();
        assertThat(eventCount(draft, "materialization.applied")).isEqualTo(1);
        assertThat(eventCount(draft, "materialization.superseded")).isEqualTo(1);
        assertNoIneligibleHead(scope);
    }

    @Test
    void retirementCommittedFirstMakesWaitingApplicationFailClosed() throws Exception {
        Scope scope = scope();
        UUID definition = definition(scope, "orders-a");
        UUID draft = materialization(scope, definition, "orders:form", "draft");

        Throwable failure = race(
                () -> retire(definition, scope),
                () -> apply(draft, scope));

        assertThat(failure).isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("definition is active");
        assertThat(status("domain_rule_definition", definition)).isEqualTo("retired");
        assertThat(status("domain_rule_materialization", draft)).isEqualTo("draft");
        assertThat(everApplied(draft)).isFalse();
        assertThat(eventCount(draft, "materialization.applied")).isZero();
        assertNoIneligibleHead(scope);
    }

    @Test
    void aDefinitionLoadedBeforeTheScopeLockCannotAuthorizeApplicationAfterRetirement() throws Exception {
        Scope scope = scope();
        UUID definition = definition(scope, "orders-cached");
        UUID draft = materialization(scope, definition, "orders:cached", "draft");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch cachedActiveDefinition = new CountDownLatch(1);
        CountDownLatch releaseCachedReader = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var staleReader = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                // Deliberately retain the managed definition in this Config persistence context.
                assertThat(definitions.findById(definition).orElseThrow().getStatus()).isEqualTo("active");
                cachedActiveDefinition.countDown();
                await(releaseCachedReader);
                apply(draft, scope); // This proxied REQUIRED call joins the already-open Config transaction.
            }));
            try {
                assertThat(cachedActiveDefinition.await(10, TimeUnit.SECONDS)).isTrue();
                var retirement = executor.submit(() -> retire(definition, scope));
                retirement.get(10, TimeUnit.SECONDS); // Must commit while the first context still holds active.
                releaseCachedReader.countDown();
                Throwable failure = null;
                try {
                    staleReader.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException rejected) {
                    failure = rejected.getCause();
                }
                assertThat(failure).isInstanceOf(ConfigurationIngestionException.class)
                        .hasMessageContaining("definition is active");
            } finally {
                releaseCachedReader.countDown();
            }
        }
        assertThat(status("domain_rule_definition", definition)).isEqualTo("retired");
        assertThat(status("domain_rule_materialization", draft)).isEqualTo("draft");
        assertThat(eventCount(draft, "materialization.applied")).isZero();
        assertNoIneligibleHead(scope);
    }

    @Test
    void replacementAndRetirementFinishWithoutDeadlockInEitherCommitOrder() throws Exception {
        for (boolean replacementFirst : List.of(true, false)) {
            Scope scope = scope();
            UUID previousDefinition = definition(scope, "orders-old");
            UUID nextDefinition = definition(scope, "orders-new");
            UUID previous = materialization(scope, previousDefinition, "orders:form", "applied");
            UUID next = materialization(scope, nextDefinition, "orders:form", "draft");

            Throwable failure = replacementFirst
                    ? race(() -> apply(next, scope), () -> retire(previousDefinition, scope))
                    : race(() -> retire(previousDefinition, scope), () -> apply(next, scope));

            assertThat(failure).isNull();
            assertThat(status("domain_rule_definition", previousDefinition)).isEqualTo("retired");
            assertThat(status("domain_rule_materialization", previous)).isEqualTo("superseded");
            assertThat(status("domain_rule_materialization", next)).isEqualTo("applied");
            assertThat(everApplied(previous)).isTrue();
            assertThat(everApplied(next)).isTrue();
            assertThat(appliedHeads(scope, "orders:form")).containsExactly(next);
            assertThat(eventCount(previous, "materialization.superseded")).isEqualTo(1);
            assertThat(eventCount(next, "materialization.applied")).isEqualTo(1);
            assertNoIneligibleHead(scope);
        }
    }

    @Test
    void publishersSelectingTwoTargetsInOppositeOrdersReplaceBothHeadsAtomically() throws Exception {
        Scope scope = scope();
        UUID firstDefinition = definition(scope, "orders-first");
        UUID secondDefinition = definition(scope, "orders-second");
        UUID firstX = materialization(scope, firstDefinition, "orders:x", "draft");
        UUID firstY = materialization(scope, firstDefinition, "orders:y", "draft");
        UUID secondX = materialization(scope, secondDefinition, "orders:x", "draft");
        UUID secondY = materialization(scope, secondDefinition, "orders:y", "draft");

        Throwable failure = race(
                () -> publish(firstDefinition, List.of(firstX, firstY), scope),
                () -> publish(secondDefinition, List.of(secondY, secondX), scope));

        assertThat(failure).isNull();
        assertThat(appliedHeads(scope, "orders:x")).containsExactly(secondX);
        assertThat(appliedHeads(scope, "orders:y")).containsExactly(secondY);
        assertThat(status("domain_rule_materialization", firstX)).isEqualTo("superseded");
        assertThat(status("domain_rule_materialization", firstY)).isEqualTo("superseded");
        assertThat(eventCount(firstX, "materialization.superseded")).isEqualTo(1);
        assertThat(eventCount(firstY, "materialization.superseded")).isEqualTo(1);
        assertNoIneligibleHead(scope);
    }

    @Test
    void promotionWaitsForPublishedScopeBeforeItsWorkspaceLookup() throws Exception {
        Scope scope = scope();
        UUID definition = definition(scope, "orders-publish");
        UUID draft = materialization(scope, definition, "orders:form", "draft");
        UUID missingWorkspace = UUID.randomUUID();

        Throwable failure = race(
                () -> publish(definition, List.of(draft), scope),
                () -> workspaces.promote(missingWorkspace, "\"unused-etag\"", scope.principal()));

        assertThat(failure).isInstanceOf(ResponseStatusException.class);
        assertThat(status("domain_rule_materialization", draft)).isEqualTo("applied");
        assertThat(eventCount(draft, "materialization.applied")).isEqualTo(1);
        assertNoIneligibleHead(scope);
    }

    /** Both calls enter through Spring proxies; the outer Config transaction holds the first lock through commit. */
    private Throwable race(Runnable firstOperation, Runnable secondOperation) throws Exception {
        assertThat(AopUtils.isAopProxy(rules)).isTrue();
        assertThat(AopUtils.isAopProxy(workspaces)).isTrue();
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch firstOperationFinished = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                firstPid.set(backendPid());
                firstOperation.run();
                firstOperationFinished.countDown();
                await(releaseFirst);
            }));
            try {
                assertThat(firstOperationFinished.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                    secondPid.set(backendPid());
                    secondEntered.countDown();
                    secondOperation.run();
                }));
                assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertAdvisoryLockWait(firstPid.get(), secondPid.get());
                assertThat(second.isDone()).isFalse();
                releaseFirst.countDown();
                first.get(10, TimeUnit.SECONDS);
                try {
                    second.get(10, TimeUnit.SECONDS);
                    return null;
                } catch (ExecutionException failed) {
                    return failed.getCause();
                }
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    private void assertAdvisoryLockWait(int blockerPid, int waiterPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        LockWait observed = null;
        while (System.nanoTime() < deadline) {
            observed = jdbc.queryForObject("""
                    select wait_event_type, wait_event,
                           exists(select 1 from unnest(pg_blocking_pids(pid)) blocker where blocker = ?) as blocked_by_first
                    from pg_stat_activity where pid = ?
                    """, (row, number) -> new LockWait(
                    row.getString("wait_event_type"), row.getString("wait_event"), row.getBoolean("blocked_by_first")),
                    blockerPid, waiterPid);
            if (observed != null && observed.blockedByFirst() && "advisory".equals(observed.waitEvent())) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10); // Polls PostgreSQL's observed lock state, not elapsed-time ordering.
        }
        assertThat(observed).as("the second proxied service call must wait on the first Config advisory lock")
                .isEqualTo(new LockWait("Lock", "advisory", true));
    }

    private int backendPid() {
        return jdbc.queryForObject("select pg_backend_pid()", Integer.class);
    }

    private void apply(UUID materialization, Scope scope) {
        rules.transitionMaterializationStatus(
                materialization, new DomainRuleStatusTransitionRequest("applied", null), scope.principal());
    }

    private void retire(UUID definition, Scope scope) {
        rules.transitionDefinitionStatus(
                definition, new DomainRuleDefinitionStatusTransitionRequest("retired", null), scope.principal());
    }

    private void publish(UUID definition, List<UUID> orderedMaterializations, Scope scope) {
        assertThat(rules.publish(
                new DomainRulePublicationRequest(definition, orderedMaterializations, true, null),
                scope.principal()).publicationStatus()).isEqualTo("published");
    }

    private Scope scope() {
        String tenant = "tenant-" + UUID.randomUUID();
        return new Scope(tenant, new DomainRuleGovernancePrincipal(tenant, "release-operator", ENVIRONMENT));
    }

    private UUID definition(Scope scope, String resourceSuffix) {
        UUID id = UUID.randomUUID();
        String resource = "resource." + resourceSuffix + "." + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                insert into domain_rule_definition
                    (id, tenant_id, environment, rule_key, version, rule_type, status, resource_key,
                     created_by_type, created_by, approved_by, approved_at, activated_at)
                values (?, ?, ?, ?, 1, 'visual_guidance', 'active', ?, 'authenticated', 'release-operator',
                        'release-operator', now(), now())
                """, id, scope.tenant(), ENVIRONMENT, resource + ".rule", resource);
        return id;
    }

    private UUID materialization(Scope scope, UUID definition, String target, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into domain_rule_materialization
                    (id, tenant_id, environment, rule_definition_id, materialization_key, target_layer,
                     target_artifact_type, target_artifact_key, status, applied_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, case when ? = 'applied' then now() else null end)
                """, id, scope.tenant(), ENVIRONMENT, definition, id.toString(), LAYER, TYPE, target, status, status);
        return id;
    }

    private String status(String table, UUID id) {
        // The table name is selected only from two literals at call sites.
        return jdbc.queryForObject("select status from " + table + " where id = ?", String.class, id);
    }

    private Boolean everApplied(UUID id) {
        return jdbc.queryForObject("select ever_applied from domain_rule_materialization where id = ?", Boolean.class, id);
    }

    private long eventCount(UUID materialization, String type) {
        return jdbc.queryForObject("""
                select count(*) from domain_rule_event where materialization_id = ? and event_type = ?
                """, Long.class, materialization, type);
    }

    private List<UUID> appliedHeads(Scope scope, String target) {
        return jdbc.query("""
                select id from domain_rule_materialization
                where tenant_id = ? and environment = ? and target_layer = ? and target_artifact_type = ?
                  and target_artifact_key = ? and status = 'applied'
                """, (row, number) -> row.getObject(1, UUID.class),
                scope.tenant(), ENVIRONMENT, LAYER, TYPE, target);
    }

    private void assertNoIneligibleHead(Scope scope) {
        assertThat(jdbc.queryForObject("""
                select count(*) from domain_rule_materialization m
                join domain_rule_definition d on d.id = m.rule_definition_id
                where m.tenant_id = ? and m.environment = ? and m.status = 'applied' and d.status <> 'active'
                """, Long.class, scope.tenant(), ENVIRONMENT)).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out holding the first Config transaction");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the first Config transaction", interrupted);
        }
    }

    @Test
    void readerObservesInitialAbsenceDraftApplicationWithdrawalAndReplacement() throws Exception {
        Scope scope = scope();
        var target = approvalTarget();
        var initial = resolution(scope, target);
        assertThat(initial.resolutionState()).isEqualTo(OperationalPolicyResolution.State.NEVER_APPLIED);
        UUID first = policy(scope, target, "BLOCK", false);
        assertThat(resolution(scope, target).resolutionFingerprint()).isEqualTo(initial.resolutionFingerprint());
        apply(first, scope);
        var active = resolution(scope, target);
        assertThat(active.resolutionState()).isEqualTo(OperationalPolicyResolution.State.ELIGIBLE_APPLIED_HEAD);
        assertThat(active.policy().effect()).isEqualTo(OperationalPolicyResolution.Effect.BLOCK);
        assertThat(active.policy().payload().at("/approvalPolicy/condition/</1").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("1.0000000000000000000000001"));
        assertThat(resolution(scope, target).resolutionFingerprint()).isEqualTo(active.resolutionFingerprint());
        UUID replacement = policy(scope, target, "ALLOW", false);
        assertThat(resolution(scope, target).resolutionFingerprint()).isEqualTo(active.resolutionFingerprint());
        UUID definition = jdbc.queryForObject("select rule_definition_id from domain_rule_materialization where id=?", UUID.class, first);
        retire(definition, scope);
        var withdrawn = resolution(scope, target);
        assertThat(withdrawn.resolutionState()).isEqualTo(OperationalPolicyResolution.State.PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD);
        assertThat(withdrawn.policy()).isNull();
        assertThat(withdrawn.resolutionFingerprint()).isNotEqualTo(active.resolutionFingerprint());
        apply(replacement, scope);
        var allowed = resolution(scope, target);
        assertThat(allowed.resolutionState()).isEqualTo(OperationalPolicyResolution.State.ELIGIBLE_APPLIED_HEAD);
        assertThat(allowed.policy().effect()).isEqualTo(OperationalPolicyResolution.Effect.ALLOW);
        assertThat(allowed.resolutionFingerprint()).isNotEqualTo(active.resolutionFingerprint());
        assertThat(everApplied(first)).isTrue();
    }

    @Test
    void readerProvesAllThreeFamiliesThroughPostgresJsonbAndTheRealWriter() throws Exception {
        for (var target : List.of(approvalTarget(),
                new OperationalPolicyTarget("workflow_action", "resource-workflow-action", "hr.absences:plan-coverage"),
                new OperationalPolicyTarget("backend_validation", "resource-validation", "operations.participants"))) {
            Scope scope = scope();
            policy(scope, target, "ALLOW", true);
            var resolved = resolution(scope, target);
            assertThat(resolved.resolutionState()).describedAs(target.toString()).isEqualTo(OperationalPolicyResolution.State.ELIGIBLE_APPLIED_HEAD);
            assertThat(resolved.policy().effect()).isEqualTo(OperationalPolicyResolution.Effect.ALLOW);
        }
    }

    @Test
    void draftReversionNeverErasesTheFactOfApplication() throws Exception {
        Scope scope = scope();
        UUID policy = policy(scope, approvalTarget(), "BLOCK", true);
        rules.transitionMaterializationStatus(policy, new DomainRuleStatusTransitionRequest("reverted", null), scope.principal());
        rules.transitionMaterializationStatus(policy, new DomainRuleStatusTransitionRequest("draft", null), scope.principal());
        assertThat(everApplied(policy)).isTrue();
        assertThat(resolution(scope, approvalTarget()).resolutionState())
                .isEqualTo(OperationalPolicyResolution.State.PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD);
    }

    @Test
    void readerSeparatesTenantEnvironmentAndExactTargetWithoutDefaults() throws Exception {
        Scope scope = scope();
        policy(scope, approvalTarget(), "ALLOW", true);
        assertThat(resolution(scope(), approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.NEVER_APPLIED);
        Scope otherEnvironment = new Scope(scope.tenant(), new DomainRuleGovernancePrincipal(scope.tenant(), "release-operator", "other"));
        assertThat(resolution(otherEnvironment, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.NEVER_APPLIED);
        assertThat(resolution(scope, new OperationalPolicyTarget("approval_policy", "resource-action-approval", "hr.orders:other")).resolutionState())
                .isEqualTo(OperationalPolicyResolution.State.NEVER_APPLIED);
    }

    @Test
    void malformedPayloadSourceHashOrInactiveDefinitionCannotAdmitAHead() throws Exception {
        for (String mutation : List.of("materialized_payload='{}'::jsonb", "source_hash='tampered'")) {
            Scope scope = scope();
            UUID policy = policy(scope, approvalTarget(), "ALLOW", true);
            jdbc.update("update domain_rule_materialization set " + mutation + " where id=?", policy);
            assertThat(resolution(scope, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE);
        }
        Scope scope = scope();
        UUID policy = policy(scope, approvalTarget(), "ALLOW", true);
        jdbc.update("update domain_rule_definition set status='retired' where id=(select rule_definition_id from domain_rule_materialization where id=?)", policy);
        assertThat(resolution(scope, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE);
    }

    @Test
    void legacyUnknownAndOrphanAppliedEvidenceNeverBecomeInitialAbsence() throws Exception {
        Scope unknown = scope();
        UUID draft = policy(unknown, approvalTarget(), "BLOCK", false);
        // Model an inconclusive migrated record; V63 preserves null and forbids clearing it later.
        jdbc.update("update domain_rule_materialization set ever_applied=null where id=?", draft);
        assertThat(resolution(unknown, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE);
        Scope orphan = scope();
        UUID definition = definition(orphan, "orphan");
        jdbc.update("""
                insert into domain_rule_event(id,tenant_id,environment,rule_definition_id,event_type,occurred_at,
                  target_layer,target_artifact_type,target_artifact_key,actor_type,actor,summary)
                values (?,?,?,?,'materialization.applied',now(),'approval_policy','resource-action-approval',?,'authenticated','publisher','Retained application evidence')
                """, UUID.randomUUID(), orphan.tenant(), ENVIRONMENT, definition, approvalTarget().targetArtifactKey());
        assertThat(resolution(orphan, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD);
        jdbc.update("""
                insert into domain_rule_event(id,tenant_id,environment,rule_definition_id,materialization_id,event_type,occurred_at,
                  target_layer,target_artifact_type,target_artifact_key,actor_type,actor,summary)
                values (?,?,?,?,?,'materialization.applied',now(),'approval_policy','resource-action-approval',?,'authenticated','publisher','Retained application evidence')
                """, UUID.randomUUID(), unknown.tenant(), ENVIRONMENT,
                jdbc.queryForObject("select rule_definition_id from domain_rule_materialization where id=?", UUID.class, draft),
                draft, approvalTarget().targetArtifactKey());
        assertThat(resolution(unknown, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE);
    }

    @Test
    void aVerifiableLegacyPolicyWithoutEffectStillBlocksAndOldUnverifiableHashIsInconsistent() throws Exception {
        Scope scope = scope();
        UUID policy = policy(scope, approvalTarget(), "BLOCK", false);
        var mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            var materialization = materializations.findById(policy).orElseThrow();
            var definition = materialization.getRuleDefinition();
            try {
                var parameters = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(definition.getParameters());
                parameters.remove("approvalPolicy");
                definition.setParameters(parameters.toString());
                var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(materialization.getMaterializedPayload());
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("approvalPolicy")).remove("effect");
                materialization.setMaterializedPayload(payload.toString());
                materialization.setSourceHash(org.praxisplatform.config.service.DomainRuleMaterializationFingerprint.sha256(
                        definition, approvalTarget().targetLayer(), approvalTarget().targetArtifactType(),
                        approvalTarget().targetArtifactKey(), null, null, payload));
                // Historical import fixture, deliberately outside the new explicit-effect writer.
                materialization.setStatus("applied");
                materialization.setEverApplied(true);
                definitions.save(definition);
                materializations.save(materialization);
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        var legacy = resolution(scope, approvalTarget());
        assertThat(legacy.resolutionState()).isEqualTo(OperationalPolicyResolution.State.ELIGIBLE_APPLIED_HEAD);
        assertThat(legacy.policy().effect()).isEqualTo(OperationalPolicyResolution.Effect.BLOCK);
        jdbc.update("update domain_rule_materialization set source_hash='legacy:unverifiable' where id=?", policy);
        assertThat(resolution(scope, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE);
    }

    @Test
    void aLinkedApplicationEventWithADifferentKeyOrHashMakesAnOtherwiseValidHeadInconsistent() throws Exception {
        for (boolean wrongKey : List.of(true, false)) {
            Scope scope = scope();
            UUID policy = policy(scope, approvalTarget(), "ALLOW", true);
            jdbc.update("""
                    insert into domain_rule_event(id,tenant_id,environment,rule_definition_id,materialization_id,
                      materialization_key,source_hash,event_type,occurred_at,target_layer,target_artifact_type,target_artifact_key,
                      actor_type,actor,summary)
                    select ?,tenant_id,environment,rule_definition_id,id,
                      case when ? then 'mismatched-key' else materialization_key end,
                      case when ? then source_hash else 'mismatched-hash' end,
                      'materialization.applied',now(),target_layer,target_artifact_type,target_artifact_key,
                      'authenticated','publisher','Ambiguous retained application'
                    from domain_rule_materialization where id=?
                    """, UUID.randomUUID(), wrongKey, wrongKey, policy);
            assertThat(resolution(scope, approvalTarget()).resolutionState())
                    .isEqualTo(OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE);
        }
    }

    @Test
    void readerSeesOneCommittedSnapshotWhileRetirementIsInFlight() throws Exception {
        Scope scope = scope();
        UUID policy = policy(scope, approvalTarget(), "ALLOW", true);
        UUID definition = jdbc.queryForObject("select rule_definition_id from domain_rule_materialization where id=?", UUID.class, policy);
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var retirement = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
                retire(definition, scope);
                definitions.flush();
                changed.countDown();
                await(commit);
            }));
            try {
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(resolution(scope, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.ELIGIBLE_APPLIED_HEAD);
            } finally {
                commit.countDown();
            }
            retirement.get(10, TimeUnit.SECONDS);
        }
        assertThat(resolution(scope, approvalTarget()).resolutionState()).isEqualTo(OperationalPolicyResolution.State.PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD);
    }

    @Test
    void dirtyExternalPersistenceContextsAreRejectedBeforeTheLifecycleCanFlushThem() throws Exception {
        for (boolean publication : List.of(false, true)) {
            Scope scope = scope();
            UUID definition = definition(scope, "dirty");
            UUID draft = materialization(scope, definition, "orders:dirty", "draft");
            CountDownLatch cached = new CountDownLatch(1);
            CountDownLatch retired = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var caller = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
                    var managed = definitions.findById(definition).orElseThrow();
                    managed.setDefinition("{\"summary\":\"pending external change\"}");
                    cached.countDown();
                    await(retired);
                    if (publication) publish(definition, List.of(draft), scope); else apply(draft, scope);
                }));
                try {
                    assertThat(cached.await(10, TimeUnit.SECONDS)).isTrue();
                    executor.submit(() -> retire(definition, scope)).get(10, TimeUnit.SECONDS);
                    retired.countDown();
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> caller.get(10, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ConfigurationIngestionException.class);
                } finally { retired.countDown(); }
            }
            assertThat(status("domain_rule_definition", definition)).isEqualTo("retired");
            assertThat(status("domain_rule_materialization", draft)).isEqualTo("draft");
        }
    }

    @Test
    void lifecycleRejectsRepeatableReadInsteadOfUsingAnOldSnapshot() {
        Scope scope = scope();
        UUID definition = definition(scope, "isolation");
        UUID draft = materialization(scope, definition, "orders:rr", "draft");
        var repeatable = new TransactionTemplate(transactionManager);
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repeatable.executeWithoutResult(ignored -> apply(draft, scope)))
                .isInstanceOf(ConfigurationIngestionException.class).hasMessageContaining("READ COMMITTED");
        assertThat(status("domain_rule_materialization", draft)).isEqualTo("draft");
    }

    private OperationalPolicyTarget approvalTarget() {
        return new OperationalPolicyTarget("approval_policy", "resource-action-approval", "hr.orders:approve");
    }

    private OperationalPolicyResolution resolution(Scope scope, OperationalPolicyTarget target) {
        return new OperationalPolicyService(materializations, rules, transactionManager, java.time.Clock.systemUTC())
                .resolveOperationalPolicy(target, scope.principal());
    }

    private UUID policy(Scope scope, OperationalPolicyTarget target, String effect, boolean apply) throws Exception {
        String ruleType = switch (target.targetLayer()) {
            case "approval_policy" -> "approval_policy";
            case "workflow_action" -> "workflow_action_policy";
            default -> "validation";
        };
        String slot = switch (target.targetLayer()) {
            case "approval_policy" -> "approvalPolicy";
            case "workflow_action" -> "availabilityPolicy";
            default -> "validationPolicy";
        };
        var params = new ObjectMapper().createObjectNode();
        params.putObject(slot).put("effect", effect);
        if (target.actionId() != null) params.put("actionId", target.actionId());
        UUID definition = UUID.randomUUID();
        jdbc.update("""
                insert into domain_rule_definition
                  (id,tenant_id,environment,rule_key,version,rule_type,status,resource_key,definition,parameters,governance,
                   created_by_type,created_by,approved_by,approved_at,activated_at)
                values (?,?,?,?,1,?,'active',?,cast(? as jsonb),cast(? as jsonb),'{}'::jsonb,
                  'authenticated','author','reviewer',now(),now())
                """, definition, scope.tenant(), ENVIRONMENT, "policy." + UUID.randomUUID(), ruleType, target.resourceKey(),
                "{\"summary\":\"Operational policy\",\"decimal\":1.0000000000000000000000001,\"ordered\":{\"b\":2,\"a\":1}}", params.toString());
        if ("BLOCK".equals(effect)) {
            jdbc.update("update domain_rule_definition set condition=cast(? as jsonb) where id=?",
                    "{\"<\":[{\"var\":\"amount\"},1.0000000000000000000000001]}", definition);
        }
        UUID materialization = rules.createMaterialization(new org.praxisplatform.config.dto.DomainRuleMaterializationRequest(
                definition, "policy." + UUID.randomUUID(), target.targetLayer(), target.targetArtifactType(),
                target.targetArtifactKey(), null, null, null, "draft", null, null, null), scope.principal()).id();
        if (apply) apply(materialization, scope);
        return materialization;
    }

    private static EmbeddedPostgres startPostgres() {
        EmbeddedPostgres postgres = null;
        Path migrations = null;
        try {
            postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
            JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
            jdbc.execute("create table domain_catalog_release (id uuid primary key)");
            jdbc.execute("create table domain_knowledge_change_set (id uuid primary key)");
            jdbc.execute("create table domain_knowledge_evidence (subject_type varchar(64))");
            migrations = Files.createTempDirectory("praxis-rule-lifecycle-migrations-");
            for (String name : List.of(
                    "V20__create_domain_shared_rule_layer.sql",
                    "V22__expand_domain_rule_constraints_for_selection_eligibility.sql",
                    "V23__expand_domain_rule_constraints_for_workflow_action.sql",
                    "V24__expand_domain_rule_constraints_for_approval_policy.sql",
                    "V25__create_domain_rule_event.sql",
                    "V36__persist_rule_definition_approvals.sql",
                    "V37__allow_authenticated_domain_rule_event_actors.sql",
                    "V41__allow_authenticated_domain_rule_materialization_actors.sql",
                    "V45__add_backend_reactive_determination_materialization.sql",
                    "V46__create_domain_rule_change_workspaces.sql",
                    "V47__create_domain_rule_test_runs.sql",
                    "V48__add_domain_rule_workspace_reviews.sql",
                    "V49__add_domain_rule_workspace_promotion.sql",
                    "V55__enforce_single_applied_materialization_head.sql",
                    "V56__add_policy_test_run_semantic_assertions.sql",
                    "V57__add_policy_test_run_provenance.sql",
                    "V58__govern_policy_test_run_idempotency_and_baseline_lane.sql",
                    "V62__add_governed_color_palette_materialization.sql",
                    "V63__preserve_domain_rule_application_history.sql")) {
                Files.copy(Path.of("src/main/resources/db/migration", name), migrations.resolve(name));
            }
            Flyway.configure().dataSource(postgres.getPostgresDatabase())
                    .locations("filesystem:" + migrations)
                    .baselineOnMigrate(true).baselineVersion("19").load().migrate();
            return postgres;
        } catch (Exception failure) {
            if (postgres != null) try { postgres.close(); } catch (IOException ignored) { }
            throw new ExceptionInInitializerError(failure);
        } finally {
            if (migrations != null) try (var files = Files.walk(migrations)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            } catch (IOException ignored) {
                // Preserve the migration/initialization failure, if any.
            }
        }
    }

    private record Scope(String tenant, DomainRuleGovernancePrincipal principal) {}
    private record LockWait(String waitType, String waitEvent, boolean blockedByFirst) {}
}
