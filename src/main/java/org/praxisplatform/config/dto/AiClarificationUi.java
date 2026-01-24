package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiClarificationUi {
    private String responseType; // text | choice | confirm | mixed | context
    private String selectionMode; // single | multiple
    private String presentation; // buttons | list | chips
    private Boolean allowCustom;
}
