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
public class AiIntentClassification {
    private String intent;
    private String targetField;
    private String newField;
    private List<String> baseFields;
    private String computedFormat;
    private String expression;
    private String category;
    private String scope;
    private Boolean needsClarification;
    private List<String> missingContext;
    private List<String> options;
}
