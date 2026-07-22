package org.praxisplatform.config.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Override por chamada para provider, modelo, limites e contexto de recuperacao.
 * Quando informado, {@code timeoutSeconds} representa o orçamento total da operação no roteador;
 * candidatos de fallback consomem apenas o tempo restante e não renovam esse orçamento.
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
    private AiExecutionProfile executionProfile;
    @JsonIgnore
    private AiProviderInvocationTrace invocationTrace;

    public static AiCallConfigBuilder agenticAuthoringBuilder() {
        return builder().executionProfile(AiExecutionProfile.AGENTIC_AUTHORING);
    }
}
