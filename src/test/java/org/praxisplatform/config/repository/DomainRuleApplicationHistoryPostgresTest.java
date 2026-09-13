package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.OptimisticLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Canonical Flyway upgrade, SQL guards and real Hibernate versioning against the same PostgreSQL schema. */
@Tag("integration")
class DomainRuleApplicationHistoryPostgresTest {
    private static EmbeddedPostgres postgres;
    private static Path migrations;
    private static JdbcTemplate jdbc;
    private static SessionFactory sessions;
    private static UUID legacyApplied, legacyTimestamp, legacyEvent, legacyUnknown, mismatchedEvent;

    @BeforeAll
    static void start() throws Exception {
        migrations = Files.createTempDirectory("praxis-rule-history-migrations-");
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        // Unrelated FK prerequisites only. The rule tables, indexes and triggers all come from canonical migrations.
        jdbc.execute("create table domain_catalog_release (id uuid primary key)");
        jdbc.execute("create table domain_knowledge_change_set (id uuid primary key)");
        jdbc.execute("create table domain_knowledge_evidence (subject_type varchar(64))");
        for (String file : List.of(
                "V20__create_domain_shared_rule_layer.sql",
                "V22__expand_domain_rule_constraints_for_selection_eligibility.sql",
                "V23__expand_domain_rule_constraints_for_workflow_action.sql",
                "V24__expand_domain_rule_constraints_for_approval_policy.sql",
                "V25__create_domain_rule_event.sql",
                "V37__allow_authenticated_domain_rule_event_actors.sql",
                "V41__allow_authenticated_domain_rule_materialization_actors.sql",
                "V45__add_backend_reactive_determination_materialization.sql",
                "V55__enforce_single_applied_materialization_head.sql",
                "V62__add_governed_color_palette_materialization.sql",
                "V63__preserve_domain_rule_application_history.sql")) {
            Files.copy(Path.of("src/main/resources/db/migration", file), migrations.resolve(file));
        }
        var baseline = Flyway.configure().dataSource(postgres.getPostgresDatabase())
                .locations("filesystem:" + migrations).baselineOnMigrate(true).baselineVersion("19").target("62").load();
        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(10);
        legacyApplied = materialization(definition(), "applied");
        legacyTimestamp = materialization(definition(), "draft");
        jdbc.update("update domain_rule_materialization set applied_at=now() where id=?", legacyTimestamp);
        UUID eventDefinition = definition();
        legacyEvent = materialization(eventDefinition, "reverted");
        event(eventDefinition, legacyEvent);
        legacyUnknown = materialization(definition(), "superseded");
        UUID mismatchDefinition = definition();
        mismatchedEvent = materialization(mismatchDefinition, "draft");
        UUID mismatch = event(mismatchDefinition, mismatchedEvent);
        jdbc.update("update domain_rule_event set tenant_id='other-tenant' where id=?", mismatch);
        var upgrade = Flyway.configure().dataSource(postgres.getPostgresDatabase())
                .locations("filesystem:" + migrations).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();

        var configuration = new Configuration()
                .addAnnotatedClass(DomainRuleMaterialization.class).addAnnotatedClass(DomainRuleDefinition.class)
                .addAnnotatedClass(DomainCatalogRelease.class).addAnnotatedClass(DomainKnowledgeChangeSet.class)
                .setProperty("hibernate.hbm2ddl.auto", "none");
        configuration.getProperties().put("hibernate.connection.datasource", postgres.getPostgresDatabase());
        sessions = configuration.buildSessionFactory();
    }

