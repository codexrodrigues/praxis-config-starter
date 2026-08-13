package org.praxisplatform.config.repository;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DomainRuleTestRunResultRepository extends JpaRepository<DomainRuleTestRunResult, UUID> {
  List<DomainRuleTestRunResult> findByTestRunIdOrderByScenarioKey(UUID testRunId);
}
