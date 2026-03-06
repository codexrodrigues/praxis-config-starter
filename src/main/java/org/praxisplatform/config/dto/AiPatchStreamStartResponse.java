package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiPatchStreamStartResponse {
    private UUID streamId;
    private UUID threadId;
    private UUID turnId;
    private String eventSchemaVersion;
    private String streamAuthMode;
    private String streamAccessToken;
    private Instant expiresAt;
    private String fallbackPatchUrl;
}
