package org.praxisplatform.config.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Catálogo serializado de provedores AI disponíveis para administração.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderCatalogResponse {
    private List<AiProviderCatalogItem> providers;
}
