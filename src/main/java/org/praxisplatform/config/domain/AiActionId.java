package org.praxisplatform.config.domain;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Identificador composto de {@link AiAction}, formado por thread, turno e tipo de acao.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiActionId implements Serializable {
    private UUID threadId;
    private UUID turnId;
    private String actionType;
}
