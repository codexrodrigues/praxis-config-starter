package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AiApiKeyMaintenanceResponse {
    private boolean success;
    private String status;
    private String message;
    private String componentType;
    private String componentId;
    private String scope;
    private String environment;
    private Boolean hasApiKey;
    private String apiKeyLast4;
}
