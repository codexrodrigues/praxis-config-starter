package org.praxisplatform.config.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AiCallConfig {
    private String provider;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private String apiKey;
}
