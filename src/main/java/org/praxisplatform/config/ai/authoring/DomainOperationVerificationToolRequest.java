package org.praxisplatform.config.ai.authoring;

record DomainOperationVerificationToolRequest(String resourceKey, String resourcePath, String requestBaseUrl) {
    DomainOperationVerificationToolRequest(String resourceKey, String requestBaseUrl) {
        this(resourceKey, null, requestBaseUrl);
    }
}
