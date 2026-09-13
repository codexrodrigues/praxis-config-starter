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

    /** One statement observes the head, source definition and retained/orphan history together. */
    @org.springframework.transaction.annotation.Transactional(
            transactionManager = org.praxisplatform.config.tx.ConfigTransactionManagerNames.CONFIG,
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, readOnly = true)
    @Query(value = """
            select cast(evidence as text) from (
              select jsonb_build_object(
                'kind', 'materialization', 'id', m.id, 'revision', m.row_version,
                'status', m.status, 'everApplied', m.ever_applied, 'sourceHash', m.source_hash,
                'definitionId', m.rule_definition_id, 'targetPointer', m.target_pointer,
                'materializedRuleId', m.materialized_rule_id,
                'payload', case when m.status = 'applied' then m.materialized_payload else null end,
                'definition', case when m.status = 'applied' then jsonb_build_object(
                  'id', d.id, 'tenantId', d.tenant_id, 'environment', d.environment,
                  'status', d.status, 'ruleKey', d.rule_key, 'version', d.version,
                  'ruleType', d.rule_type, 'resourceKey', d.resource_key, 'serviceKey', d.service_key,
                  'contextKey', d.context_key, 'definition', d.definition, 'parameters', d.parameters,
                  'condition', d.condition, 'governance', d.governance) else null end
              ) as evidence
              from domain_rule_materialization m left join domain_rule_definition d on d.id = m.rule_definition_id
              where m.tenant_id = :tenantId and m.environment = :environment
                and m.target_layer = :layer and m.target_artifact_type = :type and m.target_artifact_key = :targetKey
                and (m.ever_applied is distinct from false or m.status = 'applied')
              union all
              select jsonb_build_object(
                'kind', 'event', 'id', e.id, 'definitionId', e.rule_definition_id,
                'sourceHash', e.source_hash, 'occurredAt', e.occurred_at,
                'uncertainLink', e.materialization_id is not null,
                'definitionScopeValid', d.id is not null and d.tenant_id = :tenantId and d.environment = :environment)
              from domain_rule_event e
              left join domain_rule_materialization m on m.id = e.materialization_id
              left join domain_rule_definition d on d.id = e.rule_definition_id
              where e.event_type = 'materialization.applied'
                and e.tenant_id = :tenantId and e.environment = :environment
                and e.target_layer = :layer and e.target_artifact_type = :type and e.target_artifact_key = :targetKey
                and (m.id is null or m.ever_applied is distinct from true
                  or m.tenant_id is distinct from e.tenant_id or m.environment is distinct from e.environment
                  or m.target_layer is distinct from e.target_layer or m.target_artifact_type is distinct from e.target_artifact_type
                  or m.target_artifact_key is distinct from e.target_artifact_key or m.rule_definition_id <> e.rule_definition_id
                  or m.materialization_key is distinct from e.materialization_key or m.source_hash is distinct from e.source_hash)
            ) snapshot order by evidence->>'kind', evidence->>'id' limit 4097
            """, nativeQuery = true)
    List<String> operationalSnapshot(@Param("tenantId") String tenantId, @Param("environment") String environment,
            @Param("layer") String layer, @Param("type") String type, @Param("targetKey") String targetKey);

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
