package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamRequest;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamService;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Endpoints canonicos SSE do assistente operacional sob {@code /api/praxis/config/ai/agent}.
 */
@RestController
@RequestMapping("/api/praxis/config/ai/agent")
@ConditionalOnProperty(prefix = "praxis.ai.agent", name = "http-enabled", havingValue = "true", matchIfMissing = true)
public class OperationalAgentController {

    private final OperationalAgentTurnStreamService turnStreamService;
    private final AiPrincipalContextResolver principalContextResolver;
    private final AiStreamAccessTokenService streamAccessTokenService;

    public OperationalAgentController(
            OperationalAgentTurnStreamService turnStreamService,
            AiPrincipalContextResolver principalContextResolver,
            AiStreamAccessTokenService streamAccessTokenService) {
        this.turnStreamService = turnStreamService;
        this.principalContextResolver = principalContextResolver;
        this.streamAccessTokenService = streamAccessTokenService;
    }

    @PostMapping("/turn/stream/start")
    public ResponseEntity<AgenticAuthoringTurnStreamStartResponse> startTurnStream(
            @RequestBody OperationalAgentTurnStreamRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest, tenantId, userId, environment);
        String baseUrl = currentContextBaseUrl();
        OperationalAgentTurnStreamService.StartResult result = turnStreamService.start(
                request, servletRequest, baseUrl, principalContext);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }

    @GetMapping(path = "/turn/stream/{streamId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connectTurnStream(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        AiPrincipalContext principalContext = resolveStreamPrincipalContext(
                streamId, servletRequest, tenantId, userId, environment, accessToken);
        String resolvedLastEventId = StringUtils.hasText(lastEventIdHeader) ? lastEventIdHeader : lastEventIdParam;
        return turnStreamService.connect(streamId, resolvedLastEventId, principalContext);
    }

    @GetMapping("/turn/stream/{streamId}/probe")
    public ResponseEntity<Void> probeTurnStream(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        turnStreamService.probe(streamId, resolveStreamPrincipalContext(
                streamId, servletRequest, tenantId, userId, environment, accessToken));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/turn/stream/{streamId}/cancel")
    public ResponseEntity<AiPatchStreamCancelResponse> cancelTurnStream(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(turnStreamService.cancel(streamId, resolveStreamPrincipalContext(
                streamId, servletRequest, tenantId, userId, environment, accessToken)));
    }

    private AiPrincipalContext resolveStreamPrincipalContext(
            UUID streamId,
            HttpServletRequest servletRequest,
            String tenantId,
            String userId,
            String environment,
            String accessToken) {
        AiPrincipalContext principalContext = null;
        try {
            principalContext = principalContextResolver.resolve(servletRequest, tenantId, userId, environment);
        } catch (ResponseStatusException ex) {
            boolean identityStatus = HttpStatus.FORBIDDEN.equals(ex.getStatusCode())
                    || HttpStatus.UNAUTHORIZED.equals(ex.getStatusCode());
            if (!(identityStatus && streamAccessTokenService.isSignedUrlTokenMode() && StringUtils.hasText(accessToken))) {
                throw ex;
            }
        }
        return streamAccessTokenService.resolvePrincipalContext(streamId, accessToken, principalContext);
    }

    private static String currentContextBaseUrl() {
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        } catch (IllegalStateException ex) {
            return "";
        }
    }
}
