package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiSearchResult {
    private Long id;
    private String method;
    private String path;
    private String summary;
    private String tags;
    private double similarityScore; // 0.0 a 1.0
    
    /**
     * Snippet legado - atualmente retorna o schema completo.
     * @deprecated Use requestSchema para garantir acesso ao JSON integral.
     */
    @Deprecated
    private String requestSchemaSnippet;
    /**
     * Snippet legado - atualmente retorna o schema completo.
     * @deprecated Use responseSchema para garantir acesso ao JSON integral.
     */
    @Deprecated
    private String responseSchemaSnippet;

    // Schemas completos (JSON String) para uso em geração de código pelo LLM
    private String requestSchema;
    private String responseSchema;
    private String parameters;
}
