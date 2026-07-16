package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.praxisplatform.config.domain.DomainRuleCompositionApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only persistence for independently authenticated composition approvals. */
public interface DomainRuleCompositionApprovalRepository
    extends JpaRepository<DomainRuleCompositionApproval, UUID> {

  Optional<DomainRuleCompositionApproval>
      findByTenantIdAndEnvironmentAndCompositionDigestAndActorRef(
          String tenantId, String environment, String compositionDigest, String actorRef);

  List<DomainRuleCompositionApproval>
      findByTenantIdAndEnvironmentAndCompositionDigestOrderByApprovedAtAsc(
          String tenantId, String environment, String compositionDigest);

  @Modifying
  @Query(value = """
      INSERT INTO domain_rule_composition_approval (
          id, tenant_id, environment, composition_digest, actor_ref, role, manifest, approved_at)
      VALUES (
          :id, :tenantId, :environment, :digest, :actorRef,
          'RULE_COMPOSITION_APPROVER', CAST(:manifest AS jsonb), :approvedAt)
      ON CONFLICT (tenant_id, environment, composition_digest, actor_ref) DO NOTHING
      """, nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("digest") String digest,
      @Param("actorRef") String actorRef,
      @Param("manifest") String manifest,
      @Param("approvedAt") Instant approvedAt);
}
