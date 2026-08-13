package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DomainRuleChangeWorkspaceResponse(
    UUID id,
    String ruleKey,
    UUID baseDefinitionId,
    Integer baseDefinitionVersion,
    String baseDefinitionHash,
    UUID promotedDefinitionId,
    String title,
    String status,
    JsonNode condition,
    JsonNode parameters,
    String rationale,
    Long revision,
    String etag,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {}
