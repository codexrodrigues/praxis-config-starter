package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicyHead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for the scoped active rollout-policy pointer. */
public interface DomainRuleRolloutPolicyHeadRepository
    extends JpaRepository<DomainRuleRolloutPolicyHead, UUID> {
  Optional<DomainRuleRolloutPolicyHead> findByTenantIdAndEnvironmentAndRuleSetKey(
      String tenantId, String environment, String ruleSetKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select head from DomainRuleRolloutPolicyHead head
      where head.tenantId = :tenantId and head.environment = :environment
        and head.ruleSetKey = :ruleSetKey
      """)
  Optional<DomainRuleRolloutPolicyHead> findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
      @Param("tenantId") String tenantId, @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey);
}
