package org.praxisplatform.config.dto;

import java.util.UUID;

public record DomainRuleChangeWorkspaceCreateRequest(UUID baseDefinitionId, String title) {}
