package org.praxisplatform.config.service;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.dto.DomainRuleWorkspaceBlocker;

/** Safe immutable projection of the reviewed Test Run used by a governed stage. */
public record DomainRuleDefinitionEvidenceDecision(
    UUID definitionId,
    String stage,
    boolean required,
    UUID workspaceId,
    UUID testRunId,
    String requestHash,
    Long workspaceRevision,
    String evidenceDigest,
    List<DomainRuleWorkspaceBlocker> blockers) {

  public boolean satisfied() {
    return blockers == null || blockers.isEmpty();
  }
}
