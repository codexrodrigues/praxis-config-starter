package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainRuleChangeWorkspaceUpdateRequest(
    JsonNode condition,
    JsonNode parameters,
    String rationale) {}
