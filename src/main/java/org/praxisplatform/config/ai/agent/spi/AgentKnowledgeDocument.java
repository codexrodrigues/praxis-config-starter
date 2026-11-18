package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Documento normativo, de regra de dominio ou de catalogo de erros recuperavel via RAG
 * incremental no assistente operacional.
 */
public record AgentKnowledgeDocument(
        String docId,
        String sourceKind,
        String title,
        String normRef,
        String scopeLabel,
        String excerpt,
        List<String> tags,
        double score,
        String targetRoute,
        Map<String, Object> metadata
) {
    public AgentKnowledgeDocument {
        tags = AgentMapCopies.immutableList(tags);
        metadata = AgentMapCopies.immutableOrderedMap(metadata);
    }
}
