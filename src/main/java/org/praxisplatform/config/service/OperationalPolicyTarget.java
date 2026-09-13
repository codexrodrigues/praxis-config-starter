package org.praxisplatform.config.service;

import java.util.Set;

/** Canonical projection coordinate. Resource existence and authority remain host registry responsibilities. */
public record OperationalPolicyTarget(String targetLayer, String targetArtifactType, String targetArtifactKey) {
    public OperationalPolicyTarget {
        Family family = Family.of(targetLayer, targetArtifactType);
        if (targetArtifactKey == null || targetArtifactKey.isBlank() || targetArtifactKey.length() > 768
                || !targetArtifactKey.equals(targetArtifactKey.trim())) {
            throw new IllegalArgumentException("An exact policy target key is required");
        }
        int separator = targetArtifactKey.indexOf(':');
        String resource = family == Family.VALIDATION ? targetArtifactKey
                : separator < 0 ? "" : targetArtifactKey.substring(0, separator);
        String action = family == Family.VALIDATION ? null
                : separator < 0 ? "" : targetArtifactKey.substring(separator + 1);
        // Identifier validation after semantic target selection; this never routes user intent.
        if (!resource.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
                || (action != null && !action.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}"))) {
            throw new IllegalArgumentException("Invalid canonical policy target coordinate");
        }
    }

    public Family family() { return Family.of(targetLayer, targetArtifactType); }
    public String resourceKey() { return family() == Family.VALIDATION ? targetArtifactKey : targetArtifactKey.substring(0, targetArtifactKey.indexOf(':')); }
    public String actionId() { return family() == Family.VALIDATION ? null : targetArtifactKey.substring(targetArtifactKey.indexOf(':') + 1); }

    public enum Family {
        APPROVAL("approval_policy", "resource-action-approval", "approval_policy", "approvalPolicy",
                Set.of("approval_policy")),
        WORKFLOW("workflow_action", "resource-workflow-action", "workflow_action_policy", "availabilityPolicy",
                Set.of("workflow_action_policy")),
        VALIDATION("backend_validation", "resource-validation", "resource_validation_policy", "validationPolicy",
                Set.of("validation", "compliance", "privacy", "selection_eligibility"));

        final String layer, artifactType, kind, slot;
        final Set<String> ruleTypes;
        Family(String layer, String artifactType, String kind, String slot, Set<String> ruleTypes) {
            this.layer = layer; this.artifactType = artifactType; this.kind = kind; this.slot = slot; this.ruleTypes = ruleTypes;
        }
        public static Family of(String layer, String type) {
            for (Family family : values()) if (family.layer.equals(layer) && family.artifactType.equals(type)) return family;
            throw new IllegalArgumentException("Unsupported operational policy target family");
        }
        static boolean supports(String layer, String type) {
            for (Family family : values()) if (family.layer.equals(layer) && family.artifactType.equals(type)) return true;
            return false;
        }
    }
}
