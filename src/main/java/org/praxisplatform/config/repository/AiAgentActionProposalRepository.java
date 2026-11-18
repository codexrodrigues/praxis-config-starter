package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.AiAgentActionProposal;
import org.praxisplatform.config.domain.AiAgentActionProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para propostas persistidas de execucao governada do assistente operacional.
 */
@Repository
public interface AiAgentActionProposalRepository extends JpaRepository<AiAgentActionProposal, UUID> {

    Optional<AiAgentActionProposal> findByProposalIdAndTenantIdAndUserIdAndEnvironment(
            UUID proposalId,
            String tenantId,
            String userId,
            String environment);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiAgentActionProposal p
               set p.status = :nextStatus,
                   p.version = coalesce(p.version, 0) + 1
             where p.proposalId = :proposalId
               and p.tenantId = :tenantId
               and p.userId = :userId
               and p.environment = :environment
               and p.status = :expectedStatus
               and p.expiresAt > :now
            """)
    int transitionStatusIfCurrent(
            @Param("proposalId") UUID proposalId,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("environment") String environment,
            @Param("expectedStatus") AiAgentActionProposalStatus expectedStatus,
            @Param("nextStatus") AiAgentActionProposalStatus nextStatus,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AiAgentActionProposal p where p.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
