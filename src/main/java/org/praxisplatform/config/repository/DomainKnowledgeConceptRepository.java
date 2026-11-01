package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainKnowledgeConceptRepository extends JpaRepository<DomainKnowledgeConcept, UUID> {

    Optional<DomainKnowledgeConcept> findByTenantIdAndEnvironmentAndConceptKey(
            String tenantId,
            String environment,
            String conceptKey);

    List<DomainKnowledgeConcept> findByTenantIdAndEnvironmentAndConceptKeyIn(
            String tenantId,
            String environment,
            List<String> conceptKeys);

    /**
     * Hydrates only the source release identity ({@code id}, {@code releaseKey}) without loading
     * {@code DomainCatalogRelease.rawPayload}.
     */
    @Query("""
        select new org.praxisplatform.config.domain.DomainKnowledgeConcept(
          c.id, c.tenantId, c.environment, c.conceptKey, c.contextKey, c.resourceKey,
          c.nodeType, c.label, c.description, c.locale, c.semanticOwner, c.steward,
          c.lifecycle, c.curationStatus, c.aiVisibility, c.dataCategory, c.classification,
          c.complianceTags, sr.id, sr.releaseKey, c.payload, c.createdAt, c.updatedAt)
        from DomainKnowledgeConcept c
        left join c.sourceRelease sr
        where c.tenantId = :tenantId
          and c.environment = :environment
          and c.conceptKey in :conceptKeys
    """)
    List<DomainKnowledgeConcept> findWithSourceReleaseByTenantIdAndEnvironmentAndConceptKeyIn(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("conceptKeys") List<String> conceptKeys);

    /**
     * Hydrates only the source release identity ({@code id}, {@code releaseKey}) without loading
     * {@code DomainCatalogRelease.rawPayload}.
     */
    @Query("""
        select new org.praxisplatform.config.domain.DomainKnowledgeConcept(
          c.id, c.tenantId, c.environment, c.conceptKey, c.contextKey, c.resourceKey,
          c.nodeType, c.label, c.description, c.locale, c.semanticOwner, c.steward,
          c.lifecycle, c.curationStatus, c.aiVisibility, c.dataCategory, c.classification,
          c.complianceTags, sr.id, sr.releaseKey, c.payload, c.createdAt, c.updatedAt)
        from DomainKnowledgeConcept c
        left join c.sourceRelease sr
        where c.tenantId = :tenantId
          and c.environment = :environment
          and c.lifecycle = 'active'
          and c.curationStatus = 'approved'
          and c.aiVisibility in ('allow', 'mask', 'summarize_only')
        order by c.conceptKey asc
    """)
    List<DomainKnowledgeConcept> findGovernedProjectKnowledgeForDerivedIndex(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment);

    /**
     * Runtime discovery projection for governed Project Knowledge candidates. Joins only the
     * release identity ({@code id}, {@code releaseKey}) and never initializes
     * {@code DomainCatalogRelease.rawPayload}.
     */
    @Query("""
        select new org.praxisplatform.config.domain.DomainKnowledgeConcept(
          c.id, c.tenantId, c.environment, c.conceptKey, c.contextKey, c.resourceKey,
          c.nodeType, c.label, c.description, c.locale, c.semanticOwner, c.steward,
          c.lifecycle, c.curationStatus, c.aiVisibility, c.dataCategory, c.classification,
          c.complianceTags, sr.id, sr.releaseKey, c.payload, c.createdAt, c.updatedAt)
        from DomainKnowledgeConcept c
        left join c.sourceRelease sr
        where c.tenantId = :tenantId
          and c.environment = :environment
          and c.lifecycle = 'active'
          and c.curationStatus = 'approved'
          and c.aiVisibility in ('allow', 'mask', 'summarize_only')
          and (:contextKey is null or :contextKey = ''
            or c.contextKey is null or c.contextKey = '' or c.contextKey = :contextKey)
          and (:resourceKey is null or :resourceKey = ''
            or c.resourceKey is null or c.resourceKey = '' or c.resourceKey = :resourceKey)
          and (:nodeType is null or :nodeType = '' or c.nodeType = :nodeType)
        order by
          case when c.resourceKey is null or c.resourceKey = '' then 0 else 1 end asc,
          c.contextKey asc nulls first,
          c.resourceKey asc nulls first,
          c.conceptKey asc
    """)
    List<DomainKnowledgeConcept> findGovernedProjectKnowledgeCandidates(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("contextKey") String contextKey,
            @Param("resourceKey") String resourceKey,
            @Param("nodeType") String nodeType,
            Pageable pageable);

    @Query("""
        select c from DomainKnowledgeConcept c
        where (:tenantId is null or :tenantId = '' or c.tenantId = :tenantId)
          and (:environment is null or :environment = '' or c.environment = :environment)
          and (:contextKey is null or :contextKey = '' or c.contextKey = :contextKey)
          and (:resourceKey is null or :resourceKey = '' or c.resourceKey = :resourceKey)
          and (:nodeType is null or :nodeType = '' or c.nodeType = :nodeType)
          and (:query is null or :query = ''
            or lower(c.conceptKey) like lower(concat('%', :query, '%'))
            or lower(c.label) like lower(concat('%', :query, '%'))
            or lower(c.description) like lower(concat('%', :query, '%')))
        order by c.curationStatus asc, c.conceptKey asc
    """)
    List<DomainKnowledgeConcept> search(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("contextKey") String contextKey,
            @Param("resourceKey") String resourceKey,
            @Param("nodeType") String nodeType,
            @Param("query") String query,
            Pageable pageable);
}
