package org.praxisplatform.config.service;

import java.util.List;
import java.util.Set;
import org.praxisplatform.config.dto.UiLayoutTarget;

public interface UiLayoutCandidateSource {
    List<UiLayoutResolutionCandidate> findActive(String tenant, String environment, UiLayoutTarget target);

    Set<String> authorablePaths(UiLayoutTarget target);

    Set<String> removablePaths(UiLayoutTarget target);

    /**
     * Validates component-specific value and identity constraints before a patch is merged.
     * The default is deliberately fail-closed so existing binary providers remain loadable but
     * cannot resolve candidates until they adopt an owning component policy.
     */
    default void validatePatch(UiLayoutTarget target, com.fasterxml.jackson.databind.JsonNode patch) {
        throw new UiLayoutResolutionException(
                UiLayoutResolutionException.Code.PROTECTED_PRESENTATION_INVARIANT,
                "Layout source does not declare a component presentation policy.", null);
    }
}
