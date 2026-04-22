package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainKnowledgeEvidenceRepository extends JpaRepository<DomainKnowledgeEvidence, UUID> {

    List<DomainKnowledgeEvidence> findByTenantIdAndEnvironmentAndSubjectTypeAndSubjectId(
            String tenantId,
            String environment,
            String subjectType,
            UUID subjectId);

    List<DomainKnowledgeEvidence> findByTenantIdAndEnvironmentAndEvidenceKey(
            String tenantId,
            String environment,
            String evidenceKey);

    List<DomainKnowledgeEvidence> findByTenantIdAndEnvironmentAndEvidenceKeyIn(
            String tenantId,
            String environment,
            List<String> evidenceKeys);
}
