package org.praxisplatform.config.domain;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiMessageId implements Serializable {
    private UUID threadId;
    private Integer seq;
}
