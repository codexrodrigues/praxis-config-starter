package org.praxisplatform.config.contract;

import java.util.List;

/**
 * Generated from docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml.
 * Do not edit manually. Run tools/contracts/generate-ai-contract-bindings.js.
 */
public final class AiContractSpec {

    public static final String CONTRACT_VERSION = "v1.1";
    public static final String CONTRACT_SCHEMA_HASH = "9e62b19418d44ea2ddb7e65b63bc71c65b1a559258192b61fc6977ea6353d473";
    public static final String STREAM_EVENT_SCHEMA_VERSION = "v1";
    public static final String DOMAIN_CATALOG_CONTEXT_HINT_SCHEMA_VERSION = "praxis.ai.context-hints.domain-catalog/v0.2";
    public static final List<String> STREAM_EVENT_TYPES = List.of("status", "thought.step", "heartbeat", "intent.resolved", "result", "error", "cancelled");

    private AiContractSpec() {
    }
}
