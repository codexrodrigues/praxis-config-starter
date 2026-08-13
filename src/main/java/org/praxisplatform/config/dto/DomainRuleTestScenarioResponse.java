package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DomainRuleTestScenarioResponse(
    UUID id,
    UUID workspaceId,
    String scenarioKey,
    String name,
    JsonNode facts,
    String expectedDecision,
    JsonNode expectedOutput,
    String status,
    Long revision,
    String etag,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {}
