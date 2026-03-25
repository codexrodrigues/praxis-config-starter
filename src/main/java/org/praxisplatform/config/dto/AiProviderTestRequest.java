package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request de teste de conectividade/autorização de um provedor AI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderTestRequest {
    private String provider;
    private String apiKey;
    private String model;
}
