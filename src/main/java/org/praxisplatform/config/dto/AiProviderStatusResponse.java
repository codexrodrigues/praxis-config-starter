package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderStatusResponse {
    private String provider;
    private String model;
    private boolean hasApiKey;
    private String source;
    private boolean success;
    private String message;
}
