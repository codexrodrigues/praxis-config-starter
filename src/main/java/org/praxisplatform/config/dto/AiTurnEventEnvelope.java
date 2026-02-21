package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
public class AiTurnEventEnvelope {
    private UUID eventId;
    private UUID streamId;
    private UUID threadId;
    private UUID turnId;
    private long seq;
    private String eventSchemaVersion;
    private Instant timestamp;
    private String type;
    private JsonNode payload;
}
