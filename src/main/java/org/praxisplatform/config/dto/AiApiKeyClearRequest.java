package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiApiKeyClearRequest {
    private String componentType;
    private String componentId;
    private String scope; // user|tenant (optional)
    private String environment;
}
