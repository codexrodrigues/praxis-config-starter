package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record DomainRuleTestScenarioRequest(
    String scenarioKey,
    String name,
    JsonNode facts,
    String expectedDecision,
    JsonNode expectedOutput,
    List<String> expectedReasonCodes,
    List<String> expectedEffectIntents,
    String status) {}
