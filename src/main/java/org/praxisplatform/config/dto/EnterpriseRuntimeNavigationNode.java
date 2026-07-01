package org.praxisplatform.config.dto;

import java.util.List;

public record EnterpriseRuntimeNavigationNode(
        String id,
        String label,
        String type,
        String href,
        String route,
        String moduleKey,
        String resourceKey,
        String surfaceRef,
        String actionRef,
        String capabilityRef,
        List<EnterpriseRuntimeNavigationNode> children) {
}
