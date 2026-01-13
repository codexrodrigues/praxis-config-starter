package org.praxisplatform.config.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderModelsResponse {
    private String provider;
    private boolean success;
    private String message;
    private List<AiProviderModel> models;
}
