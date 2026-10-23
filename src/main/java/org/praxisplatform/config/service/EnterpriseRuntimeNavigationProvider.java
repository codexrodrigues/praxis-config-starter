package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;

/**
 * Current host-authorized final navigation. Implementations changing a Config fragment must
 * recompute the final projection receipt/cursor and recheck authority before returning.
 */
public interface EnterpriseRuntimeNavigationProvider {

    EnterpriseRuntimeNavigationResponse getNavigation(EnterpriseRuntimeContextRequest request,
            String expectedContextVersion, String parentRef, String cursor, int pageSize);
}
