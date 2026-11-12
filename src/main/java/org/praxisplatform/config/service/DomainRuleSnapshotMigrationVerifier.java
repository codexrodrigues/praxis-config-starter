package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.domain.DomainRuleSnapshot;

/** Infrastructure bridge for historical Flyway verification; does not compile, approve or activate content. */
public final class DomainRuleSnapshotMigrationVerifier {
  private DomainRuleSnapshotMigrationVerifier() {}

  /** Verifies a detached JDBC row with the canonical historical verifier, then returns its exact operation. */
  public static String verifiedOperation(DomainRuleSnapshot detached, ObjectMapper mapper) {
    try {
      var node=mapper.readTree(detached.getSnapshotPayload()).path("ruleSet").path("ref").get("operationKey");
      if(node==null||!node.isTextual()||node.textValue().isBlank()||node.textValue().length()>512
          ||!node.textValue().equals(node.textValue().trim()))
        throw new IllegalStateException("Historical snapshot has no canonical operation identity");
      detached.setOperationKey(node.textValue());
      var verifier=new DomainRuleSnapshotVerifier(mapper,scope->java.util.List.of());
      return verifier.readHistoricallyVerifiedSnapshot(detached).ruleSet().ref().operationKey();
    } catch(java.io.IOException invalid) {
      throw new IllegalStateException("Historical snapshot cannot be read for operation migration",invalid);
    }
  }
}
