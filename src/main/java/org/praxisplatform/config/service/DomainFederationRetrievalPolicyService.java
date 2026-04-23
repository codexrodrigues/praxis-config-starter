package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyReport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DomainFederationRetrievalPolicyService {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.7d;

    public Result apply(List<DomainCatalogItemResponse> items) {
        List<DomainCatalogItemResponse> effectiveItems = items == null ? List.of() : items;
        List<DomainCatalogItemResponse> returnedItems = new ArrayList<>();
        List<String> decisions = new ArrayList<>();
        int denied = 0;
        int governedSummary = 0;
        int lowConfidence = 0;

        for (DomainCatalogItemResponse item : effectiveItems) {
            if (item == null) {
                continue;
            }
            if (deniesAiVisibility(item.payload())) {
                denied++;
                decisions.add("Excluded " + item.itemKey() + " because aiUsage.visibility=deny.");
                continue;
            }
            if (isGovernedSummary(item.payload())) {
                governedSummary++;
            }
            if (isLowConfidence(item.payload())) {
                lowConfidence++;
                decisions.add("Flagged " + item.itemKey() + " as low confidence for federated retrieval.");
            }
            returnedItems.add(item);
        }

        DomainFederationRetrievalPolicyReport report = new DomainFederationRetrievalPolicyReport(
                effectiveItems.size(),
                returnedItems.size(),
                denied,
                governedSummary,
                lowConfidence,
                List.copyOf(decisions));
        return new Result(List.copyOf(returnedItems), report);
    }

    private boolean deniesAiVisibility(JsonNode payload) {
        return "deny".equals(aiVisibility(payload));
    }

    private boolean isGovernedSummary(JsonNode payload) {
        String mode = text(payload, "payloadMode");
        String visibility = text(payload, "contextVisibility");
        return "governed-summary".equals(mode) || "mask".equals(visibility) || "summarize_only".equals(visibility);
    }

    private boolean isLowConfidence(JsonNode payload) {
        if (payload == null) {
            return false;
        }
        JsonNode confidence = payload.path("confidence");
        return confidence.isNumber() && confidence.asDouble() < LOW_CONFIDENCE_THRESHOLD;
    }

    private String aiVisibility(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        String visibility = text(payload.path("aiUsage"), "visibility");
        return visibility == null ? null : visibility.toLowerCase();
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !StringUtils.hasText(fieldName)) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        return value.isTextual() ? value.asText().toLowerCase() : null;
    }

    public record Result(
            List<DomainCatalogItemResponse> items,
            DomainFederationRetrievalPolicyReport report
    ) {
    }
}
