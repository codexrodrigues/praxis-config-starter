package org.praxisplatform.config.dto;

import java.util.List;

public record EnterpriseRuntimeNavigationNode(
        String id,
        String label,
        String icon,
        String type,
        String href,
        String route,
        String moduleKey,
        String resourceKey,
        String surfaceRef,
        String actionRef,
        String capabilityRef,
        List<EnterpriseRuntimeNavigationNode> children) {
    public EnterpriseRuntimeNavigationNode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Navigation identity is required.");
        children = children == null ? null : List.copyOf(children);
    }
}
