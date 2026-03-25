package org.praxisplatform.config.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado agregado do upsert em lote de templates AI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiRegistryTemplateBulkUpsertResponse {
    private int accepted;
    private int failed;
    private List<AiRegistryTemplateBulkError> errors;
}
