package org.praxisplatform.config.ai.authoring;

import java.util.Optional;

/**
 * Host extension point for page-level agentic authoring plans.
 *
 * <p>The config starter owns the HTTP envelope, but hosts own their concrete business APIs and
 * demo domains. Providers let a host contribute a validated {@code UiCompositionPlan} without
 * hardcoding host-specific resources into the starter.</p>
 */
public interface AgenticAuthoringUiCompositionPlanProvider {

    Optional<AgenticAuthoringUiCompositionPlanResult> plan(AgenticAuthoringPlanRequest request);
}
