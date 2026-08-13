package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

/** Safe append-only lifecycle evidence for one rollout-policy version. */
public record DomainRuleRolloutPolicyEventResponse(
    UUID eventId,
    UUID policyId,
    String eventType,
    String actorRef,
    String headEtag,
    Instant createdAt) {}
