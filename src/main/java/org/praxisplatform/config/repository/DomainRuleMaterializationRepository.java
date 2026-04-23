package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
