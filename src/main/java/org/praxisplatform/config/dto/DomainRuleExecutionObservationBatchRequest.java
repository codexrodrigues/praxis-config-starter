package org.praxisplatform.config.dto;

import java.util.List;

/** Bounded asynchronous delivery unit for redacted runtime observations. */
public record DomainRuleExecutionObservationBatchRequest(
    List<DomainRuleExecutionObservationRequest> observations) {}
