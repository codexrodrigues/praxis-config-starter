package org.praxisplatform.config.ai.agent.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiProviderFailureClassifier;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiProviderRateLimitStore;
import org.springframework.util.StringUtils;

/**
 * Roteador neutro de {@link AiToolCallingProvider} que resolve provedor, chave, modelo,
 * politicas de elegibilidade ({@link AiProviderEligibilityPolicy}) e janela de cooldown
 * ({@link AiProviderRateLimitStore}), preservando {@code disableToolCalling} e
 * {@code responseJsonSchema} e realizando failover dentro do orcamento de tempo restante.
 */
public class RoutingAiToolCallingProvider implements AiToolCallingProvider {

    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);

    private final AiProviderManagementService providerManagementService;
    private final Map<String, AiToolCallingProvider> providersById = new LinkedHashMap<>();
    private final AiProviderRateLimitStore rateLimitStore;
    private final List<AiProviderEligibilityPolicy> eligibilityPolicies;

    public RoutingAiToolCallingProvider(
            AiProviderManagementService providerManagementService,
            List<AiToolCallingProvider> gateways) {
        this(providerManagementService, gateways, null, List.of());
    }

    public RoutingAiToolCallingProvider(
            AiProviderManagementService providerManagementService,
            List<AiToolCallingProvider> gateways,
            AiProviderRateLimitStore rateLimitStore,
            List<AiProviderEligibilityPolicy> eligibilityPolicies) {
        this.providerManagementService = providerManagementService;
        this.rateLimitStore = rateLimitStore;
        this.eligibilityPolicies = eligibilityPolicies == null
                ? List.of()
                : List.copyOf(eligibilityPolicies.stream().filter(Objects::nonNull).toList());
        if (gateways != null) {
            for (AiToolCallingProvider gateway : gateways) {
                if (gateway == null || gateway == this || !StringUtils.hasText(gateway.providerId())) {
                    continue;
                }
                providersById.put(normalize(gateway.providerId()), gateway);
            }
        }
    }

    @Override
    public String providerId() {
        return "routing";
    }

    @Override
    public AgentModelTurnResponse generateTurn(
            AgentModelTurnRequest request,
            AgentModelStreamListener listener) {
        Objects.requireNonNull(request, "request is required");
        AiCallConfig requested = request.callConfig();
        boolean alreadyResolvedForCaller = requested != null
                && StringUtils.hasText(requested.getProvider())
                && StringUtils.hasText(requested.getApiKey());
        AiCallConfig effective = (!alreadyResolvedForCaller && providerManagementService != null)
                ? providerManagementService.resolveEffectiveCallConfig(
                        requested,
                        requested != null ? requested.getTenantId() : null,
                        null,
                        requested != null ? requested.getEnvironment() : null)
                : requested;

        String primaryProvider = normalize(effective != null ? effective.getProvider() : null);
        if (!StringUtils.hasText(primaryProvider)) {
            primaryProvider = GoogleGenAiAgentToolGateway.PROVIDER_ID;
        }
        AiToolCallingProvider primaryDelegate = providersById.get(primaryProvider);
        if (primaryDelegate == null) {
            throw new IllegalStateException("Unsupported AI tool-calling provider: " + primaryProvider);
        }

        List<CandidateAttempt> candidates = buildOrderedCandidates(
                primaryProvider, requested, effective, request.executionContext());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No AI tool-calling provider eligible for turn execution.");
        }

        Instant deadline = (effective != null && effective.getTimeoutSeconds() != null && effective.getTimeoutSeconds() > 0)
                ? Instant.now().plusSeconds(effective.getTimeoutSeconds())
                : null;

        RuntimeException lastRetryableFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            CandidateAttempt candidate = candidates.get(i);
            AiCallConfig attemptConfig = applyRemainingTimeout(candidate.config(), deadline);
            AgentModelTurnRequest routedRequest = new AgentModelTurnRequest(
                    request.systemInstruction(),
                    request.conversation(),
                    request.tools(),
                    attemptConfig,
                    request.disableToolCalling(),
                    request.responseJsonSchema(),
                    request.executionContext());
            String modelName = attemptConfig != null ? attemptConfig.getModel() : null;
            if (attemptConfig != null && attemptConfig.getInvocationTrace() != null) {
                attemptConfig.getInvocationTrace().providerSelected(candidate.providerId(), modelName);
            }
            try {
                AgentModelTurnResponse response = candidate.delegate().generateTurn(routedRequest, listener);
                if (rateLimitStore != null) {
                    rateLimitStore.clearCooldown(candidate.providerId(), modelName);
                }
                return response;
            } catch (RuntimeException ex) {
                if (!isRetryableFailure(ex)) {
                    throw ex;
                }
                recordCooldownIfApplicable(candidate.providerId(), modelName, ex);
                lastRetryableFailure = ex;
                boolean hasMoreCandidates = i + 1 < candidates.size();
                if (!hasMoreCandidates) {
                    throw ex;
                }
                if (deadline != null && !Instant.now().isBefore(deadline)) {
                    throw AiProviderCallException.timeout(candidate.providerId(), ex);
                }
            }
        }
        throw lastRetryableFailure != null
                ? lastRetryableFailure
                : new IllegalStateException("All AI tool-calling provider candidates failed.");
    }

    private List<CandidateAttempt> buildOrderedCandidates(
            String primaryProvider,
            AiCallConfig requested,
            AiCallConfig effective,
            AgentExecutionContext requestContext) {
        List<String> providerIds = new ArrayList<>();
        providerIds.add(primaryProvider);
        for (String registered : providersById.keySet()) {
            if (!registered.equals(primaryProvider)) {
                providerIds.add(registered);
            }
        }

        AgentExecutionContext eligibilityContext = requestContext != null
                ? requestContext
                : new AgentExecutionContext(
                        null,
                        null,
                        null,
                        effective != null ? effective.getTenantId() : null,
                        null,
                        effective != null ? effective.getEnvironment() : null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        List<CandidateAttempt> healthy = new ArrayList<>();
        List<CandidateAttempt> coolingDown = new ArrayList<>();
        String firstDenialReason = null;
        Instant now = Instant.now();

        for (String candidateId : providerIds) {
            AiToolCallingProvider delegate = providersById.get(candidateId);
            if (delegate == null) {
                continue;
            }
            AiCallConfig candidateConfig = candidateId.equals(primaryProvider)
                    ? effective
                    : resolveFallbackConfig(requested, effective, candidateId);
            if (!candidateId.equals(primaryProvider) && !delegate.hasConfiguredCredentials(candidateConfig)) {
                continue;
            }
            String candidateModel = candidateConfig != null ? candidateConfig.getModel() : null;
            String denial = evaluateEligibility(candidateId, candidateModel, eligibilityContext);
            if (denial != null) {
                if (firstDenialReason == null) {
                    firstDenialReason = denial;
                }
                continue;
            }
            CandidateAttempt attempt = new CandidateAttempt(candidateId, delegate, candidateConfig);
            if (rateLimitStore != null && rateLimitStore.isInCooldown(candidateId, candidateModel, now)) {
                coolingDown.add(attempt);
            } else {
                healthy.add(attempt);
            }
        }

        if (healthy.isEmpty() && coolingDown.isEmpty()) {
            throw new IllegalStateException(firstDenialReason != null
                    ? firstDenialReason
                    : "AI provider rejected by eligibility policy: " + primaryProvider);
        }
        List<CandidateAttempt> ordered = new ArrayList<>(healthy.size() + coolingDown.size());
        ordered.addAll(healthy);
        ordered.addAll(coolingDown);
        return ordered;
    }

    private String evaluateEligibility(
            String providerId,
            String model,
            AgentExecutionContext context) {
        for (AiProviderEligibilityPolicy policy : eligibilityPolicies) {
            AiProviderEligibilityPolicy.EligibilityResult result = policy.evaluate(providerId, model, context);
            if (result != null && !result.eligible()) {
                return result.reason();
            }
        }
        return null;
    }

    private AiCallConfig resolveFallbackConfig(
            AiCallConfig requested,
            AiCallConfig effectivePrimary,
            String fallbackProvider) {
        AiCallConfig base = effectivePrimary != null
                ? effectivePrimary
                : (requested != null ? requested : AiCallConfig.builder().build());
        Map<String, String> overrides = base.getProviderModelOverrides();
        String overrideModel = overrides != null ? overrides.get(fallbackProvider) : null;
        AiCallConfig fallbackSeed = base.toBuilder()
                .provider(fallbackProvider)
                .model(overrideModel)
                .apiKey(null)
                .build();
        if (providerManagementService != null) {
            AiCallConfig resolved = providerManagementService.resolveEffectiveCallConfig(
                    fallbackSeed,
                    fallbackSeed.getTenantId(),
                    null,
                    fallbackSeed.getEnvironment());
            // Do not blindly forward stored primary provider's API key into another vendor's SDK client.
            return resolved != null
                    ? resolved.toBuilder().apiKey(null).build()
                    : fallbackSeed;
        }
        return fallbackSeed;
    }

    private static AiCallConfig applyRemainingTimeout(AiCallConfig config, Instant deadline) {
        if (config == null || deadline == null) {
            return config;
        }
        long remainingSeconds = Math.max(1L, Duration.between(Instant.now(), deadline).toSeconds());
        if (config.getTimeoutSeconds() != null && config.getTimeoutSeconds() <= remainingSeconds) {
            return config;
        }
        return config.toBuilder().timeoutSeconds((int) remainingSeconds).build();
    }

    private void recordCooldownIfApplicable(String providerId, String model, RuntimeException ex) {
        if (rateLimitStore == null || !(ex instanceof AiProviderCallException callEx)) {
            return;
        }
        AiProviderCallException.Kind kind = callEx.getKind();
        if (kind == AiProviderCallException.Kind.RATE_LIMIT
                || kind == AiProviderCallException.Kind.QUOTA_EXHAUSTED
                || kind == AiProviderCallException.Kind.CAPACITY) {
            Instant retryAfter = callEx.getRetryAfter() != null
                    ? callEx.getRetryAfter()
                    : Instant.now().plus(DEFAULT_COOLDOWN);
            rateLimitStore.recordCooldown(providerId, model, retryAfter, AiProviderFailureClassifier.classify(callEx));
        }
    }

    private static boolean isRetryableFailure(RuntimeException ex) {
        if (!(ex instanceof AiProviderCallException callEx)) {
            return false;
        }
        return switch (callEx.getKind()) {
            case RATE_LIMIT, QUOTA_EXHAUSTED, CAPACITY, TIMEOUT, TRANSPORT, SERVER_ERROR -> true;
            case AUTH, CLIENT_ERROR, UNKNOWN -> false;
        };
    }

    private static String normalize(String provider) {
        if (!StringUtils.hasText(provider)) {
            return null;
        }
        String lower = provider.trim().toLowerCase(Locale.ROOT);
        if ("open-ai".equals(lower)) {
            return OpenAiResponsesAgentToolGateway.PROVIDER_ID;
        }
        return lower;
    }

    private record CandidateAttempt(
            String providerId,
            AiToolCallingProvider delegate,
            AiCallConfig config) {
    }
}
