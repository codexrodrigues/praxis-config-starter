package org.praxisplatform.config.repository;

import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for immutable RuleSet snapshots. */
public interface DomainRuleSnapshotRepository extends JpaRepository<DomainRuleSnapshot, UUID> {
  Optional<DomainRuleSnapshot> findByTenantIdAndEnvironmentAndSnapshotKey(
      String tenantId, String environment, String snapshotKey);

  Optional<DomainRuleSnapshot> findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
      UUID id, String tenantId, String environment, String ruleSetKey);

  boolean existsByTenantIdAndEnvironmentAndRuleSetKeyAndRuleSetVersion(
      String tenantId, String environment, String ruleSetKey, Integer ruleSetVersion);

  @Query("""
      select max(snapshot.publicationRevision) from DomainRuleSnapshot snapshot
      where snapshot.tenantId = :tenantId
        and snapshot.environment = :environment
        and snapshot.ruleSetKey = :ruleSetKey
      """)
  Integer findMaximumPublicationRevision(
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey);

  Optional<DomainRuleSnapshot> findTopByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
      String tenantId, String environment, String ruleSetKey);
}
