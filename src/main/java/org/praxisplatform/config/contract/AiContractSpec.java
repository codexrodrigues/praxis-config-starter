package org.praxisplatform.config.contract;

import java.util.List;

/**
 * Generated from docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml.
 * Do not edit manually. Run tools/contracts/generate-ai-contract-bindings.js.
 */
public final class AiContractSpec {

    public static final String CONTRACT_VERSION = "v1.1";
    public static final String CONTRACT_SCHEMA_HASH = "67016c9ee2035e6671d3c2edf375f30ae46ffafb064cb11b49b8d032d99d1258";
    public static final String STREAM_EVENT_SCHEMA_VERSION = "v1";
    public static final String DOMAIN_CATALOG_CONTEXT_HINT_SCHEMA_VERSION = "praxis.ai.context-hints.domain-catalog/v0.1";
    public static final List<String> STREAM_EVENT_TYPES = List.of("status", "thought.step", "heartbeat", "result", "error", "cancelled");

    private AiContractSpec() {
    }
}
