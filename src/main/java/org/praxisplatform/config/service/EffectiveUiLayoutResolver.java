package org.praxisplatform.config.service;

import java.security.Principal;
import java.util.Optional;
import org.praxisplatform.config.dto.EffectiveUiLayoutResponse;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Read boundary for resolving one effective layout from host-authoritative context. */
@FunctionalInterface
public interface EffectiveUiLayoutResolver {
    Optional<EffectiveUiLayoutResponse> resolve(
            Principal principal, String expectedContextVersion, UiLayoutTarget target);
}
