package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainRuleMaterializationRepository extends JpaRepository<DomainRuleMaterialization, UUID> {

    Optional<DomainRuleMaterialization> findByTenantIdAndEnvironmentAndMaterializationKey(
            String tenantId,
            String environment,
            String materializationKey);

    List<DomainRuleMaterialization> findByTenantIdAndEnvironmentAndRuleDefinition_Id(
            String tenantId,
            String environment,
            UUID ruleDefinitionId);

    List<DomainRuleMaterialization> findByTenantIdAndEnvironmentAndTargetLayerAndTargetArtifactTypeAndTargetArtifactKey(
            String tenantId,
            String environment,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey);

    List<DomainRuleMaterialization> findByTenantIdAndEnvironmentAndStatus(
            String tenantId,
            String environment,
            String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select materialization from DomainRuleMaterialization materialization
            where materialization.tenantId = :tenantId
              and materialization.environment = :environment
              and materialization.targetLayer = :targetLayer
              and materialization.targetArtifactType = :targetArtifactType
              and materialization.targetArtifactKey = :targetArtifactKey
              and materialization.status = 'applied'
            """)
    List<DomainRuleMaterialization> findAppliedForUpdateByExactTarget(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("targetLayer") String targetLayer,
            @Param("targetArtifactType") String targetArtifactType,
            @Param("targetArtifactKey") String targetArtifactKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select materialization from DomainRuleMaterialization materialization
            where materialization.tenantId = :tenantId
              and materialization.environment = :environment
              and materialization.ruleDefinition.id = :ruleDefinitionId
            """)
    List<DomainRuleMaterialization> findForUpdateByDefinition(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("ruleDefinitionId") UUID ruleDefinitionId);
}
