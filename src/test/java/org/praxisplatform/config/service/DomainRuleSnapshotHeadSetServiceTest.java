package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual JDBC query/mapping with synthetic content; PostgreSQL MVCC has a separate Neon gate. */
@Tag("unit")
class DomainRuleSnapshotHeadSetServiceTest {
    JdbcTemplate jdbc; DomainRuleSnapshotHeadSetService reader;
    @BeforeEach void prepare() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:headset_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table domain_rule_snapshot(id uuid primary key, tenant_id varchar, environment varchar, snapshot_key varchar, rule_set_key varchar, operation_key varchar, rule_set_version int, publication_revision int, snapshot_payload clob, content_hash varchar, composition_manifest clob, composition_digest varchar)");
        jdbc.execute("create table domain_rule_snapshot_head(operation_key varchar, tenant_id varchar, environment varchar, rule_set_key varchar, active_snapshot_id uuid, head_etag uuid, activation_revision bigint,withdrawn boolean default false not null)");
        reader = new DomainRuleSnapshotHeadSetService(new NamedParameterJdbcTemplate(source), HeadSetTestFixtures.JSON, DomainRuleImplementationCatalog.denyAll());
    }
    @Test void completeSelectionUsesTheActualQueryAndCanonicalVerifier() throws Exception {
        insert("create"); insert("update");
        var selected = reader.findActive(scope()).orElseThrow();
        assertThat(selected.heads()).containsOnlyKeys("create", "update");
        assertThat(selected.fingerprint()).hasSize(64);
    }
    @Test void missingMemberDoesNotReturnTheAvailableSubset() throws Exception {
        insert("create"); assertThat(reader.findActive(scope())).isEmpty();
    }
    @Test void corruptionOrMissingApprovalCannotBecomeAnActiveHead() throws Exception {
        insert("create"); insert("update");
        jdbc.update("update domain_rule_snapshot set content_hash=? where operation_key='update'", "0".repeat(64));
        assertThatThrownBy(() -> reader.findActive(scope())).isInstanceOf(IllegalStateException.class).hasMessageContaining("hash verification");
    }
    @Test void danglingHeadIsAnInvalidReadRatherThanAbsence() {
        jdbc.update("insert into domain_rule_snapshot_head(operation_key,tenant_id,environment,rule_set_key,active_snapshot_id,head_etag,activation_revision) values('create','2','stage','family',?,?,1)", UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> reader.findActive(scope())).isInstanceOf(IllegalStateException.class).hasMessageContaining("missing immutable");
    }
    @Test void anotherTenantCannotCompleteTheSelection() throws Exception {
        insert("create"); insert("update");
        jdbc.update("update domain_rule_snapshot_head set tenant_id='3' where operation_key='update'");
        assertThat(reader.findActive(scope())).isEmpty();
    }
    private PublishedRuleSnapshotHeadSetScope scope() { return new PublishedRuleSnapshotHeadSetScope("2", "stage", "family", List.of("create", "update")); }
    private void insert(String operation) throws Exception {
        var s = HeadSetTestFixtures.snapshot(operation);
        jdbc.update("insert into domain_rule_snapshot values(?,?,?,?,?,?,?,?,?,?,?,?)", s.getId(), s.getTenantId(), s.getEnvironment(), s.getSnapshotKey(), s.getRuleSetKey(), operation,
                s.getRuleSetVersion(), s.getPublicationRevision(), s.getSnapshotPayload(), s.getContentHash(), s.getCompositionManifest(), s.getCompositionDigest());
        jdbc.update("insert into domain_rule_snapshot_head(operation_key,tenant_id,environment,rule_set_key,active_snapshot_id,head_etag,activation_revision) values(?,?,?,?,?,?,?)", operation, "2", "stage", "family", s.getId(), UUID.randomUUID(), 1);
    }
}
