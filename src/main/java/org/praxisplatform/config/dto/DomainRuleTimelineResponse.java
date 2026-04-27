package org.praxisplatform.config.dto;

import java.util.List;
import java.util.UUID;

public record DomainRuleTimelineResponse(
        UUID ruleDefinitionId,
        String tenantId,
        String environment,
        String ruleKey,
        Integer ruleVersion,
        String ruleType,
        String resourceKey,
        String serviceKey,
        List<DomainRuleTimelineEventResponse> events
) {
}
