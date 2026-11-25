package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * SPI de leitura do baseline publicado ativo em {@code ui-layouts} para enforcement server-side
 * de regras de governança ({@code governance.lockLevel}) em {@code ui_user_config}.
 */
@FunctionalInterface
public interface PublishedUiLayoutBaselineProvider {

    record PublishedBaseline(String versionToken, JsonNode document) {}

    Optional<JsonNode> findActivePublishedDocument(
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment);

    default Optional<PublishedBaseline> findActivePublishedBaseline(
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment) {
        return findActivePublishedDocument(tenantId, userId, componentType, componentId, environment)
                .map(doc -> new PublishedBaseline(null, doc));
    }
}
