package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComponentSearchResult {
    private String id;
    private String description;
    private String jsonSchema; // Schema compacto
    private double similarityScore;
}
