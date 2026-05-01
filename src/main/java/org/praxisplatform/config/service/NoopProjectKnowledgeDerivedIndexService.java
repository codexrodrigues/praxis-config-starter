package org.praxisplatform.config.service;

import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.springframework.stereotype.Component;

@Component
public class NoopProjectKnowledgeDerivedIndexService implements ProjectKnowledgeDerivedIndexService {

    @Override
    public void evidenceActivated(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        // No derived Project Knowledge index is published in this beta slice.
    }

    @Override
    public void evidenceDeactivated(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        // No derived Project Knowledge index is published in this beta slice.
    }
}
