package org.praxisplatform.config.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.service.AiInteractionLogger;
import org.praxisplatform.config.service.AiOrchestratorService;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/praxis/config/ai")
@RequiredArgsConstructor
@Slf4j
public class AiOrchestratorController {

    private final AiOrchestratorService orchestratorService;
    private final AiInteractionLogger interactionLogger;

    @PostMapping("/patch")
    public ResponseEntity<AiOrchestratorResponse> generatePatch(
            @Valid @RequestBody AiOrchestratorRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        String resolvedRequestId = requestId != null && !requestId.isBlank()
                ? requestId
                : UUID.randomUUID().toString();
        MDC.put("requestId", resolvedRequestId);
        try {
            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            AiOrchestratorResponse response = orchestratorService.generatePatch(
                    request,
                    baseUrl,
                    tenantId,
                    userId,
                    environment);
            interactionLogger.logFrontendResponse(request, response);
            return ResponseEntity.status(resolveStatus(response)).body(response);
        } catch (Exception ex) {
            log.error(
                    "[AiOrchestratorController] Patch generation failed (componentId={}, componentType={}, aiMode={}, variantId={})",
                    request.getComponentId(),
                    request.getComponentType(),
                    request.getAiMode(),
                    request.getVariantId(),
                    ex);
            throw ex;
        } finally {
            MDC.remove("requestId");
        }
    }

    private HttpStatus resolveStatus(AiOrchestratorResponse response) {
        if (response == null || response.getCode() == null) {
            return HttpStatus.OK;
        }
        return switch (response.getCode()) {
            case "UNKNOWN_COMPONENT" -> HttpStatus.NOT_FOUND;
            case "INVALID_ENUM_VALUE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.OK;
        };
    }
}
