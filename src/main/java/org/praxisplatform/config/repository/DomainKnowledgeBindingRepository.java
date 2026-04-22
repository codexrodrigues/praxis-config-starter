package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainKnowledgeBinding;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainKnowledgeBindingRepository extends JpaRepository<DomainKnowledgeBinding, UUID> {

    List<DomainKnowledgeBinding> findByConcept_Id(UUID conceptId);

    List<DomainKnowledgeBinding> findByTenantIdAndEnvironmentAndResourceKey(
            String tenantId,
            String environment,
            String resourceKey);

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
