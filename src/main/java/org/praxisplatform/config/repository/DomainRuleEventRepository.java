package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRuleEventRepository extends JpaRepository<DomainRuleEvent, UUID> {

    List<DomainRuleEvent> findByTenantIdAndEnvironmentAndRuleDefinition_IdOrderByOccurredAtAscEventTypeAsc(
            String tenantId,
            String environment,
            UUID ruleDefinitionId);
}
