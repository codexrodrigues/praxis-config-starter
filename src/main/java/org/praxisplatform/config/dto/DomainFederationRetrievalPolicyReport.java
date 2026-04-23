package org.praxisplatform.config.dto;

import java.util.List;

public record DomainFederationRetrievalPolicyReport(
        int inputItemCount,
        int returnedItemCount,
        int deniedItemCount,
        int governedSummaryItemCount,
        int lowConfidenceItemCount,
        List<String> decisions
) {
}
