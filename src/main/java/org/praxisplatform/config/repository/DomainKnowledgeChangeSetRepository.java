package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainKnowledgeChangeSetRepository extends JpaRepository<DomainKnowledgeChangeSet, UUID> {

    Optional<DomainKnowledgeChangeSet> findByTenantIdAndEnvironmentAndChangeSetKey(
            String tenantId,
            String environment,
            String changeSetKey);

    List<DomainKnowledgeChangeSet> findByTenantIdAndEnvironmentOrderByCreatedAtDesc(
            String tenantId,
            String environment);

    List<DomainKnowledgeChangeSet> findByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
            String tenantId,
            String environment,
            String status);
}
