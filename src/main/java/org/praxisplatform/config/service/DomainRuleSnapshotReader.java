package org.praxisplatform.config.service;

import java.util.Optional;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;

/** Stable in-process read boundary for hosts embedding the Config Starter. */
public interface DomainRuleSnapshotReader {
  Optional<DomainRuleSnapshotActivationResponse> findActive(
      String tenantId, String environment, String ruleSetKey);

  Optional<DomainRuleSnapshotStoredResponse> findSnapshot(
      String tenantId, String environment, String snapshotKey);
}
