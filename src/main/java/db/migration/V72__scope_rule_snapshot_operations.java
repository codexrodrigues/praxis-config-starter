package db.migration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.service.DomainRuleSnapshotMigrationVerifier;

/** Adds relational operation identity only after verifying every immutable historical envelope. */
public final class V72__scope_rule_snapshot_operations extends BaseJavaMigration {
  private static final String SQL="/db/operation-scope-v72.sql";

  @Override public Integer getChecksum() {
    try(var input=getClass().getResourceAsStream(SQL)) {
      if(input==null)throw new IllegalStateException("Operation migration SQL is absent from the selected artifact");
      byte[] digest=MessageDigest.getInstance("SHA-256").digest(input.readAllBytes());
      return java.nio.ByteBuffer.wrap(digest).getInt();
    }catch(Exception failure){throw new IllegalStateException("Cannot verify operation migration resource",failure);}
  }

  @Override public void migrate(Context context) throws Exception {
    var connection=context.getConnection();
    if(connection.getAutoCommit())throw new IllegalStateException("Operation migration requires one transaction");
    try(var sql=connection.createStatement()) {
      sql.execute("lock table domain_rule_snapshot,domain_rule_snapshot_head,domain_rule_snapshot_event,"
          +"domain_rule_snapshot_rollout,domain_rule_snapshot_rollout_event,domain_rule_candidate_probe,"
          +"domain_rule_host_status in access exclusive mode");
    }
    var mapper=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    var familyOwners=new HashMap<java.util.List<String>,java.util.List<String>>();
    try(var query=connection.createStatement();var rows=query.executeQuery("select * from domain_rule_snapshot")) {
      while(rows.next()) {
        var row=DomainRuleSnapshot.builder().id(rows.getObject("id",java.util.UUID.class))
            .tenantId(rows.getString("tenant_id")).environment(rows.getString("environment"))
            .snapshotKey(rows.getString("snapshot_key")).ruleSetKey(rows.getString("rule_set_key"))
            .ruleSetVersion(rows.getInt("rule_set_version")).publicationRevision(rows.getInt("publication_revision"))
            .snapshotPayload(rows.getString("snapshot_payload")).contentHash(rows.getString("content_hash"))
            .compositionManifest(rows.getString("composition_manifest")).compositionDigest(rows.getString("composition_digest"))
            .build();
        DomainRuleSnapshotMigrationVerifier.verifiedOperation(row,mapper);
        var payload=mapper.readTree(row.getSnapshotPayload());var ref=payload.path("ruleSet").path("ref");
        var key=java.util.List.of(row.getTenantId(),row.getEnvironment(),row.getRuleSetKey());
        var identity=java.util.List.of(payload.path("ownerServiceKey").asText(),ref.path("domainKey").asText(),ref.path("boundedContextKey").asText());
        var previous=familyOwners.putIfAbsent(key,identity);
        if(previous!=null&&!previous.equals(identity))throw new IllegalStateException("Historical family ownership is inconsistent");
      }
    }
    try(var input=getClass().getResourceAsStream(SQL);var query=connection.createStatement()) {
      if(input==null)throw new IllegalStateException("Operation migration SQL is absent");
      query.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8));
    }
  }
}
