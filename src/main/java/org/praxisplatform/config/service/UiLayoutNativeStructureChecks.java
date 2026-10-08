package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

/**
 * Shared, bounded Java checks for native authoring data accepted by Config.
 * These checks neither execute UI libraries nor grant source, target or operation admission.
 * Hosts retain their policy and provenance checks; renderer semantics remain in the UI owners.
 */
public final class UiLayoutNativeStructureChecks {
    private UiLayoutNativeStructureChecks() {}

    /** Validate a supported native baseline without recapturing or normalizing its authorship. */
    public static void requireBaseline(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
            UiLayoutAuthoringDocumentDescriptor descriptor, JsonNode baseline,
            UiLayoutPatchDocumentDescriptor patchDescriptor, UiLayoutValidationContext validation) {
        Objects.requireNonNull(validation, "validation context is required");
        validation.requirePurpose(UiLayoutValidationPurpose.AUTHORING, UiLayoutValidationPurpose.FROZEN_RELEASE,
                UiLayoutValidationPurpose.EVOLUTION_CURRENT_READ);
        validation.run(invocation, () -> UiLayoutAuthoringProjection.project(target, descriptor, baseline, baseline,
                patchDescriptor, () -> validation.require(invocation)));
    }

    /**
     * Validate an existing immutable contribution against its original baseline and candidate.
     * Does not compile or replace the patch: historical pins/resets retain their original meaning.
     */
    public static void requireRevision(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
            UiLayoutAuthoringDocumentDescriptor baselineDescriptor, JsonNode baseline,
            UiLayoutAuthoringDocumentDescriptor candidateDescriptor, JsonNode candidate,
            UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patch, UiLayoutValidationContext validation) {
        Objects.requireNonNull(validation, "validation context is required");
        validation.requirePurpose(UiLayoutValidationPurpose.AUTHORING, UiLayoutValidationPurpose.FROZEN_RELEASE);
        validation.run(invocation, () -> {
            if (!Objects.equals(baselineDescriptor, candidateDescriptor)) {
                throw new UiLayoutLifecycleException(Code.VALIDATION_FAILED, "Native revision descriptors differ.");
            }
            var checkpoint = (Runnable) () -> validation.require(invocation);
            var projection = UiLayoutAuthoringProjection.project(target, baselineDescriptor, baseline, candidate,
                    patchDescriptor, checkpoint);
            UiLayoutJsonBounds.requireDocument(patch, Code.INVALID_REQUEST, checkpoint);
            UiLayoutRevisionRelation.requireReproduction(projection.baselineConfig(), projection.candidateConfig(),
                    (ObjectNode) patch);
            checkpoint.run();
        });
    }
}
