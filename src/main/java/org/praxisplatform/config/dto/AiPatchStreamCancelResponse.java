package org.praxisplatform.config.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
