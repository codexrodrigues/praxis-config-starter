package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Server-owned actions and business blockers for one governed Policy Studio workspace.")
public record DomainRuleWorkspaceCapabilityResponse(
    UUID workspaceId,
    String ruleKey,
    String status,
    Long revision,
    String etag,
    List<String> availableActions,
    List<DomainRuleWorkspaceBlocker> blockers) {
  public DomainRuleWorkspaceCapabilityResponse {
    availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
    blockers = blockers == null ? List.of() : List.copyOf(blockers);
  }
}
