package org.praxisplatform.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.security.Principal;

/** Internal provider input. UX hints cannot establish identity, membership, scope or capabilities. */
@Schema(hidden = true)
public record EnterpriseRuntimeContextRequest(
        @JsonIgnore Principal principal,
        String locale,
        String timezone,
        String activeModuleKey) {
    @Override public String toString() { return "EnterpriseRuntimeContextRequest[redacted]"; }
}
