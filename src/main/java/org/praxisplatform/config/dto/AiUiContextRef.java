package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUiContextRef {
    private String componentType;
    private String componentId;
    private String routeKey;
    private String schemaHash;
    private String variantId;
}
