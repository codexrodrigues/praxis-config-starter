package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleWorkspaceReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRuleWorkspaceReviewRepository extends JpaRepository<DomainRuleWorkspaceReview, UUID> {
  List<DomainRuleWorkspaceReview> findByTenantIdAndEnvironmentAndWorkspaceIdOrderByReviewedAtDesc(
      String tenantId, String environment, UUID workspaceId);
}
