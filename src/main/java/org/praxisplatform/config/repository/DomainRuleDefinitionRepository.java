package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface DomainRuleDefinitionRepository extends JpaRepository<DomainRuleDefinition, UUID> {

    @Query("""
            select definition from DomainRuleDefinition definition
            where definition.tenantId = :tenantId
              and definition.environment = :environment
              and (:ruleType is null or definition.ruleType = :ruleType)
              and (:status is null or definition.status = :status)
              and (:resourceKey is null or definition.resourceKey = :resourceKey)
              and (
                :query = ''
                or lower(definition.ruleKey) like lower(concat('%', :query, '%'))
                or lower(coalesce(definition.contextKey, '')) like lower(concat('%', :query, '%'))
                or lower(coalesce(definition.resourceKey, '')) like lower(concat('%', :query, '%'))
                or lower(coalesce(definition.serviceKey, '')) like lower(concat('%', :query, '%'))
                or lower(coalesce(definition.semanticOwner, '')) like lower(concat('%', :query, '%'))
              )
            order by definition.updatedAt desc, definition.ruleKey asc, definition.version desc
            """)
    Page<DomainRuleDefinition> searchCatalogCandidates(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("query") String query,
            @Param("ruleType") String ruleType,
            @Param("status") String status,
            @Param("resourceKey") String resourceKey,
            Pageable pageable);

    Optional<DomainRuleDefinition> findByTenantIdAndEnvironmentAndRuleKeyAndVersion(
            String tenantId,
            String environment,
            String ruleKey,
            Integer version);

    List<DomainRuleDefinition> findByTenantIdAndEnvironmentAndRuleKeyOrderByVersionDesc(
            String tenantId,
            String environment,
            String ruleKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<DomainRuleDefinition> findAllByTenantIdAndEnvironmentAndRuleKeyOrderByVersionDesc(
            String tenantId,
            String environment,
            String ruleKey);

    List<DomainRuleDefinition> findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
            String tenantId,
            String environment,
            String resourceKey,
            List<String> statuses);

    List<DomainRuleDefinition> findByTenantIdAndEnvironmentAndRuleTypeAndStatus(
            String tenantId,
            String environment,
            String ruleType,
            String status);
}
