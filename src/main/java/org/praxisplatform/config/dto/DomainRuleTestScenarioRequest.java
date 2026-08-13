package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainRuleTestScenarioRequest(
    String scenarioKey,
    String name,
    JsonNode facts,
    String expectedDecision,
    JsonNode expectedOutput,
    String status) {}
