package org.praxisplatform.config.dto;

import java.util.List;

/** Version catalog plus the independent anti-ABA policy-head identity. */
public record DomainRuleRolloutPolicyCatalogResponse(
    String ruleSetKey,
    long activationRevision,
    String headEtag,
    DomainRuleRolloutPolicyResponse activePolicy,
    List<DomainRuleRolloutPolicyResponse> versions,
    List<String> availableActions) {}
