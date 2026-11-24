package org.praxisplatform.config.service;

/**
 * Provider-agnostic reasoning effort / thinking budget profile applied by model gateways
 * (Gemini {@code ThinkingConfig} and OpenAI Responses {@code ReasoningEffort}).
 */
public enum AiThinkingProfile {

    /** Minimizes or disables reasoning tokens for lowest latency and deterministic lookup tasks. */
    OFF,

    /** Low reasoning budget suitable for quick catalog/entity resolution and short answers. */
    FAST_LOOKUP,

    /** Balanced reasoning budget recommended for multi-step operational tool loops and audits. */
    OPERATIONAL_AUDIT,

    /** High reasoning budget reserved for complex rule synthesis, root-cause analysis, and dossiers. */
    DEEP_ANALYSIS
}
