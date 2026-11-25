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

    /** Acquire before loading definitions/workspaces/heads; held through the Config commit. */
    default void lockLifecycleScope(org.praxisplatform.config.service.DomainRuleGovernancePrincipal principal) {
        String result = acquireLifecycleLock(org.praxisplatform.config.service.DomainRuleLifecycleScope.lockKey(principal));
        if (!"".equals(result)) {
            throw new org.praxisplatform.config.exception.ConfigurationIngestionException(
                    "Domain rule lifecycle requires PostgreSQL READ COMMITTED isolation");
        }
    }

    @org.springframework.transaction.annotation.Transactional(
            transactionManager = org.praxisplatform.config.tx.ConfigTransactionManagerNames.CONFIG,
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    @Query(value = """
            select case when current_setting('transaction_isolation') = 'read committed'
              then cast(pg_advisory_xact_lock(:scopeKey) as text)
              else 'unsupported_isolation' end
            """, nativeQuery = true)
    String acquireLifecycleLock(@Param("scopeKey") long scopeKey);


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

    @Query("""
            select definition from DomainRuleDefinition definition
            where definition.tenantId = :tenantId
              and definition.environment = :environment
              and (:ruleType is null or definition.ruleType = :ruleType)
              and (:status is null or definition.status = :status)
              and (:resourceKey is null or definition.resourceKey = :resourceKey)
              and (:contextKey is null or definition.contextKey = :contextKey)
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
    Page<DomainRuleDefinition> searchCatalogCandidatesByContext(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("query") String query,
            @Param("ruleType") String ruleType,
            @Param("status") String status,
            @Param("resourceKey") String resourceKey,
            @Param("contextKey") String contextKey,
            Pageable pageable);

    @Query("""
            select definition.ruleType, count(definition) from DomainRuleDefinition definition
            where definition.tenantId = :tenantId
              and definition.environment = :environment
              and definition.version = (
                select max(latest.version) from DomainRuleDefinition latest
                where latest.tenantId = definition.tenantId
                  and latest.environment = definition.environment
                  and latest.ruleKey = definition.ruleKey
              )
            group by definition.ruleType
            """)
    List<Object[]> countLatestByRuleType(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment);

    @Query("""
            select definition.contextKey, count(definition) from DomainRuleDefinition definition
            where definition.tenantId = :tenantId
              and definition.environment = :environment
              and definition.ruleType <> :authorizationRuleType
              and definition.version = (
                select max(latest.version) from DomainRuleDefinition latest
                where latest.tenantId = definition.tenantId
                  and latest.environment = definition.environment
                  and latest.ruleKey = definition.ruleKey
              )
            group by definition.contextKey
            """)
    List<Object[]> countLatestBusinessByContextKey(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("authorizationRuleType") String authorizationRuleType);

    @Query("""
            select definition.resourceKey, count(definition) from DomainRuleDefinition definition
            where definition.tenantId = :tenantId
              and definition.environment = :environment
              and definition.ruleType <> :authorizationRuleType
              and definition.version = (
                select max(latest.version) from DomainRuleDefinition latest
                where latest.tenantId = definition.tenantId
                  and latest.environment = definition.environment
                  and latest.ruleKey = definition.ruleKey
              )
            group by definition.resourceKey
            """)
    List<Object[]> countLatestBusinessByResourceKey(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("authorizationRuleType") String authorizationRuleType);

    @Query("""
            select definition.status, count(definition) from DomainRuleDefinition definition
            where definition.tenantId = :tenantId
              and definition.environment = :environment
              and definition.ruleType <> :authorizationRuleType
              and definition.version = (
                select max(latest.version) from DomainRuleDefinition latest
                where latest.tenantId = definition.tenantId
                  and latest.environment = definition.environment
                  and latest.ruleKey = definition.ruleKey
              )
            group by definition.status
            """)
    List<Object[]> countLatestBusinessByStatus(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("authorizationRuleType") String authorizationRuleType);

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
