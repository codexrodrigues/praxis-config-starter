package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiPatchStreamStartResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiStreamService;
import org.slf4j.MDC;
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

/**
 * Endpoints de streaming para geração incremental de patch AI.
 *
 * <p>
 * Esta superfície complementa o endpoint síncrono de patch com um fluxo em três etapas:
 * iniciar stream, conectar via SSE e cancelar/probar o stream. O controller preserva a mesma
 * validação de contrato da rota síncrona e delega o controle de ciclo de vida ao
 * {@link AiStreamService}.
 * </p>
 */
@RestController
@RequestMapping("/api/praxis/config/ai")
@RequiredArgsConstructor
@Slf4j
public class AiPatchStreamController {

    private final AiStreamService aiStreamService;
    private final AiPrincipalContextResolver principalContextResolver;
    private final AiStreamAccessTokenService streamAccessTokenService;

    @PostMapping("/patch/stream/start")
    public ResponseEntity<?> start(
            @Valid @RequestBody AiOrchestratorRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = AiOrchestratorController.RAG_RELEASE_ID_HEADER, required = false) String ragReleaseIdHeader,
            @RequestHeader(value = AiOrchestratorController.CONTRACT_VERSION_HEADER, required = false) String contractVersionHeader,
            @RequestHeader(value = AiOrchestratorController.CONTRACT_SCHEMA_HASH_HEADER, required = false) String schemaHashHeader) {
        return withRequestCorrelation(requestId, () -> {
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
            request.setRagReleaseId(firstNonBlank(request.getRagReleaseId(), ragReleaseIdHeader, null));

            AiPrincipalContext principalContext = principalContextResolver.resolve(
                    servletRequest,
                    tenantId,
                    userId,
                    environment);
            ContractValidationError contractValidationError = validateContractMetadata(
                    resolvedContractVersion,
                    resolvedSchemaHash);
            if (contractValidationError != null) {
                return buildContractMismatchResponse(
                        request,
                        resolvedContractVersion,
                        resolvedSchemaHash,
                        contractValidationError);
            }
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
        });
    }

    @GetMapping(path = "/patch/stream/{streamId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return withRequestCorrelation(requestId, () -> {
            AiPrincipalContext principalContext = resolveStreamPrincipalContext(
                    servletRequest,
                    tenantId,
                    userId,
                    environment,
                    accessToken);
            String resolvedLastEventId = firstNonBlank(lastEventIdHeader, lastEventIdParam, null);
            return aiStreamService.connect(streamId, resolvedLastEventId, accessToken, principalContext);
        });
    }

    @GetMapping("/patch/stream/{streamId}/probe")
    public ResponseEntity<Void> probe(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return withRequestCorrelation(requestId, () -> {
            AiPrincipalContext principalContext = resolveStreamPrincipalContext(
                    servletRequest,
                    tenantId,
                    userId,
                    environment,
                    accessToken);
            aiStreamService.probeStream(streamId, accessToken, principalContext);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/patch/stream/{streamId}/cancel")
    public ResponseEntity<AiPatchStreamCancelResponse> cancel(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return withRequestCorrelation(requestId, () -> {
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
        });
    }

    private ContractValidationError validateContractMetadata(String contractVersion, String schemaHash) {
        if (!AiOrchestratorController.DEFAULT_CONTRACT_VERSION.equals(contractVersion)) {
            return new ContractValidationError(
                    AiOrchestratorController.CODE_UNSUPPORTED_CONTRACT,
                    "Versao de contrato nao suportada.");
        }
        if (!AiOrchestratorController.DEFAULT_CONTRACT_SCHEMA_HASH.equals(schemaHash)) {
            return new ContractValidationError(
                    AiOrchestratorController.CODE_SCHEMA_HASH_MISMATCH,
                    "Schema hash divergente.");
        }
        return null;
    }

    private ResponseEntity<AiOrchestratorResponse> buildContractMismatchResponse(
            AiOrchestratorRequest request,
            String providedContractVersion,
            String providedSchemaHash,
            ContractValidationError error) {
        ObjectNode provided = JsonNodeFactory.instance.objectNode();
        if (providedContractVersion != null) {
            provided.put("contractVersion", providedContractVersion);
        }
        if (providedSchemaHash != null) {
            provided.put("schemaHash", providedSchemaHash);
        }
        AiOrchestratorResponse response = AiOrchestratorResponse.builder()
                .type("error")
                .code(error.code())
                .message(error.message())
                .contractVersion(AiOrchestratorController.DEFAULT_CONTRACT_VERSION)
                .schemaHash(AiOrchestratorController.DEFAULT_CONTRACT_SCHEMA_HASH)
                .path("$.contract")
                .allowedValues(List.of(AiOrchestratorController.DEFAULT_CONTRACT_VERSION))
                .providedValue(provided)
                .build();
        if (request != null) {
            response.setComponentId(request.getComponentId());
            response.setComponentType(request.getComponentType());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(AiOrchestratorController.CONTRACT_VERSION_HEADER, AiOrchestratorController.DEFAULT_CONTRACT_VERSION)
                .header(
                        AiOrchestratorController.CONTRACT_SCHEMA_HASH_HEADER,
                        AiOrchestratorController.DEFAULT_CONTRACT_SCHEMA_HASH)
                .body(response);
    }

    private record ContractValidationError(String code, String message) {
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

    private <T> T withRequestCorrelation(String requestId, Supplier<T> action) {
        String resolvedRequestId = requestId != null && !requestId.isBlank()
                ? requestId
                : UUID.randomUUID().toString();
        String previousRequestId = MDC.get("requestId");
        MDC.put("requestId", resolvedRequestId);
        try {
            return action.get();
        } finally {
            if (previousRequestId == null || previousRequestId.isBlank()) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousRequestId);
            }
        }
    }
}
