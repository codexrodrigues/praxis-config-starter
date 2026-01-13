package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiRegistryTemplateSearchResult {
    private String componentId;
    private String aiDescription;
    private double similarityScore;
    private String configJsonSnippet;
}
