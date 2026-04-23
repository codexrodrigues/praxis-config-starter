package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRuleDefinitionRepository extends JpaRepository<DomainRuleDefinition, UUID> {

    Optional<DomainRuleDefinition> findByTenantIdAndEnvironmentAndRuleKeyAndVersion(
            String tenantId,
            String environment,
            String ruleKey,
            Integer version);

    List<DomainRuleDefinition> findByTenantIdAndEnvironmentAndRuleKeyOrderByVersionDesc(
            String tenantId,
            String environment,
            String ruleKey);

    List<DomainRuleDefinition> findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
            String tenantId,
            String environment,
            String resourceKey,
            List<String> statuses);

    List<DomainRuleDefinition> findByTenantIdAndEnvironmentAndRuleTypeAndStatus(
            String tenantId,
            String environment,
            String ruleType,
            String status);
}
