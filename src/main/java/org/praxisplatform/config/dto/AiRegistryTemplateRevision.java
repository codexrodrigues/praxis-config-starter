package org.praxisplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Evidencia verificavel da revisao material e do configJson canonico de um template. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiRegistryTemplateRevision {
    private Long version;
    private String etag;
    private String configSha256;
}
