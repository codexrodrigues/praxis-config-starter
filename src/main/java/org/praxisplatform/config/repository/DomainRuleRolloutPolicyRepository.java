package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Versioned, server-owned rollout policy persistence. */
public interface DomainRuleRolloutPolicyRepository
    extends JpaRepository<DomainRuleRolloutPolicy, UUID> {
  Optional<DomainRuleRolloutPolicy> findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(
      String tenantId, String environment, String ruleSetKey);

  List<DomainRuleRolloutPolicy> findByTenantIdAndEnvironmentAndRuleSetKeyOrderByCreatedAtDesc(
      String tenantId, String environment, String ruleSetKey);

  Optional<DomainRuleRolloutPolicy> findByIdAndTenantIdAndEnvironment(
      UUID id, String tenantId, String environment);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select policy from DomainRuleRolloutPolicy policy
      where policy.id = :id and policy.tenantId = :tenantId
        and policy.environment = :environment
      """)
  Optional<DomainRuleRolloutPolicy> findForUpdateByIdAndTenantIdAndEnvironment(
      @Param("id") UUID id, @Param("tenantId") String tenantId,
      @Param("environment") String environment);

  @Query("""
      select max(policy.policyVersion) from DomainRuleRolloutPolicy policy
      where policy.tenantId = :tenantId and policy.environment = :environment
        and policy.ruleSetKey = :ruleSetKey and policy.policyKey = :policyKey
      """)
  Optional<Integer> findMaximumVersion(
      @Param("tenantId") String tenantId, @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey, @Param("policyKey") String policyKey);
}
