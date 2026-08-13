package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshotRollout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** Scoped lifecycle persistence for staged immutable-snapshot rollouts. */
public interface DomainRuleSnapshotRolloutRepository
    extends JpaRepository<DomainRuleSnapshotRollout, UUID> {
  Optional<DomainRuleSnapshotRollout> findByIdAndTenantIdAndEnvironment(
      UUID id, String tenantId, String environment);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select rollout from DomainRuleSnapshotRollout rollout
      where rollout.id = :id and rollout.tenantId = :tenantId
        and rollout.environment = :environment
      """)
  Optional<DomainRuleSnapshotRollout> findForUpdateByIdAndTenantIdAndEnvironment(
      @Param("id") UUID id, @Param("tenantId") String tenantId,
      @Param("environment") String environment);

  List<DomainRuleSnapshotRollout> findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
      String tenantId, String environment, String ruleSetKey, Set<String> statuses);

  List<DomainRuleSnapshotRollout> findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusInOrderByCreatedAtDesc(
      String tenantId, String environment, String ruleSetKey, Set<String> statuses);
}
