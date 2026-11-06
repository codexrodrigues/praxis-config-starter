package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for immutable RuleSet snapshots. */
public interface DomainRuleSnapshotRepository extends JpaRepository<DomainRuleSnapshot, UUID> {
  Optional<DomainRuleSnapshot> findByTenantIdAndEnvironmentAndSnapshotKey(
      String tenantId, String environment, String snapshotKey);

  Optional<DomainRuleSnapshot> findByIdAndTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(
      UUID id, String tenantId, String environment, String ruleSetKey, String operationKey);

  boolean existsByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKeyAndRuleSetVersion(
      String tenantId, String environment, String ruleSetKey, String operationKey, Integer ruleSetVersion);

  @Query("""
      select max(snapshot.publicationRevision) from DomainRuleSnapshot snapshot
      where snapshot.tenantId = :tenantId
        and snapshot.environment = :environment
        and snapshot.ruleSetKey = :ruleSetKey
        and snapshot.operationKey = :operationKey
      """)
  Integer findMaximumPublicationRevision(
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey, @Param("operationKey") String operationKey);

  @Query("""
      select max(snapshot.ruleSetVersion) from DomainRuleSnapshot snapshot
      where snapshot.tenantId = :tenantId
        and snapshot.environment = :environment
        and snapshot.ruleSetKey = :ruleSetKey
        and snapshot.operationKey = :operationKey
      """)
  Integer findMaximumRuleSetVersion(
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey, @Param("operationKey") String operationKey);

  Optional<DomainRuleSnapshot> findTopByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKeyOrderByPublicationRevisionDesc(
      String tenantId, String environment, String ruleSetKey, String operationKey);

  List<DomainRuleSnapshot> findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKeyOrderByPublicationRevisionDesc(
      String tenantId, String environment, String ruleSetKey, String operationKey, Pageable pageable);
  /** Family ownership guard only; never resolves an operational head or version. */
  Optional<DomainRuleSnapshot> findFirstByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublishedAtDesc(
      String tenantId, String environment, String ruleSetKey);
}

