package org.praxisplatform.config.ai.authoring;

import java.util.List;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;

public interface AgenticAuthoringProjectKnowledgeCandidateRetriever {

    List<DomainKnowledgeConcept> retrieve(AgenticAuthoringProjectKnowledgeQuery query);
}
