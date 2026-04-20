package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestCompileResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestEditPlanRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestValidationResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResolveTargetRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResolvedTarget;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunErrorResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/ai/authoring")
@ConditionalOnProperty(prefix = "praxis.ai.authoring", name = "http-enabled", havingValue = "true")
@ConditionalOnBean(AgenticAuthoringManifestService.class)
@RequiredArgsConstructor
public class AgenticAuthoringManifestController {

    private final AgenticAuthoringManifestService manifestService;

    @GetMapping("/manifests/{componentId}")
    public ResponseEntity<?> getManifest(@PathVariable String componentId) {
        try {
            JsonNode manifest = manifestService.getManifest(componentId);
            return ResponseEntity.ok(manifest);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @GetMapping("/manifests/{componentId}/editable-targets")
    public ResponseEntity<?> listEditableTargets(@PathVariable String componentId) {
        try {
            return ResponseEntity.ok(manifestService.listEditableTargets(componentId));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @GetMapping("/manifests/{componentId}/operations")
    public ResponseEntity<?> listOperations(@PathVariable String componentId) {
        try {
            return ResponseEntity.ok(manifestService.listOperations(componentId));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @PostMapping("/manifests/{componentId}/resolve-target")
    public ResponseEntity<?> resolveTarget(
            @PathVariable String componentId,
            @RequestBody AgenticAuthoringResolveTargetRequest request) {
        try {
            AgenticAuthoringResolvedTarget result = manifestService.resolveTarget(componentId, request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @PostMapping("/manifests/{componentId}/validate-plan")
    public ResponseEntity<?> validatePlan(
            @PathVariable String componentId,
            @RequestBody AgenticAuthoringManifestEditPlanRequest request) {
        try {
            AgenticAuthoringManifestValidationResult result = manifestService.validateEditPlan(componentId, request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @PostMapping("/manifests/{componentId}/compile-patch")
    public ResponseEntity<?> compilePatch(
            @PathVariable String componentId,
            @RequestBody AgenticAuthoringManifestEditPlanRequest request) {
        try {
            AgenticAuthoringManifestCompileResult result = manifestService.compilePatch(componentId, request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }
}
