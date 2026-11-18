package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.praxisplatform.config.ai.agent.runtime.AgentProposalConfirmRequest;
import org.praxisplatform.config.ai.agent.runtime.AgentProposalConfirmResponse;
import org.praxisplatform.config.ai.agent.runtime.AgentProposalExternalCompletionRequest;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentProposalService;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamRequest;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamService;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
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
@RequestMapping("${praxis.ai.agent.base-path:/api/praxis/config/ai/agent}")
@ConditionalOnProperty(prefix = "praxis.ai.agent", name = "http-enabled", havingValue = "true", matchIfMissing = true)
public class OperationalAgentController {

    private final OperationalAgentTurnStreamService turnStreamService;
    private final AiPrincipalContextResolver principalContextResolver;
    private final AiStreamAccessTokenService streamAccessTokenService;
    private final OperationalAgentProposalService proposalService;

    public OperationalAgentController(
            OperationalAgentTurnStreamService turnStreamService,
            AiPrincipalContextResolver principalContextResolver,
            AiStreamAccessTokenService streamAccessTokenService) {
        this(turnStreamService, principalContextResolver, streamAccessTokenService, null);
    }

    @Autowired
    public OperationalAgentController(
            OperationalAgentTurnStreamService turnStreamService,
            AiPrincipalContextResolver principalContextResolver,
            AiStreamAccessTokenService streamAccessTokenService,
            @Nullable OperationalAgentProposalService proposalService) {
        this.turnStreamService = turnStreamService;
        this.principalContextResolver = principalContextResolver;
        this.streamAccessTokenService = streamAccessTokenService;
        this.proposalService = proposalService;
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

    @PostMapping("/proposals/{proposalId}/confirm")
    public ResponseEntity<AgentProposalConfirmResponse> confirmActionProposal(
            @PathVariable UUID proposalId,
            @RequestBody(required = false) AgentProposalConfirmRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        if (proposalService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Proposal execution service is unavailable.");
        }
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest, tenantId, userId, environment);
        OperationalAgentProposalService.ConfirmHttpResult result = proposalService.confirm(
                proposalId, principalContext, servletRequest, ifMatch, idempotencyKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus());
        if (result.response() != null && StringUtils.hasText(result.response().updatedVersionTag())) {
            builder.eTag(result.response().updatedVersionTag());
        }
        return builder.body(result.response());
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public ResponseEntity<AgentProposalConfirmResponse> rejectActionProposal(
            @PathVariable UUID proposalId,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        if (proposalService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Proposal execution service is unavailable.");
        }
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest, tenantId, userId, environment);
        OperationalAgentProposalService.ConfirmHttpResult result = proposalService.reject(proposalId, principalContext);
        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @PostMapping("/proposals/{proposalId}/complete-form")
    public ResponseEntity<AgentProposalConfirmResponse> completeActionProposalViaForm(
            @PathVariable UUID proposalId,
            @RequestBody(required = false) AgentProposalExternalCompletionRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        if (proposalService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Proposal execution service is unavailable.");
        }
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest, tenantId, userId, environment);
        OperationalAgentProposalService.ConfirmHttpResult result =
                proposalService.completeViaExternalForm(proposalId, principalContext, request);
        return ResponseEntity.status(result.httpStatus()).body(result.response());
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
