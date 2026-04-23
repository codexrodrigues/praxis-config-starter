package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyReport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DomainFederationRetrievalPolicyService {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.7d;

    public Result apply(List<DomainCatalogItemResponse> items) {
        return apply(items, null);
    }

    public Result apply(List<DomainCatalogItemResponse> items, DomainFederationRetrievalPolicyOptions options) {
        List<DomainCatalogItemResponse> effectiveItems = items == null ? List.of() : items;
        double minConfidence = minConfidence(options);
        boolean includeDenied = includeDenied(options);
        boolean includeLowConfidence = includeLowConfidence(options);
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
                if (!includeDenied) {
                    decisions.add("Excluded " + item.itemKey() + " because aiUsage.visibility=deny.");
                    continue;
                }
                decisions.add("Included " + item.itemKey() + " despite aiUsage.visibility=deny because includeDenied=true.");
            }
            if (isGovernedSummary(item.payload())) {
                governedSummary++;
            }
            if (isLowConfidence(item.payload(), minConfidence)) {
                lowConfidence++;
                decisions.add("Flagged " + item.itemKey() + " as low confidence for federated retrieval.");
                if (!includeLowConfidence) {
                    decisions.add("Excluded " + item.itemKey() + " because confidence is below minConfidence.");
                    continue;
                }
            }
            returnedItems.add(item);
        }

        DomainFederationRetrievalPolicyReport report = new DomainFederationRetrievalPolicyReport(
                minConfidence,
                includeDenied,
                includeLowConfidence,
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

    private boolean isLowConfidence(JsonNode payload, double minConfidence) {
        if (payload == null) {
            return false;
        }
        JsonNode confidence = payload.path("confidence");
        return confidence.isNumber() && confidence.asDouble() < minConfidence;
    }

    private double minConfidence(DomainFederationRetrievalPolicyOptions options) {
        if (options == null || options.minConfidence() == null || options.minConfidence().isNaN()) {
            return LOW_CONFIDENCE_THRESHOLD;
        }
        return Math.max(0.0d, Math.min(1.0d, options.minConfidence()));
    }

    private boolean includeDenied(DomainFederationRetrievalPolicyOptions options) {
        return options != null && Boolean.TRUE.equals(options.includeDenied());
    }

    private boolean includeLowConfidence(DomainFederationRetrievalPolicyOptions options) {
        return options == null || options.includeLowConfidence() == null || Boolean.TRUE.equals(options.includeLowConfidence());
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
