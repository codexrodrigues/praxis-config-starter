package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resposta de inicializacao de stream para um turno de authoring agentico.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgenticAuthoringTurnStreamStartResponse {
    private UUID streamId;
    private UUID threadId;
    private UUID turnId;
    private String eventSchemaVersion;
    private String streamAuthMode;
    private String streamAccessToken;
    private Instant expiresAt;
    private String fallbackAuthoringUrl;
}
