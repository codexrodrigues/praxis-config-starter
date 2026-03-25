package org.praxisplatform.config.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request de upsert em lote de templates AI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiRegistryTemplateBulkUpsertRequest {

    @NotEmpty
    private List<@Valid AiRegistryTemplateBulkItem> items;
}
