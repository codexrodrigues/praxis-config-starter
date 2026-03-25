package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado do teste de conectividade de um provedor AI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderTestResponse {
    private String provider;
    private String model;
    private boolean success;
    private String message;
}
