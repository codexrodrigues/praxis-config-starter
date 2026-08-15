package org.praxisplatform.config.repository;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DomainRuleTestRunRepository extends JpaRepository<DomainRuleTestRun, UUID> {
  List<DomainRuleTestRun> findByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc(String tenantId, String environment, UUID workspaceId);
  java.util.Optional<DomainRuleTestRun> findFirstByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc(String tenantId, String environment, UUID workspaceId);
  java.util.Optional<DomainRuleTestRun> findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
      String tenantId, String environment, UUID workspaceId, String idempotencyKey);
}
