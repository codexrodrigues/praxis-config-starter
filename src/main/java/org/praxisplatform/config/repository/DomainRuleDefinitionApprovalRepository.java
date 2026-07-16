package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleDefinitionApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only persistence for independently authenticated definition approvals. */
public interface DomainRuleDefinitionApprovalRepository
    extends JpaRepository<DomainRuleDefinitionApproval, UUID> {

  List<DomainRuleDefinitionApproval>
      findByTenantIdAndEnvironmentAndDefinitionIdAndDefinitionHashOrderByApprovedAtAsc(
          String tenantId, String environment, UUID definitionId, String definitionHash);

  @Modifying
  @Query(value = """
      INSERT INTO domain_rule_definition_approval (
          id, tenant_id, environment, definition_id, definition_hash, actor_ref, role, approved_at)
      VALUES (
          :id, :tenantId, :environment, :definitionId, :definitionHash, :actorRef,
          'RULE_DEFINITION_APPROVER', :approvedAt)
      ON CONFLICT (tenant_id, environment, definition_id, definition_hash, actor_ref) DO NOTHING
      """, nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("definitionId") UUID definitionId,
      @Param("definitionHash") String definitionHash,
      @Param("actorRef") String actorRef,
      @Param("approvedAt") Instant approvedAt);
}
