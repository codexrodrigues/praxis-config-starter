package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgenticAuthoringPresentationAffordanceProvider {

    boolean supports(PresentationAffordanceDiscoveryToolRequest request);

    JsonNode discover(PresentationAffordanceDiscoveryToolRequest request);
}
