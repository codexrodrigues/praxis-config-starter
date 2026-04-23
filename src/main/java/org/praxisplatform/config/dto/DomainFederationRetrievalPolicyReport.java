package org.praxisplatform.config.dto;

import java.util.List;

public record DomainFederationRetrievalPolicyReport(
        double minConfidence,
        boolean includeDenied,
        boolean includeLowConfidence,
        int inputItemCount,
        int returnedItemCount,
        int deniedItemCount,
        int governedSummaryItemCount,
        int lowConfidenceItemCount,
        List<String> decisions
) {
}
