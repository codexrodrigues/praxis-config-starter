package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Package-private fail-closed policy shared by runtime defaults; it never validates a session itself. */
final class EnterpriseRuntimeProviderUnavailable {
    private EnterpriseRuntimeProviderUnavailable() {}

    static ResponseStatusException failure(EnterpriseRuntimeContextRequest request) {
        if (request == null || request.principal() == null) {
            return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_PROVIDER_UNAVAILABLE");
    }
}
