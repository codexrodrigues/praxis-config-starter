package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiApiKeyRotateRequest {
    private String componentType;
    private String componentId;
    private String scope; // user|tenant (optional)
    private String environment;
    private String previousEncryptionKey; // base64 AES key (optional)
    private String newEncryptionKey; // base64 AES key (optional)
}
