package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for immutable RuleSet snapshots. */
public interface DomainRuleSnapshotRepository extends JpaRepository<DomainRuleSnapshot, UUID> {
  Optional<DomainRuleSnapshot> findByTenantIdAndEnvironmentAndSnapshotKey(
      String tenantId, String environment, String snapshotKey);

  List<DomainRuleSnapshot> findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
      String tenantId, String environment, String ruleSetKey);
}
