package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AgenticAuthoringPresentationAffordanceDiscoveryService {

    private final List<AgenticAuthoringPresentationAffordanceProvider> providers;

    public AgenticAuthoringPresentationAffordanceDiscoveryService(
            List<AgenticAuthoringPresentationAffordanceProvider> providers) {
        this.providers = providers == null
                ? List.of()
                : List.copyOf(providers.stream()
                        .filter(Objects::nonNull)
                        .toList());
    }

    static AgenticAuthoringPresentationAffordanceDiscoveryService defaultService(ObjectMapper objectMapper) {
        return new AgenticAuthoringPresentationAffordanceDiscoveryService(List.of(
                AgenticAuthoringResourceBackedPresentationAffordanceProvider.defaultProvider(objectMapper)));
    }

    Optional<JsonNode> discover(PresentationAffordanceDiscoveryToolRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        return providers.stream()
                .filter(provider -> provider.supports(request))
                .findFirst()
                .map(provider -> provider.discover(request));
    }
}
