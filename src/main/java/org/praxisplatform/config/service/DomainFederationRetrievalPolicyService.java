package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyReport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DomainFederationRetrievalPolicyService {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.7d;
    private static final String DEFAULT_PROFILE = "explanation";

    public Result apply(List<DomainCatalogItemResponse> items) {
        return apply(items, null);
    }

    public Result apply(List<DomainCatalogItemResponse> items, DomainFederationRetrievalPolicyOptions options) {
        List<DomainCatalogItemResponse> effectiveItems = items == null ? List.of() : items;
        ResolvedPolicy policy = resolvePolicy(options);
        List<DomainCatalogItemResponse> returnedItems = new ArrayList<>();
        List<String> decisions = new ArrayList<>();
        int denied = 0;
        int governedSummary = 0;
        int lowConfidence = 0;

        for (DomainCatalogItemResponse item : effectiveItems) {
            if (item == null) {
                continue;
            }
            String denialReason = denialReason(item.payload());
            if (denialReason != null) {
                denied++;
                if (!policy.includeDenied()) {
                    decisions.add("Excluded " + item.itemKey() + " because " + denialReason + ".");
                    continue;
                }
                decisions.add("Included " + item.itemKey() + " despite " + denialReason + " because policyProfile="
                        + policy.profile() + " allows it.");
            }
            if (isGovernedSummary(item.payload())) {
                governedSummary++;
            }
            if (isLowConfidence(item.payload(), policy.minConfidence())) {
                lowConfidence++;
                decisions.add("Flagged " + item.itemKey() + " as low confidence for federated retrieval.");
                if (!policy.includeLowConfidence()) {
                    decisions.add("Excluded " + item.itemKey() + " because confidence is below minConfidence.");
                    continue;
                }
            }
            returnedItems.add(item);
        }

        DomainFederationRetrievalPolicyReport report = new DomainFederationRetrievalPolicyReport(
                policy.profile(),
                policy.minConfidence(),
                policy.includeDenied(),
                policy.includeLowConfidence(),
                effectiveItems.size(),
                returnedItems.size(),
                denied,
                governedSummary,
                lowConfidence,
                List.copyOf(decisions));
        return new Result(List.copyOf(returnedItems), report);
    }

    private String denialReason(JsonNode payload) {
        if ("deny".equals(aiVisibility(payload))) {
            return "aiUsage.visibility=deny";
        }
        String contractVisibility = text(payload == null ? null : payload.path("contract"), "visibility");
        if ("deny_for_llm".equals(contractVisibility)) {
            return "contract.visibility=deny_for_llm";
        }
        if ("restricted".equals(contractVisibility)) {
            return "contract.visibility=restricted";
        }
        String resolutionVisibility = text(payload == null ? null : payload.path("resolution"), "visibility");
        if ("deny_for_llm".equals(resolutionVisibility)) {
            return "resolution.visibility=deny_for_llm";
        }
        if ("restricted".equals(resolutionVisibility)) {
            return "resolution.visibility=restricted";
        }
        return null;
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
        if (!confidence.isNumber()) {
            confidence = payload.path("evidence").path("confidence");
        }
        if (!confidence.isNumber()) {
            confidence = payload.path("resolution").path("confidence");
        }
        return confidence.isNumber() && confidence.asDouble() < minConfidence;
    }

    private ResolvedPolicy resolvePolicy(DomainFederationRetrievalPolicyOptions options) {
        ResolvedPolicy profile = profile(options == null ? null : options.policyProfile());
        double minConfidence = profile.minConfidence();
        boolean includeDenied = profile.includeDenied();
        boolean includeLowConfidence = profile.includeLowConfidence();

        if (options != null && options.minConfidence() != null && !options.minConfidence().isNaN()) {
            minConfidence = clampConfidence(options.minConfidence());
        }
        if (options != null && options.includeDenied() != null) {
            includeDenied = options.includeDenied();
        }
        if (options != null && options.includeLowConfidence() != null) {
            includeLowConfidence = options.includeLowConfidence();
        }

        return new ResolvedPolicy(profile.profile(), minConfidence, includeDenied, includeLowConfidence);
    }

    private ResolvedPolicy profile(String value) {
        String profile = StringUtils.hasText(value)
                ? value.trim().toLowerCase(Locale.ROOT).replace('-', '_')
                : DEFAULT_PROFILE;
        return switch (profile) {
            case "authoring" -> new ResolvedPolicy("authoring", 0.8d, false, false);
            case "compliance_review" -> new ResolvedPolicy("compliance_review", 0.9d, false, false);
            case "diagnostics" -> new ResolvedPolicy("diagnostics", 0.0d, true, true);
            case "explanation" -> new ResolvedPolicy("explanation", LOW_CONFIDENCE_THRESHOLD, false, true);
            default -> new ResolvedPolicy(DEFAULT_PROFILE, LOW_CONFIDENCE_THRESHOLD, false, true);
        };
    }

    private double clampConfidence(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
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

    private record ResolvedPolicy(
            String profile,
            double minConfidence,
            boolean includeDenied,
            boolean includeLowConfidence
    ) {
    }
}
