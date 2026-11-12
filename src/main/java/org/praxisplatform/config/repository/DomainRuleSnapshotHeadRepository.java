package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for mutable snapshot heads. */
public interface DomainRuleSnapshotHeadRepository extends JpaRepository<DomainRuleSnapshotHead, UUID> {
  /** All operation heads, exclusively for family-wide governance and ownership coordination. */
  java.util.List<DomainRuleSnapshotHead> findAllByTenantIdAndEnvironmentAndRuleSetKey(
      String tenantId, String environment, String ruleSetKey);
  Optional<DomainRuleSnapshotHead> findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(
      String tenantId, String environment, String ruleSetKey, String operationKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select head from DomainRuleSnapshotHead head
      where head.tenantId = :tenantId
        and head.environment = :environment
        and head.ruleSetKey = :ruleSetKey
        and head.operationKey = :operationKey
      """)
  Optional<DomainRuleSnapshotHead> findForUpdateByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey,
      @Param("operationKey") String operationKey);
}
