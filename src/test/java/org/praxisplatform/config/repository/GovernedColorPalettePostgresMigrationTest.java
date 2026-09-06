package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Executes the canonical rule migrations and palette upgrade on an isolated PostgreSQL instance. */
@Tag("integration")
class GovernedColorPalettePostgresMigrationTest {
    @Test
    void migratesAndPreservesVersionHistoryWithOneAppliedHead() throws Exception {
        Path migrations = Files.createTempDirectory("praxis-palette-migrations-");
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
                var connection = postgres.getPostgresDatabase().getConnection();
                var statement = connection.createStatement()) {
            // Only foreign domain prerequisites are fixtures; rule tables and constraints use canonical DDL.
            statement.execute("create table domain_catalog_release (id uuid primary key)");
            statement.execute("create table domain_knowledge_change_set (id uuid primary key)");
            statement.execute("create table domain_knowledge_evidence (subject_type varchar(64))");
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
                    "V62__add_governed_color_palette_materialization.sql")) {
                Files.copy(Path.of("src/main/resources/db/migration", file), migrations.resolve(file));
            }
            var flyway = Flyway.configure().dataSource(postgres.getPostgresDatabase())
                    .locations("filesystem:" + migrations.toAbsolutePath())
                    .baselineOnMigrate(true).baselineVersion("19").target("55").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(9);
            var upgrade = Flyway.configure().dataSource(postgres.getPostgresDatabase())
                    .locations("filesystem:" + migrations.toAbsolutePath()).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            for (int version : List.of(1, 2)) {
                statement.executeUpdate("""
                        insert into domain_rule_definition (id, tenant_id, environment, rule_key, version, rule_type, status)
                        values ('00000000-0000-0000-0000-00000000000%d', 'test', 'local', 'palette.main', %d,
                                'design_token_palette', 'active')
                        """.formatted(version, version));
                statement.executeUpdate("""
                        insert into domain_rule_materialization
                            (id, tenant_id, environment, rule_definition_id, materialization_key,
                             target_layer, target_artifact_type, target_artifact_key, status, materialized_payload)
                        values ('10000000-0000-0000-0000-00000000000%d', 'test', 'local',
                            '00000000-0000-0000-0000-00000000000%d', 'palette.main:v%d:design_token_catalog:main',
                            'design_token_catalog', 'governed-color-palette', 'main', '%s',
                            '{"paletteKey":"main","displayName":"Main","familyKey":"corporate","variant":{"key":"light","displayName":"Light"},"entries":[{"tokenId":"primary"}]}')
                        """.formatted(version, version, version, version == 1 ? "applied" : "pending_review"));
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    update domain_rule_materialization set status='applied'
                    where id='10000000-0000-0000-0000-000000000002'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_domain_rule_materialization_applied_target_head");
            connection.setAutoCommit(false);
            statement.executeUpdate("update domain_rule_materialization set status='superseded' where status='applied'");
            statement.executeUpdate("update domain_rule_materialization set status='applied' where status='pending_review'");
            connection.commit();
            connection.setAutoCommit(true);
            try (var rows = statement.executeQuery("""
                    select definition.version, materialization.status
                    from domain_rule_materialization materialization
                    join domain_rule_definition definition on definition.id=materialization.rule_definition_id
                    order by definition.version
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getString(2)).isEqualTo("superseded");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.getString(2)).isEqualTo("applied");
                assertThat(rows.next()).isFalse();
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    update domain_rule_materialization set materialized_payload='{}'
                    where status='applied'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_domain_rule_materialization_color_palette_payload");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    update domain_rule_materialization set target_artifact_type='other'
                    where status='applied'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_domain_rule_materialization_color_palette_type");
            statement.executeUpdate("""
                    insert into domain_rule_definition (id, tenant_id, environment, rule_key, version, rule_type, status)
                    values ('00000000-0000-0000-0000-000000000003', 'test', 'local', 'palette.other', 1,
                            'design_token_palette', 'active')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    insert into domain_rule_materialization
                        (id, tenant_id, environment, rule_definition_id, materialization_key,
                         target_layer, target_artifact_type, target_artifact_key, status, materialized_payload)
                    values ('10000000-0000-0000-0000-000000000003', 'test', 'local',
                        '00000000-0000-0000-0000-000000000003', 'palette.other:v1:design_token_catalog:other',
                        'design_token_catalog', 'governed-color-palette', 'other', 'applied',
                        '{"paletteKey":"other","displayName":"Other","familyKey":"corporate","variant":{"key":"light","displayName":"Light"},"entries":[{"tokenId":"primary"}]}')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_domain_rule_materialization_applied_palette_variant");
        } finally {
            try (var paths = Files.walk(migrations)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
