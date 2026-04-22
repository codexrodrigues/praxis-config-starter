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
