package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRuleChangeWorkspaceRepository extends JpaRepository<DomainRuleChangeWorkspace, UUID> {
  List<DomainRuleChangeWorkspace> findByTenantIdAndEnvironmentOrderByUpdatedAtDesc(String tenantId, String environment);
}
