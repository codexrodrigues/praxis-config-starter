package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleTestScenario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRuleTestScenarioRepository extends JpaRepository<DomainRuleTestScenario, UUID> {
  List<DomainRuleTestScenario> findByWorkspaceIdOrderByScenarioKey(UUID workspaceId);
  Optional<DomainRuleTestScenario> findByWorkspaceIdAndScenarioKey(UUID workspaceId, String scenarioKey);
}
