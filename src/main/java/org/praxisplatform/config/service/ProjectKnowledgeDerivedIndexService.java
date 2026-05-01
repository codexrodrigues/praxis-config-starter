package org.praxisplatform.config.service;

import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;

/**
 * Internal lifecycle hook for derived Project Knowledge indexes.
 *
 * <p>Implementations must treat Domain Knowledge tables as the source of truth. Index updates are
 * derived side effects and must never authorize AI influence without canonical re-checks.
 */
public interface ProjectKnowledgeDerivedIndexService {

    void evidenceActivated(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence);

    void evidenceDeactivated(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence);
}
