package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record DomainCatalogItemResponse(
    UUID id,
    String releaseKey,
    String itemType,
    String itemKey,
    String contextKey,
    String nodeType,
    String bindingType,
    String edgeType,
    JsonNode payload
) {
}
