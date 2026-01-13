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
public class AiSuggestionsResponse {
    private List<AiSuggestion> suggestions;
    private String source;
    private String cacheKey;
    private Boolean cacheHit;
    private List<String> warnings;
}
