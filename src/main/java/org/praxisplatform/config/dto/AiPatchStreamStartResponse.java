package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resposta de inicialização de stream para geração incremental de patch.
 *
 * <p>
 * Carrega os identificadores do stream/thread/turn e os metadados necessários para conexão
 * posterior, incluindo modo de autenticação, token temporário e expiração quando aplicável.
 * </p>
 */
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