    @AfterAll
    static void stop() throws Exception {
        try { if (sessions != null) sessions.close(); }
        finally {
            try { if (postgres != null) postgres.close(); }
            finally {
                if (migrations != null) try (var paths = Files.walk(migrations)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    @Test
    void backfillPreservesPositiveAndInconclusiveEvidenceWithoutScopeGuessing() {
        assertThat(history(legacyApplied)).isTrue();
        assertThat(history(legacyTimestamp)).isTrue();
        assertThat(history(legacyEvent)).isTrue();
        assertThat(history(legacyUnknown)).isNull();
        assertThat(history(mismatchedEvent)).isNull();
        assertThat(version(legacyApplied)).isZero();
    }

    @Test
    void applicationAndEveryLaterStatusPreserveHistoryAndAdvanceVersion() {
        UUID id = materialization(definition(), "draft");
        assertThat(history(id)).isFalse();
        long expectedVersion = 0;
        for (String status : List.of("pending_review", "applied", "reverted", "draft", "applied", "superseded")) {
            jdbc.update("update domain_rule_materialization set status=? where id=?", status, id);
            assertThat(version(id)).isEqualTo(++expectedVersion);
            assertThat(history(id)).isEqualTo(expectedVersion > 1);
        }
        UUID directlyApplied = materialization(definition(), "applied");
        assertThat(history(directlyApplied)).isTrue();
        assertThat(version(directlyApplied)).isZero();
    }

    @Test
    void timestampEvidenceAlsoProtectsNewAndUpdatedRowsOutsideAppliedStatus() {
        UUID id = materialization(definition(), "draft");
        jdbc.update("update domain_rule_materialization set applied_at=now() where id=?", id);
        assertThat(history(id)).isTrue();
        rejects("delete from domain_rule_materialization where id='" + id + "'", "cannot be deleted");
        UUID inserted = UUID.randomUUID();
        jdbc.update("insert into domain_rule_materialization (id,rule_definition_id,materialization_key,target_layer,target_artifact_type,target_artifact_key,status,applied_at) values (?,?,?,'backend_validation','resource-validation',?,'reverted',now())",
                inserted, definition(), inserted.toString(), inserted.toString());
        assertThat(history(inserted)).isTrue();
    }

    @Test
    void supersededImportsCannotClaimNeverAppliedButLegacyUnknownRemainsUnknown() {
        UUID inserted = materialization(definition(), "superseded");
        assertThat(history(inserted)).isTrue();
        rejects("delete from domain_rule_materialization where id='" + inserted + "'", "cannot be deleted");
        UUID updated = materialization(definition(), "draft");
        jdbc.update("update domain_rule_materialization set status='superseded' where id=?", updated);
        assertThat(history(updated)).isTrue();
        rejects("delete from domain_rule_materialization where id='" + updated + "'", "cannot be deleted");
        jdbc.update("update domain_rule_materialization set status='superseded' where id=?", legacyUnknown);
        assertThat(history(legacyUnknown)).isNull();
    }

    @Test
    void historyCannotResetIncludingUnknownLegacy() {
        UUID id = materialization(definition(), "applied");
        for (String reset : List.of("false", "null")) {
            rejects("update domain_rule_materialization set ever_applied=" + reset + " where id='" + id + "'", "cannot be reset");
        }
        rejects("update domain_rule_materialization set ever_applied=false where id='" + legacyUnknown + "'", "cannot be reset");
        assertThat(history(id)).isTrue();
        assertThat(history(legacyUnknown)).isNull();
    }

    @Test
    void protectsDeletionAndDefinitionCascadeButAllowsNeverAppliedDraftCleanup() {
        UUID definition = definition();
        UUID id = materialization(definition, "applied");
        jdbc.update("update domain_rule_materialization set status='reverted' where id=?", id);
        rejects("delete from domain_rule_materialization where id='" + id + "'", "cannot be deleted");
        rejects("delete from domain_rule_definition where id='" + definition + "'", "cannot be deleted");
        rejects("delete from domain_rule_materialization where id='" + legacyUnknown + "'", "cannot be deleted");
        UUID unused = definition();
        materialization(unused, "draft");
        assertThat(jdbc.update("delete from domain_rule_definition where id=?", unused)).isEqualTo(1);
        assertThat(history(id)).isTrue();
    }

    @Test
    void historicalIdentityCannotMoveAcrossTenantEnvironmentTargetOrDefinition() {
        UUID id = materialization(definition(), "applied");
        UUID otherDefinition = definition();
        for (UUID protectedId : List.of(id, legacyUnknown)) {
            for (String change : List.of("id='" + UUID.randomUUID() + "'", "tenant_id='other'", "environment='prod'",
                    "rule_definition_id='" + otherDefinition + "'", "materialization_key='different'",
                    "target_layer='workflow_action'", "target_artifact_type='resource-workflow-action'",
                    "target_artifact_key='different'")) {
                rejects("update domain_rule_materialization set " + change + " where id='" + protectedId + "'", "identity is immutable");
            }
        }
    }

    @Test
    void appliedEventIsImmutableAndProtectsOrphanHistoryFromCascade() {
        UUID definition = definition();
        UUID orphan = event(definition, null);
        rejects("delete from domain_rule_event where id='" + orphan + "'", "cannot be deleted");
        rejects("delete from domain_rule_definition where id='" + definition + "'", "cannot be deleted");
        for (String change : List.of("event_type='materialization.created'", "tenant_id='other'", "summary='changed'",
                "safe_metadata='{}'", "materialization_id=null", "target_artifact_key='other'", "actor='other'")) {
            rejects("update domain_rule_event set " + change + " where id='" + orphan + "'", "event is immutable");
        }
    }

    @Test
    void rejectsVersionResetJumpNullAndNonzeroInsert() {
        UUID id = materialization(definition(), "draft");
        jdbc.update("update domain_rule_materialization set row_version=1 where id=?", id);
        for (String version : List.of("0", "3", "null")) {
            rejects("update domain_rule_materialization set row_version=" + version + " where id='" + id + "'", "advance by one");
        }
        assertThat(version(id)).isEqualTo(1);
        UUID inserted = UUID.randomUUID();
        UUID definition = definition();
        rejects("insert into domain_rule_materialization (id,rule_definition_id,materialization_key,target_layer,target_artifact_type,target_artifact_key,status,row_version) values ('"
                + inserted + "','" + definition + "','" + inserted + "','backend_validation','resource-validation','" + inserted + "','draft',5)",
                "version must be zero");
    }

    @Test
    void rollbackRestoresStatusMarkerVersionAndEventAsAUnit() throws Exception {
        UUID definition = definition();
        UUID id = materialization(definition, "draft");
        try (var connection = postgres.getPostgresDatabase().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("update domain_rule_materialization set status='applied' where id='" + id + "'");
                statement.executeUpdate("insert into domain_rule_event (id,rule_definition_id,event_type,summary,materialization_id) values ('"
                        + UUID.randomUUID() + "','" + definition + "','materialization.applied','Applied','" + id + "')");
            }
            connection.rollback();
        }
        assertThat(history(id)).isFalse();
        assertThat(version(id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from domain_rule_event where materialization_id=?", Long.class, id)).isZero();
    }

    @Test
    void truncateCannotEraseHistoryIncludingThroughCascade() {
        rejects("truncate domain_rule_materialization cascade", "cannot be truncated");
        rejects("truncate domain_rule_definition cascade", "cannot be truncated");
        rejects("truncate domain_rule_event", "cannot be truncated");
        assertThat(history(legacyApplied)).isTrue();
    }

    @Test
    void springDataSaveWithAssignedIdAndFlushUsesPersistAndPreservesUnknownHistory() {
        var entityManager = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(sessions);
        var repository = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entityManager)
                .getRepository(DomainRuleMaterializationRepository.class);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.orm.jpa.JpaTransactionManager(sessions));
        UUID definition = definition();
        UUID id = transactions.execute(status -> {
            var entity = DomainRuleMaterialization.builder().id(UUID.randomUUID()).tenantId("test").environment("local")
                    .ruleDefinition(entityManager.getReference(DomainRuleDefinition.class, definition))
                    .materializationKey(UUID.randomUUID().toString()).targetLayer("backend_validation")
                    .targetArtifactType("resource-validation").targetArtifactKey(UUID.randomUUID().toString())
                    .status("draft").everApplied(null).build();
            assertThat(repository.saveAndFlush(entity)).isSameAs(entity);
            assertThat(entity.getRowVersion()).isZero();
            assertThat(entity.getEverApplied()).isFalse();
            entity.setStatus("pending_review");
            repository.saveAndFlush(entity);
            assertThat(entity.getRowVersion()).isEqualTo(1L);
            return entity.getId();
        });
        assertThat(version(id)).isEqualTo(1L);
        transactions.executeWithoutResult(status -> {
            var old = repository.findById(legacyUnknown).orElseThrow();
            assertThat(old.getEverApplied()).isNull();
            old.setValidationResult("{\"reviewed\":true}");
            repository.saveAndFlush(old);
            assertThat(old.getEverApplied()).isNull();
        });
        assertThat(history(legacyUnknown)).isNull();
    }

    @Test
    void existingUniqueHeadConstraintStillAllowsAtomicReplacement() throws Exception {
        UUID first = materialization(definition(), "applied");
        UUID second = materialization(definition(), "draft");
        String target = jdbc.queryForObject("select target_artifact_key from domain_rule_materialization where id=?", String.class, first);
        jdbc.update("update domain_rule_materialization set target_artifact_key=? where id=?", target, second);
        rejects("update domain_rule_materialization set status='applied' where id='" + second + "'", "uq_domain_rule_materialization_applied_target_head");
        assertThat(history(second)).isFalse();
        try (var connection = postgres.getPostgresDatabase().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("update domain_rule_materialization set status='superseded' where id='" + first + "'");
                statement.executeUpdate("update domain_rule_materialization set status='applied' where id='" + second + "'");
            }
            connection.commit();
        }
        assertThat(history(first)).isTrue();
        assertThat(history(second)).isTrue();
    }

    @Test
    void hibernateInsertAndConsecutiveFlushesKeepEntityAndDatabaseVersionsAligned() {
        UUID definition = definition();
        try (var session = sessions.openSession()) {
            var tx = session.beginTransaction();
            var entity = DomainRuleMaterialization.builder().id(UUID.randomUUID()).tenantId("test").environment("local")
                    .ruleDefinition(session.getReference(DomainRuleDefinition.class, definition))
                    .materializationKey(UUID.randomUUID().toString()).targetLayer("backend_validation")
                    .targetArtifactType("resource-validation").targetArtifactKey(UUID.randomUUID().toString())
                    .status("draft").build();
            assertThat(entity.getRowVersion()).isNull();
            session.persist(entity);
            session.flush();
            assertThat(entity.getRowVersion()).isZero();
            assertThat(entity.getEverApplied()).isFalse();
            entity.setStatus("applied");
            session.flush();
            assertThat(entity.getRowVersion()).isEqualTo(1L);
            assertThat(entity.getEverApplied()).isTrue();
            entity.setStatus("reverted");
            session.flush();
            assertThat(entity.getRowVersion()).isEqualTo(2L);
            tx.commit();
            assertThat(version(entity.getId())).isEqualTo(2L);
            assertThat(history(entity.getId())).isTrue();
        }
    }

    @Test
    void twoHibernateWritersCannotLoseAnUpdate() {
        UUID id = materialization(definition(), "draft");
        try (var first = sessions.openSession(); var second = sessions.openSession()) {
            var tx1 = first.beginTransaction();
            var tx2 = second.beginTransaction();
            var left = first.find(DomainRuleMaterialization.class, id);
            var right = second.find(DomainRuleMaterialization.class, id);
            left.setStatus("pending_review");
            tx1.commit();
            right.setStatus("failed");
            assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            tx2.rollback();
        }
        assertThat(version(id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status from domain_rule_materialization where id=?", String.class, id)).isEqualTo("pending_review");
    }

    @Test
    void directSqlUpdateInvalidatesAnAlreadyLoadedHibernateEntity() {
        UUID id = materialization(definition(), "draft");
        try (var session = sessions.openSession()) {
            var tx = session.beginTransaction();
            var entity = session.find(DomainRuleMaterialization.class, id);
            jdbc.update("update domain_rule_materialization set status='applied' where id=?", id);
            entity.setStatus("failed");
            assertThatThrownBy(session::flush).isInstanceOf(OptimisticLockException.class);
            tx.rollback();
        }
        assertThat(history(id)).isTrue();
        assertThat(version(id)).isEqualTo(1L);
    }

    private static UUID definition() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into domain_rule_definition (id,tenant_id,environment,rule_key,version,rule_type,status) values (?,'test','local',?,1,'validation','active')", id, id.toString());
        return id;
    }

    private static UUID materialization(UUID definition, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into domain_rule_materialization (id,tenant_id,environment,rule_definition_id,materialization_key,
                    target_layer,target_artifact_type,target_artifact_key,status)
                values (?,'test','local',?,?,'backend_validation','resource-validation',?,?)
                """, id, definition, id.toString(), id.toString(), status);
        return id;
    }

    private static UUID event(UUID definition, UUID materialization) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into domain_rule_event (id,tenant_id,environment,rule_definition_id,event_type,summary,
                    target_layer,target_artifact_type,target_artifact_key,materialization_id,materialization_key)
                values (?,'test','local',?,'materialization.applied','Applied','backend_validation','resource-validation',?,?,?)
                """, id, definition, materialization == null ? "orphan" : materialization.toString(), materialization,
                materialization == null ? "orphan" : materialization.toString());
        return id;
    }

    private static Boolean history(UUID id) {
        return jdbc.queryForObject("select ever_applied from domain_rule_materialization where id=?", Boolean.class, id);
    }

    private static long version(UUID id) {
        return jdbc.queryForObject("select row_version from domain_rule_materialization where id=?", Long.class, id);
    }

    private static void rejects(String sql, String message) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(message);
    }
}
