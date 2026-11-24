package org.praxisplatform.config.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Override por chamada para provider, modelo, limites, perfil de raciocinio, chaves de cache
 * e contexto de recuperacao. Quando informado, {@code timeoutSeconds} representa o orcamento
 * total da operacao no roteador; candidatos de fallback consomem apenas o tempo restante e nao
 * renovam esse orcamento.
 *
 * <p>{@code invocationTrace} e os controles avancados de pensamento/cache sao metadados efemeros
 * de execucao interna; nao fazem parte de payload HTTP persistido em configuracao de usuario.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiCallConfig {
    private String provider;
    private String model;
    /** Backend-owned phase policy, applied only after the effective provider is resolved. */
    @JsonIgnore
    private Map<String, String> providerModelOverrides;
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
    @JsonIgnore
    private AiThinkingProfile thinkingProfile;
    @JsonIgnore
    private Integer thinkingBudgetTokens;
    @JsonIgnore
    private String promptCacheKey;
    @JsonIgnore
    private String cachedContentName;
    @JsonIgnore
    private String baseUrl;

    public AiCallConfig(
            String provider,
            String model,
            Map<String, String> providerModelOverrides,
            Double temperature,
            Integer maxTokens,
            Integer timeoutSeconds,
            String apiKey,
            String tenantId,
            String environment,
            String ragReleaseId,
            AiExecutionProfile executionProfile,
            AiProviderInvocationTrace invocationTrace) {
        this(
                provider,
                model,
                providerModelOverrides,
                temperature,
                maxTokens,
                timeoutSeconds,
                apiKey,
                tenantId,
                environment,
                ragReleaseId,
                executionProfile,
                invocationTrace,
                null,
                null,
                null,
                null,
                null);
    }

    public AiCallConfig(
            String provider,
            String model,
            Map<String, String> providerModelOverrides,
            Double temperature,
            Integer maxTokens,
            Integer timeoutSeconds,
            String apiKey,
            String tenantId,
            String environment,
            String ragReleaseId,
            AiExecutionProfile executionProfile,
            AiProviderInvocationTrace invocationTrace,
            AiThinkingProfile thinkingProfile,
            Integer thinkingBudgetTokens,
            String promptCacheKey,
            String cachedContentName) {
        this(
                provider,
                model,
                providerModelOverrides,
                temperature,
                maxTokens,
                timeoutSeconds,
                apiKey,
                tenantId,
                environment,
                ragReleaseId,
                executionProfile,
                invocationTrace,
                thinkingProfile,
                thinkingBudgetTokens,
                promptCacheKey,
                cachedContentName,
                null);
    }

    public static AiCallConfigBuilder builder() {
        return new AiCallConfigBuilder();
    }

    public static AiCallConfigBuilder agenticAuthoringBuilder() {
        return builder().executionProfile(AiExecutionProfile.AGENTIC_AUTHORING);
    }

    public AiCallConfigBuilder toBuilder() {
        return new AiCallConfigBuilder()
                .provider(provider)
                .model(model)
                .providerModelOverrides(providerModelOverrides)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeoutSeconds(timeoutSeconds)
                .apiKey(apiKey)
                .tenantId(tenantId)
                .environment(environment)
                .ragReleaseId(ragReleaseId)
                .executionProfile(executionProfile)
                .invocationTrace(invocationTrace)
                .thinkingProfile(thinkingProfile)
                .thinkingBudgetTokens(thinkingBudgetTokens)
                .promptCacheKey(promptCacheKey)
                .cachedContentName(cachedContentName)
                .baseUrl(baseUrl);
    }

    /**
     * Fluent builder kept as an explicit public type so source and Javadoc artifacts expose the
     * same API without depending on annotation processing.
     */
    public static final class AiCallConfigBuilder {
        private String provider;
        private String model;
        private Map<String, String> providerModelOverrides;
        private Double temperature;
        private Integer maxTokens;
        private Integer timeoutSeconds;
        private String apiKey;
        private String tenantId;
        private String environment;
        private String ragReleaseId;
        private AiExecutionProfile executionProfile;
        private AiProviderInvocationTrace invocationTrace;
        private AiThinkingProfile thinkingProfile;
        private Integer thinkingBudgetTokens;
        private String promptCacheKey;
        private String cachedContentName;
        private String baseUrl;

        private AiCallConfigBuilder() {
        }

        public AiCallConfigBuilder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public AiCallConfigBuilder model(String model) {
            this.model = model;
            return this;
        }

        public AiCallConfigBuilder providerModelOverrides(Map<String, String> value) {
            this.providerModelOverrides = value == null ? Map.of() : Map.copyOf(value);
            return this;
        }

        public AiCallConfigBuilder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public AiCallConfigBuilder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public AiCallConfigBuilder timeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public AiCallConfigBuilder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public AiCallConfigBuilder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public AiCallConfigBuilder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public AiCallConfigBuilder ragReleaseId(String ragReleaseId) {
            this.ragReleaseId = ragReleaseId;
            return this;
        }

        public AiCallConfigBuilder executionProfile(AiExecutionProfile executionProfile) {
            this.executionProfile = executionProfile;
            return this;
        }

        public AiCallConfigBuilder invocationTrace(AiProviderInvocationTrace invocationTrace) {
            this.invocationTrace = invocationTrace;
            return this;
        }

        public AiCallConfigBuilder thinkingProfile(AiThinkingProfile thinkingProfile) {
            this.thinkingProfile = thinkingProfile;
            return this;
        }

        public AiCallConfigBuilder thinkingBudgetTokens(Integer thinkingBudgetTokens) {
            this.thinkingBudgetTokens = thinkingBudgetTokens;
            return this;
        }

        public AiCallConfigBuilder promptCacheKey(String promptCacheKey) {
            this.promptCacheKey = promptCacheKey;
            return this;
        }

        public AiCallConfigBuilder cachedContentName(String cachedContentName) {
            this.cachedContentName = cachedContentName;
            return this;
        }

        public AiCallConfigBuilder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public AiCallConfig build() {
            return new AiCallConfig(
                    provider,
                    model,
                    providerModelOverrides,
                    temperature,
                    maxTokens,
                    timeoutSeconds,
                    apiKey,
                    tenantId,
                    environment,
                    ragReleaseId,
                    executionProfile,
                    invocationTrace,
                    thinkingProfile,
                    thinkingBudgetTokens,
                    promptCacheKey,
                    cachedContentName,
                    baseUrl);
        }
    }
}
