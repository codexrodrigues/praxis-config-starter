package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderCatalogItem {
    private String id;
    private String label;
    private String description;
    private String defaultModel;
    private boolean requiresApiKey;
    private boolean supportsModels;
    private boolean supportsEmbeddings;
    private String iconKey;
}
