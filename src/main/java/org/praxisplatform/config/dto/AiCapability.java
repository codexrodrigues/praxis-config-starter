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
public class AiCapability {
    private String path;
    private String category;
    private String valueKind;
    private List<Object> allowedValues;
    private String description;
    private Boolean critical;
    private List<String> intentExamples;
    private String dependsOn;
    private String example;
    private String safetyNotes;
}
