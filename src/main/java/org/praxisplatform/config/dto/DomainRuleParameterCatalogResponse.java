package org.praxisplatform.config.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.rules.contract.RuleParameterInvariant;

/** Governed parameter catalog; absence of a template is represented by an empty catalog. */
public record DomainRuleParameterCatalogResponse(
    UUID definitionId, String ruleKey, Integer definitionVersion, String schemaVersion,
    String templateKey, String templateVersion, List<DomainRuleParameterDescriptor> parameters,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<RuleParameterInvariant> invariants) {

  /** Normalizes nullable invariants into an immutable list. */
  public DomainRuleParameterCatalogResponse {
    invariants = invariants == null ? List.of() : List.copyOf(invariants);
  }

  /** Backward-compatible constructor omitting Contract 1.8 cross-parameter invariants. */
  public DomainRuleParameterCatalogResponse(
      UUID definitionId, String ruleKey, Integer definitionVersion, String schemaVersion,
      String templateKey, String templateVersion, List<DomainRuleParameterDescriptor> parameters) {
    this(definitionId, ruleKey, definitionVersion, schemaVersion, templateKey, templateVersion, parameters, List.of());
  }
}
