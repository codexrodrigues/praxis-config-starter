package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only persistence access for governed rollout-policy events. */
public interface DomainRuleRolloutPolicyEventRepository
    extends JpaRepository<DomainRuleRolloutPolicyEvent, UUID> {
  List<DomainRuleRolloutPolicyEvent>
      findByTenantIdAndEnvironmentAndRuleSetKeyOrderByCreatedAtAsc(
          String tenantId, String environment, String ruleSetKey);
}
