package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiSuggestion {
    private String id;
    private String label;
    private String description;
    private String icon;
    private String group;
    private String intent;
    private String variantId;
    private Double score;
}
