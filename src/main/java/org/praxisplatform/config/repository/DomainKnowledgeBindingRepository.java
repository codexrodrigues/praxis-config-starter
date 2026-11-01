package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainKnowledgeBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainKnowledgeBindingRepository extends JpaRepository<DomainKnowledgeBinding, UUID> {

    List<DomainKnowledgeBinding> findByConcept_Id(UUID conceptId);

    List<DomainKnowledgeBinding> findByTenantIdAndEnvironmentAndResourceKey(
            String tenantId,
            String environment,
            String resourceKey);

    /**
     * Runtime discovery projection for governed operational bindings. Joins only the release
     * identities ({@code id}, {@code releaseKey}) and never initializes
     * {@code DomainCatalogRelease.rawPayload}.
     */
    @Query("""
        select new org.praxisplatform.config.domain.DomainKnowledgeBinding(
          b.id, b.tenantId, b.environment,
          c.id, c.tenantId, c.environment, c.conceptKey, c.contextKey, c.resourceKey,
          c.nodeType, c.lifecycle, c.curationStatus, c.aiVisibility, csr.id, csr.releaseKey,
          b.bindingType, b.bindingKey, b.resourceKey, b.apiPath, b.apiMethod, b.schemaPointer,
          sr.id, sr.releaseKey, b.confidence, b.curationStatus, b.payload, b.createdAt, b.updatedAt)
        from DomainKnowledgeBinding b
        join b.concept c
        left join c.sourceRelease csr
        left join b.sourceRelease sr
        where b.tenantId = :tenantId
          and b.environment = :environment
          and b.resourceKey = :resourceKey
          and b.curationStatus = 'approved'
          and c.lifecycle = 'active'
          and c.curationStatus = 'approved'
          and c.aiVisibility in ('allow', 'mask', 'summarize_only')
        order by b.confidence desc nulls last, b.bindingKey asc
    """)
    List<DomainKnowledgeBinding> findGovernedOperationalBindings(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("resourceKey") String resourceKey);

    List<DomainKnowledgeBinding> findByTenantIdAndEnvironmentAndBindingTypeAndBindingKey(
            String tenantId,
            String environment,
            String bindingType,
            String bindingKey);

    List<DomainKnowledgeBinding> findByTenantIdAndEnvironmentAndBindingKeyIn(
            String tenantId,
            String environment,
            List<String> bindingKeys);
}
