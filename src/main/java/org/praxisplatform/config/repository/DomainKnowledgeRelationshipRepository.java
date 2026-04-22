package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainKnowledgeRelationship;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainKnowledgeRelationshipRepository extends JpaRepository<DomainKnowledgeRelationship, UUID> {

    List<DomainKnowledgeRelationship> findByTenantIdAndEnvironmentAndSourceConcept_Id(
            String tenantId,
            String environment,
            UUID sourceConceptId);

    List<DomainKnowledgeRelationship> findByTenantIdAndEnvironmentAndTargetConcept_Id(
            String tenantId,
            String environment,
            UUID targetConceptId);

    List<DomainKnowledgeRelationship> findByTenantIdAndEnvironmentAndCrossContext(
            String tenantId,
            String environment,
            Boolean crossContext);
}
