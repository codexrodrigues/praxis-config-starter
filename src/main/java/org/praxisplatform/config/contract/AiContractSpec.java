package org.praxisplatform.config.contract;

import java.util.List;

/**
 * Generated from docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml.
 * Do not edit manually. Run tools/contracts/generate-ai-contract-bindings.js.
 */
public final class AiContractSpec {

    public static final String CONTRACT_VERSION = "v1.1";
    public static final String CONTRACT_SCHEMA_HASH = "8120c06a6daa75a0ddad250da394bbc54f92128fb2d229e6c3ea848841fc768c";
    public static final String STREAM_EVENT_SCHEMA_VERSION = "v1";
    public static final String DOMAIN_CATALOG_CONTEXT_HINT_SCHEMA_VERSION = "praxis.ai.context-hints.domain-catalog/v0.2";
    public static final List<String> STREAM_EVENT_TYPES = List.of("status", "thought.step", "heartbeat", "intent.resolved", "result", "error", "cancelled");

    private AiContractSpec() {
    }
}
