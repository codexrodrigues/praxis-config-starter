package org.praxisplatform.config.dto;

/** Sanitized host evidence for a state-changing CREATE or UPDATE policy scenario. */
public record DomainRuleOperationalTestEvidence(
    String operationMode,
    String beforeStateDigest,
    String afterStateDigest,
    boolean mutationObserved,
    boolean noMutationVerified,
    boolean cleanupVerified,
    String effectLedgerDigest,
    int baselineCallCount) {}
