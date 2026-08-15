package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainRuleChangeWorkspaceRepository extends JpaRepository<DomainRuleChangeWorkspace, UUID> {
  List<DomainRuleChangeWorkspace> findByTenantIdAndEnvironmentOrderByUpdatedAtDesc(String tenantId, String environment);

  List<DomainRuleChangeWorkspace> findByTenantIdAndEnvironmentAndPromotedDefinitionId(
      String tenantId, String environment, UUID promotedDefinitionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select workspace from DomainRuleChangeWorkspace workspace where workspace.id = :id")
  Optional<DomainRuleChangeWorkspace> findByIdForUpdate(@Param("id") UUID id);
}
