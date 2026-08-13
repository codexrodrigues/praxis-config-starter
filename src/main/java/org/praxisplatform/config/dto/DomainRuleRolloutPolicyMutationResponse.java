package org.praxisplatform.config.dto;

/** One policy lifecycle result bound to the current independent policy-head ETag. */
public record DomainRuleRolloutPolicyMutationResponse(
    DomainRuleRolloutPolicyResponse policy,
    long activationRevision,
    String headEtag) {}
