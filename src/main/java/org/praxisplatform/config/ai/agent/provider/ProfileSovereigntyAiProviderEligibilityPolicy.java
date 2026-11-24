package org.praxisplatform.config.ai.agent.provider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.springframework.util.StringUtils;

/**
 * Configurable {@link AiProviderEligibilityPolicy} enforcing fail-closed provider sovereignty for
 * restricted execution profiles, data classifications, or sensitive resource paths.
 */
public class ProfileSovereigntyAiProviderEligibilityPolicy implements AiProviderEligibilityPolicy {

    private final Set<String> restrictedProfiles;
    private final Set<String> restrictedResourcePrefixes;
    private final Set<String> restrictedTopics;
    private final Set<String> restrictedPathSegments;
    private final Set<String> sovereignProviders;
    private final boolean strictFailClosed;

    public ProfileSovereigntyAiProviderEligibilityPolicy(
            List<String> restrictedProfiles,
            List<String> restrictedResourcePrefixes,
            List<String> sovereignProviders,
            boolean strictFailClosed) {
        this(
                restrictedProfiles,
                restrictedResourcePrefixes,
                List.of(),
                List.of(),
                sovereignProviders,
                strictFailClosed);
    }

    public ProfileSovereigntyAiProviderEligibilityPolicy(
            List<String> restrictedProfiles,
            List<String> restrictedResourcePrefixes,
            List<String> restrictedTopics,
            List<String> restrictedPathSegments,
            List<String> sovereignProviders,
            boolean strictFailClosed) {
        this.restrictedProfiles = normalizeLowerSet(restrictedProfiles);
        this.restrictedResourcePrefixes = normalizeTrimmedSet(restrictedResourcePrefixes);
        this.restrictedTopics = normalizeLowerSet(restrictedTopics);
        this.restrictedPathSegments = normalizeLowerSet(restrictedPathSegments);
        this.sovereignProviders = normalizeProviderSet(sovereignProviders);
        this.strictFailClosed = strictFailClosed;
    }

    @Override
    public EligibilityResult evaluate(String providerId, String model, AgentExecutionContext context) {
        boolean explicitClaimRestriction = isExplicitlyRestrictedInClaims(context);
        if (!explicitClaimRestriction
                && restrictedProfiles.isEmpty()
                && restrictedResourcePrefixes.isEmpty()
                && restrictedTopics.isEmpty()
                && restrictedPathSegments.isEmpty()) {
            return EligibilityResult.allow();
        }
        if (!explicitClaimRestriction && !matchesConfiguredRestriction(context)) {
            return EligibilityResult.allow();
        }
        String normalizedProvider = normalizeProviderId(providerId);
        if (sovereignProviders.contains(normalizedProvider)) {
            return EligibilityResult.allow();
        }
        if (sovereignProviders.isEmpty() && !strictFailClosed) {
            return EligibilityResult.allow();
        }
        return EligibilityResult.deny(
                "Provider '" + providerId + "' denied by sovereignty policy for restricted context");
    }

    private boolean matchesConfiguredRestriction(AgentExecutionContext context) {
        if (context == null) {
            return false;
        }
        Map<String, Object> claims = context.propagatedClaims();
        if (claims != null && !claims.isEmpty() && !restrictedProfiles.isEmpty()) {
            for (String key : List.of("executionProfile", "dataClassification", "sovereigntyProfile", "domainSensitivity")) {
                Object value = claims.get(key);
                if (value != null && restrictedProfiles.contains(value.toString().trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        String topicKey = context.activeTopicKey();
        if (StringUtils.hasText(topicKey) && !restrictedTopics.isEmpty()) {
            if (restrictedTopics.contains(topicKey.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        String screenPath = context.activeScreenResourcePath();
        if (StringUtils.hasText(screenPath)) {
            String normalizedPath = screenPath.trim().toLowerCase(Locale.ROOT);
            for (String prefix : restrictedResourcePrefixes) {
                if (normalizedPath.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            for (String segment : restrictedPathSegments) {
                if (normalizedPath.contains(segment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isExplicitlyRestrictedInClaims(AgentExecutionContext context) {
        if (context == null || context.propagatedClaims() == null || context.propagatedClaims().isEmpty()) {
            return false;
        }
        Object sovereignOnly = context.propagatedClaims().get("sovereignOnly");
        return Boolean.TRUE.equals(sovereignOnly)
                || (sovereignOnly != null && "true".equalsIgnoreCase(sovereignOnly.toString().trim()));
    }

    private static Set<String> normalizeProviderSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                out.add(normalizeProviderId(value));
            }
        }
        return Set.copyOf(out);
    }

    private static String normalizeProviderId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("open-ai".equals(normalized)) {
            return OpenAiResponsesAgentToolGateway.PROVIDER_ID;
        }
        return normalized;
    }

    private static Set<String> normalizeLowerSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private static Set<String> normalizeTrimmedSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                out.add(value.trim());
            }
        }
        return Set.copyOf(out);
    }
}
