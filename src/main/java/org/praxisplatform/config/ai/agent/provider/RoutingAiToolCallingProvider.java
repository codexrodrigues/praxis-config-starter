package org.praxisplatform.config.ai.agent.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.springframework.util.StringUtils;

/**
 * Roteador neutro de {@link AiToolCallingProvider} que resolve provedor, chave e modelo pelo
 * {@link AiProviderManagementService} e delega para o gateway SDK correspondente.
 */
public class RoutingAiToolCallingProvider implements AiToolCallingProvider {

    private final AiProviderManagementService providerManagementService;
    private final Map<String, AiToolCallingProvider> providersById = new LinkedHashMap<>();

    public RoutingAiToolCallingProvider(
            AiProviderManagementService providerManagementService,
            List<AiToolCallingProvider> gateways) {
        this.providerManagementService = providerManagementService;
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
        AiCallConfig effective = providerManagementService != null
                ? providerManagementService.resolveEffectiveCallConfig(
                        requested,
                        requested != null ? requested.getTenantId() : null,
                        null,
                        requested != null ? requested.getEnvironment() : null)
                : requested;
        String resolvedProvider = normalize(effective != null ? effective.getProvider() : null);
        if (!StringUtils.hasText(resolvedProvider)) {
            resolvedProvider = GoogleGenAiAgentToolGateway.PROVIDER_ID;
        }
        AiToolCallingProvider delegate = providersById.get(resolvedProvider);
        if (delegate == null) {
            throw new IllegalStateException("Unsupported AI tool-calling provider: " + resolvedProvider);
        }
        AgentModelTurnRequest routedRequest = new AgentModelTurnRequest(
                request.systemInstruction(),
                request.conversation(),
                request.tools(),
                effective);
        return delegate.generateTurn(routedRequest, listener);
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
}
