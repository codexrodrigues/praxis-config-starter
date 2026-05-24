package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiAssistantObservationFeedbackRequest;
import org.praxisplatform.config.dto.AiAssistantObservationFeedbackResponse;
import org.praxisplatform.config.dto.AiAssistantObservationResponse;
import org.praxisplatform.config.dto.AiAssistantObservationSummaryResponse;
import org.praxisplatform.config.service.AiAssistantObservationService;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/ai/triage")
@RequiredArgsConstructor
public class AiAssistantTriageController {

    private final AiAssistantObservationService observationService;
    private final AiPrincipalContextResolver principalContextResolver;

    @GetMapping("/observations")
    public ResponseEntity<List<AiAssistantObservationResponse>> observations(
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "surface", required = false) String surface,
            @RequestParam(value = "componentId", required = false) String componentId,
            @RequestParam(value = "componentType", required = false) String componentType,
            @RequestParam(value = "admissionOutcome", required = false) String admissionOutcome,
            @RequestParam(value = "terminalOutcome", required = false) String terminalOutcome,
            @RequestParam(value = "qualityOutcome", required = false) String qualityOutcome,
            @RequestParam(value = "promptHash", required = false) String promptHash,
            @RequestParam(value = "hasFeedback", required = false) Boolean hasFeedback,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest,
                tenantId,
                userId,
                environment);
        return ResponseEntity.ok(observationService.search(
                principalContext,
                from,
                to,
                surface,
                componentId,
                componentType,
                admissionOutcome,
                terminalOutcome,
                qualityOutcome,
                promptHash,
                hasFeedback,
                limit));
    }

    @GetMapping("/observations/{observationId}")
    public ResponseEntity<AiAssistantObservationResponse> observation(
            @PathVariable UUID observationId,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest,
                tenantId,
                userId,
                environment);
        return ResponseEntity.ok(observationService.get(observationId, principalContext));
    }

    @GetMapping("/summary")
    public ResponseEntity<AiAssistantObservationSummaryResponse> summary(
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest,
                tenantId,
                userId,
                environment);
        return ResponseEntity.ok(observationService.summarize(principalContext, from, to, limit));
    }

    @PostMapping("/observations/{observationId}/feedback")
    public ResponseEntity<AiAssistantObservationFeedbackResponse> feedback(
            @PathVariable UUID observationId,
            @RequestBody AiAssistantObservationFeedbackRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest,
                tenantId,
                userId,
                environment);
        return ResponseEntity.ok(observationService.attachFeedback(observationId, request, principalContext));
    }
}
