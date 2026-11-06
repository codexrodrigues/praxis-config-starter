package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.contract.*;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.*;

/** Reads authority from one SQL statement, independently of a caller's JPA cache or transaction snapshot. */
public class DomainRuleSnapshotHeadSetService implements PublishedRuleSnapshotHeadSetReader {
    private static final String SQL = """
        select h.operation_key as selected_operation, h.head_etag as selected_etag,
               h.activation_revision as selected_revision, s.*
          from domain_rule_snapshot_head h
          left join domain_rule_snapshot s on s.id = h.active_snapshot_id
         where h.tenant_id = :tenant and h.environment = :environment
           and h.rule_set_key = :family and not h.withdrawn and h.operation_key in (:operations)
         order by h.operation_key
         limit 65
        """;
    private final NamedParameterJdbcTemplate jdbc;
    private final DomainRuleSnapshotVerifier verifier;

    public DomainRuleSnapshotHeadSetService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            DomainRuleImplementationCatalog catalog) {
        this.jdbc = Objects.requireNonNull(jdbc);
        verifier = new DomainRuleSnapshotVerifier(mapper, catalog);
    }

    @Override
    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true,
            propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Optional<PublishedRuleSnapshotHeadSet> findActive(PublishedRuleSnapshotHeadSetScope scope) {
        Objects.requireNonNull(scope, "scope is required");
        var rows = jdbc.query(SQL, Map.of("tenant", scope.tenantId(), "environment", scope.environment(),
                "family", scope.ruleSetKey(), "operations", scope.operationKeys()), (row, index) -> {
            if (row.getObject("id") == null) throw new IllegalStateException("Head references missing immutable content");
            var stored = detached(row);
            var snapshot = verifier.readVerifiedSnapshot(stored);
            String operation = row.getString("selected_operation");
            var head = new PublishedRuleSnapshotHead(snapshot, stored.getContentHash(),
                    row.getObject("selected_etag", UUID.class).toString(), row.getLong("selected_revision"),
                    PublishedRuleSnapshotHeadActivationType.ACTIVE);
            return Map.entry(operation, head);
        });
        var heads = new LinkedHashMap<String, PublishedRuleSnapshotHead>();
        for (var row : rows) {
            if (heads.putIfAbsent(row.getKey(), row.getValue()) != null)
                throw new IllegalStateException("Operation head identity is not unique");
        }
        if (heads.size() > scope.operationKeys().size()) throw new IllegalStateException("Head-set bound exceeded");
        if (heads.size() != scope.operationKeys().size()) return Optional.empty();
        try { return Optional.of(new PublishedRuleSnapshotHeadSet(scope, heads)); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Stored head-set identity is inconsistent", invalid);
        }
    }

    private static DomainRuleSnapshot detached(ResultSet row) throws SQLException {
        return DomainRuleSnapshot.builder().id(row.getObject("id", UUID.class))
                .tenantId(row.getString("tenant_id")).environment(row.getString("environment"))
                .snapshotKey(row.getString("snapshot_key")).ruleSetKey(row.getString("rule_set_key"))
                .operationKey(row.getString("operation_key")).ruleSetVersion(row.getInt("rule_set_version"))
                .publicationRevision(row.getInt("publication_revision"))
                .snapshotPayload(row.getString("snapshot_payload")).contentHash(row.getString("content_hash"))
                .compositionManifest(row.getString("composition_manifest")).compositionDigest(row.getString("composition_digest"))
                .build();
    }
}
