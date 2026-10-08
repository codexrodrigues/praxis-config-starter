package org.praxisplatform.config.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Universal canonical validator for UI layout releases.
 * Component-agnostic: supports any number of targets/components (e.g. Table, Form, Page Builder widgets).
 * Verifies aggregate completeness, member sequencing, target consistency, and revision linkage.
 */
public class CanonicalUiLayoutReleaseValidator implements UiLayoutReleaseValidator {

    private final UiLayoutCompositionRegistry compositionRegistry;

    public CanonicalUiLayoutReleaseValidator() {
        this(null);
    }

    public CanonicalUiLayoutReleaseValidator(UiLayoutCompositionRegistry compositionRegistry) {
        this.compositionRegistry = compositionRegistry;
    }

    @Override
    public void validate(UiLayoutRelease release, List<UiLayoutReleaseMember> members) {
        if (release == null || release.getId() == null) {
            throw invalid("Release and release ID must not be null.");
        }
        if (release.getRootComponentType() == null || release.getRootComponentType().isBlank()
                || release.getRootComponentId() == null || release.getRootComponentId().isBlank()) {
            throw invalid("Release root component type and ID must not be blank.");
        }
        if (members == null || members.isEmpty()) {
            throw invalid("Release must contain at least one member.");
        }

        UiLayoutTarget rootTarget = new UiLayoutTarget(release.getRootComponentType(), release.getRootComponentId());
        Set<UiLayoutTarget> seenTargets = new HashSet<>();

        for (int i = 0; i < members.size(); i++) {
            UiLayoutReleaseMember member = members.get(i);
            if (member == null) {
                throw invalid("Release member at index " + i + " is null.");
            }
            if (member.getMemberOrder() != i) {
                throw invalid("Release member order must match its index: expected " + i + ", was " + member.getMemberOrder());
            }
            if (!release.getId().equals(member.getReleaseId())) {
                throw invalid("Release member releaseId does not match release ID.");
            }
            if (member.getComponentType() == null || member.getComponentType().isBlank()
                    || member.getComponentId() == null || member.getComponentId().isBlank()) {
                throw invalid("Release member component type and ID must not be blank at index " + i);
            }
            if (member.getAssignmentRevisionId() == null || member.getContentRevisionId() == null) {
                throw invalid("Release member revisions must not be null at index " + i);
            }
            if (member.getContributionKey() == null || member.getContributionKey().isBlank()) {
                throw invalid("Release member contribution key must not be blank at index " + i);
            }

            UiLayoutTarget memberTarget = new UiLayoutTarget(member.getComponentType(), member.getComponentId());
            if (!seenTargets.add(memberTarget)) {
                throw invalid("Duplicate target in release members: " + memberTarget);
            }

            if (i == 0 && !rootTarget.equals(memberTarget)) {
                throw invalid("First release member must match the release root target: " + rootTarget);
            }
        }

        if (compositionRegistry != null) {
            UiLayoutCompositionRegistration registration = null;
            try {
                EffectiveUiAudience audience = (release.getTenantId() != null && !release.getTenantId().isBlank())
                        ? new EffectiveUiAudience(
                                release.getTenantId(),
                                release.getEnvironment(),
                                "system",
                                null, null, null, Set.of(), "release-validation")
                        : null;
                registration = compositionRegistry.resolve(audience, rootTarget);
            } catch (RuntimeException ignored) {
                // Registry requires active audience context; continue with structural checks
            }
            if (registration != null) {
                List<UiLayoutTarget> expectedTargets = registration.targets();
                if (expectedTargets.size() != members.size()) {
                    throw invalid("Release member count (" + members.size() + ") does not match registered composition target count (" + expectedTargets.size() + ").");
                }
                for (int i = 0; i < expectedTargets.size(); i++) {
                    UiLayoutTarget expected = expectedTargets.get(i);
                    UiLayoutReleaseMember member = members.get(i);
                    if (!expected.componentType().equals(member.getComponentType())
                            || !expected.componentId().equals(member.getComponentId())) {
                        throw invalid("Release member at index " + i + " does not match registered composition target: expected " + expected + ", was " + member.getComponentType() + ":" + member.getComponentId());
                    }
                }
            }
        }
    }

    private static UiLayoutLifecycleException invalid(String message) {
        return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_RELEASE, message);
    }
}
