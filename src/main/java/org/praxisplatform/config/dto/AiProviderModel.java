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
public class AiProviderModel {
    private String name;
    private String displayName;
    private String description;
    private Integer inputTokenLimit;
    private Integer outputTokenLimit;
    private List<String> supportedGenerationMethods;
    private String version;
}
