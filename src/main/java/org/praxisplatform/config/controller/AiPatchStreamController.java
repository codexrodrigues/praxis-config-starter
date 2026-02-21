package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiPatchStreamStartResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/praxis/config/ai")
@RequiredArgsConstructor
@Slf4j
public class AiPatchStreamController {

    private final AiStreamService aiStreamService;
    private final AiPrincipalContextResolver principalContextResolver;
    private final AiStreamAccessTokenService streamAccessTokenService;

    @PostMapping("/patch/stream/start")
    public ResponseEntity<AiPatchStreamStartResponse> start(
            @Valid @RequestBody AiOrchestratorRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = AiOrchestratorController.CONTRACT_VERSION_HEADER, required = false) String contractVersionHeader,
            @RequestHeader(value = AiOrchestratorController.CONTRACT_SCHEMA_HASH_HEADER, required = false) String schemaHashHeader) {
        String resolvedContractVersion = firstNonBlank(
                request.getContractVersion(),
                contractVersionHeader,
                AiOrchestratorController.DEFAULT_CONTRACT_VERSION);
        String resolvedSchemaHash = firstNonBlank(
                request.getSchemaHash(),
                schemaHashHeader,
                AiOrchestratorController.DEFAULT_CONTRACT_SCHEMA_HASH);
        request.setContractVersion(resolvedContractVersion);
        request.setSchemaHash(resolvedSchemaHash);

        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest,
                tenantId,
                userId,
                environment);
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        AiStreamService.StreamStartResult result = aiStreamService.startStream(
                request,
                baseUrl,
                principalContext);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .header(AiOrchestratorController.CONTRACT_VERSION_HEADER, resolvedContractVersion)
                .header(AiOrchestratorController.CONTRACT_SCHEMA_HASH_HEADER, resolvedSchemaHash)
                .body(result.response());
    }

    @GetMapping(path = "/patch/stream/{streamId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        AiPrincipalContext principalContext = resolveStreamPrincipalContext(
                servletRequest,
                tenantId,
                userId,
                environment,
                accessToken);
        String resolvedLastEventId = firstNonBlank(lastEventIdHeader, lastEventIdParam, null);
        return aiStreamService.connect(streamId, resolvedLastEventId, accessToken, principalContext);
    }

    @GetMapping("/patch/stream/{streamId}/probe")
    public ResponseEntity<Void> probe(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        AiPrincipalContext principalContext = resolveStreamPrincipalContext(
                servletRequest,
                tenantId,
                userId,
                environment,
                accessToken);
        aiStreamService.probeStream(streamId, accessToken, principalContext);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/patch/stream/{streamId}/cancel")
    public ResponseEntity<AiPatchStreamCancelResponse> cancel(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        AiPrincipalContext principalContext = resolveStreamPrincipalContext(
                servletRequest,
                tenantId,
                userId,
                environment,
                accessToken);
        try {
            AiPatchStreamCancelResponse response = aiStreamService.cancelStream(streamId, accessToken, principalContext);
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException ex) {
            if (HttpStatus.NOT_FOUND.equals(ex.getStatusCode())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(AiPatchStreamCancelResponse.builder()
                        .streamId(streamId)
                        .terminalState("not_found")
                        .message("Stream not found.")
                        .build());
            }
            throw ex;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim();
            if (normalized.isEmpty() || "null".equalsIgnoreCase(normalized)) {
                continue;
            }
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return null;
    }

    private AiPrincipalContext resolveStreamPrincipalContext(
            HttpServletRequest servletRequest,
            String tenantId,
            String userId,
            String environment,
            String accessToken) {
        try {
            return principalContextResolver.resolve(servletRequest, tenantId, userId, environment);
        } catch (ResponseStatusException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
            boolean identityStatus = HttpStatus.FORBIDDEN.equals(status) || HttpStatus.UNAUTHORIZED.equals(status);
            boolean signedMode = streamAccessTokenService.isSignedUrlTokenMode();
            boolean hasAccessToken = accessToken != null && !accessToken.isBlank();
            if (identityStatus && signedMode && hasAccessToken) {
                return null;
            }
            throw ex;
        }
    }
}
