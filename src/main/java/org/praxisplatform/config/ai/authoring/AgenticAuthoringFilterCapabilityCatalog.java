package org.praxisplatform.config.ai.authoring;

import java.util.List;
import java.util.Optional;

final class AgenticAuthoringFilterCapabilityCatalog {

    static final AgenticAuthoringFilterCapabilityCatalog INSTANCE = new AgenticAuthoringFilterCapabilityCatalog(
            AgenticAuthoringComponentCapabilityCatalog.load("ai-authoring/filter-capabilities.v0.json"));

    private final AgenticAuthoringComponentCapabilityCatalog delegate;

    private AgenticAuthoringFilterCapabilityCatalog(AgenticAuthoringComponentCapabilityCatalog delegate) {
        this.delegate = delegate;
    }

    boolean matchesAnyModificationPrompt(String normalizedPrompt) {
        return delegate.matchesAnyModificationPrompt(normalizedPrompt);
    }

    Optional<String> resolveChangeKind(String normalizedPrompt) {
        return delegate.resolveChangeKind(normalizedPrompt);
    }

    boolean supports(String changeKind, String normalizedPrompt) {
        return delegate.supports(changeKind, normalizedPrompt);
    }

    Optional<String> resolveField(String changeKind, String normalizedPrompt) {
        return delegate.resolveField(changeKind, normalizedPrompt);
    }

    List<AgenticAuthoringComponentCapabilityCatalog.ComponentCapability> capabilities() {
        return delegate.capabilities();
    }

    String version() {
        return delegate.version();
    }

    String componentId() {
        return delegate.componentId();
    }
}
