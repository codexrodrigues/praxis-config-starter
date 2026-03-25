package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request para descoberta de modelos de um provedor AI específico.
 *
 * <p>
 * Permite ao caller informar o nome do provedor e, opcionalmente, uma chave temporária para
 * testar listagem de modelos sem persisti-la no registry.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderModelsRequest {
    private String provider;
    private String apiKey;
}
