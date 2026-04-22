package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainKnowledgeAlias;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainKnowledgeAliasRepository extends JpaRepository<DomainKnowledgeAlias, UUID> {

    List<DomainKnowledgeAlias> findByTenantIdAndEnvironmentAndNormalizedAliasContainingIgnoreCase(
            String tenantId,
            String environment,
            String normalizedAlias,
            Pageable pageable);

    List<DomainKnowledgeAlias> findByConcept_Id(UUID conceptId);
}
