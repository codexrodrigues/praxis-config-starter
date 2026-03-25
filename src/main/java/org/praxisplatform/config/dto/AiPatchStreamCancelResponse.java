package org.praxisplatform.config.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resposta de cancelamento de stream AI.
 *
 * <p>
 * Resume o identificador do stream cancelado e o estado terminal conhecido no momento em que o
 * backend processa a solicitação.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiPatchStreamCancelResponse {
    private UUID streamId;
    private UUID threadId;
    private UUID turnId;
    private String terminalState;
    private String message;
}
