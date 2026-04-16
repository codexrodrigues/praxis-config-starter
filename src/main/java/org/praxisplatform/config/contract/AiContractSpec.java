package org.praxisplatform.config.contract;

import java.util.List;

/**
 * Generated from docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml.
 * Do not edit manually. Run tools/contracts/generate-ai-contract-bindings.js.
 */
public final class AiContractSpec {

    public static final String CONTRACT_VERSION = "v1.1";
    public static final String CONTRACT_SCHEMA_HASH = "dacaba8fa7ec21ee9ae31bab7057614837d2d1320d784d0ee61fb840efe55a35";
    public static final String STREAM_EVENT_SCHEMA_VERSION = "v1";
    public static final List<String> STREAM_EVENT_TYPES = List.of("status", "thought.step", "heartbeat", "result", "error", "cancelled");

    private AiContractSpec() {
    }
}
