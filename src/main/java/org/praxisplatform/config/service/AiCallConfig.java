package org.praxisplatform.config.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Override por chamada para provider, modelo, limites e contexto de recuperacao.
 *
 * <p>{@code invocationTrace} e um coletor operacional efemero. Ele nao faz parte de payload HTTP,
 * persistencia ou configuracao do usuario.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AiCallConfig {
    private String provider;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Integer timeoutSeconds;
    private String apiKey;
    private String tenantId;
    private String environment;
    private String ragReleaseId;
    @JsonIgnore
    private AiProviderInvocationTrace invocationTrace;
}
