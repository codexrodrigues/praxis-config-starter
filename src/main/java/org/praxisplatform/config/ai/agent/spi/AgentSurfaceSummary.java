package org.praxisplatform.config.ai.agent.spi;

/**
 * Resumo de superficie contextual relacionada (ex.: aba/grade filha ou projecao 360) disponivel para o item.
 */
public record AgentSurfaceSummary(
        String surfaceId,
        String title,
        String description,
        String kind,
        String scope,
        String contextualPath,
        String childResourceKey,
        String childResourcePath,
        String childParentField,
        boolean available,
        String unavailableReason,
        int order
) {
}
