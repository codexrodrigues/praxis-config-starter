package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;

/**
 * Host validates authenticated issuance and live selection/membership before projecting state.
 * No selection is a valid authenticated state, not a reason to invent a default tenant.
 */
@FunctionalInterface
public interface EnterpriseRuntimeContextProvider {
    EnterpriseRuntimeContextResponse getContext(EnterpriseRuntimeContextRequest request);
}
