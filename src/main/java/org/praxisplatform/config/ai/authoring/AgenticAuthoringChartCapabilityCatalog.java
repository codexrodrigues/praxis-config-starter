package org.praxisplatform.config.ai.authoring;

import java.util.List;
import java.util.Optional;

final class AgenticAuthoringChartCapabilityCatalog {

    static final AgenticAuthoringChartCapabilityCatalog INSTANCE = new AgenticAuthoringChartCapabilityCatalog(
            AgenticAuthoringComponentCapabilityCatalog.load("ai-authoring/chart-capabilities.v0.json"));

    private final AgenticAuthoringComponentCapabilityCatalog delegate;

    private AgenticAuthoringChartCapabilityCatalog(AgenticAuthoringComponentCapabilityCatalog delegate) {
        this.delegate = delegate;
    }

    boolean matchesAnyModificationPrompt(String prompt) {
        return delegate.matchesAnyModificationPrompt(prompt);
    }

    Optional<String> resolveChangeKind(String prompt) {
        return delegate.resolveChangeKind(prompt);
    }

    boolean supports(String changeKind, String prompt) {
        return delegate.supports(changeKind, prompt);
    }

    Optional<String> resolveField(String changeKind, String prompt) {
        return delegate.resolveField(changeKind, prompt);
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
