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

    static final String CONTRACT_VERSION_HEADER = "X-Praxis-Contract-Version";
    static final String CONTRACT_SCHEMA_HASH_HEADER = "X-Praxis-Schema-Hash";
    static final String DEFAULT_CONTRACT_VERSION = "v1.1";
    static final String DEFAULT_CONTRACT_SCHEMA_HASH =
            "51b7901f1df633d89fc019a2e41f675cc5b87b135dfc8335aa96e53205034b26";

    private final AiOrchestratorService orchestratorService;
    private final AiInteractionLogger interactionLogger;

    @PostMapping("/patch")
    public ResponseEntity<AiOrchestratorResponse> generatePatch(
            @Valid @RequestBody AiOrchestratorRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = CONTRACT_VERSION_HEADER, required = false) String contractVersionHeader,
            @RequestHeader(value = CONTRACT_SCHEMA_HASH_HEADER, required = false) String schemaHashHeader) {
        String resolvedRequestId = requestId != null && !requestId.isBlank()
                ? requestId
                : UUID.randomUUID().toString();
        MDC.put("requestId", resolvedRequestId);
        try {
            String resolvedContractVersion = firstNonBlank(
                    request.getContractVersion(),
                    contractVersionHeader,
                    DEFAULT_CONTRACT_VERSION);
            String resolvedSchemaHash = firstNonBlank(
                    request.getSchemaHash(),
                    schemaHashHeader,
                    DEFAULT_CONTRACT_SCHEMA_HASH);
            request.setContractVersion(resolvedContractVersion);
            request.setSchemaHash(resolvedSchemaHash);

            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            AiOrchestratorResponse response = orchestratorService.generatePatch(
                    request,
                    baseUrl,
                    tenantId,
                    userId,
                    environment);
            if (response != null) {
                response.setContractVersion(resolvedContractVersion);
                response.setSchemaHash(resolvedSchemaHash);
            }
            interactionLogger.logFrontendResponse(request, response);
            return ResponseEntity.status(resolveStatus(response))
                    .header(CONTRACT_VERSION_HEADER, resolvedContractVersion)
                    .header(CONTRACT_SCHEMA_HASH_HEADER, resolvedSchemaHash)
                    .body(response);
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

    private String firstNonBlank(String bodyValue, String headerValue, String fallback) {
        if (bodyValue != null && !bodyValue.isBlank()) {
            return bodyValue.trim();
        }
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue.trim();
        }
        return fallback;
    }
}
