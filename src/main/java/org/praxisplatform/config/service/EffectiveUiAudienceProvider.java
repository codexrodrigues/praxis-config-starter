package org.praxisplatform.config.service;

import java.security.Principal;

/** Host SPI that resolves trusted audience facts from an authenticated principal. */
@FunctionalInterface
public interface EffectiveUiAudienceProvider {
    EffectiveUiAudience resolve(Principal principal, String expectedContextVersion);
}
