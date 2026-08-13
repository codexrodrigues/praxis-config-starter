package org.praxisplatform.config.repository;

import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshotEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only persistence access for snapshot activation events. */
public interface DomainRuleSnapshotEventRepository extends JpaRepository<DomainRuleSnapshotEvent, UUID> {
  boolean existsByTenantIdAndEnvironmentAndToSnapshotIdAndActivationRevision(
      String tenantId, String environment, UUID toSnapshotId, Long activationRevision);
}
