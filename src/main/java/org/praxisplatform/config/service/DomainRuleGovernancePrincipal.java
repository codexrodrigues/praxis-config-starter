package org.praxisplatform.config.service;

/** Server-resolved identity and scope for governed domain-rule reads and mutations. */
public record DomainRuleGovernancePrincipal(
    String tenantId,
    String actorRef,
    String environment) {}
