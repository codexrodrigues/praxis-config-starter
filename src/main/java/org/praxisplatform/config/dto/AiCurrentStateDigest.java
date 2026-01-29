package org.praxisplatform.config.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCurrentStateDigest {
    private List<String> columns;
    private String sort;
    private Integer rowCount;
}
