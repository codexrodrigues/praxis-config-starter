package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.contract.AiContractSpec;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.service.AiInteractionLogger;
import org.praxisplatform.config.service.AiOrchestratorService;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Endpoint canÃ´nico de orquestraÃ§Ã£o AI para geraÃ§Ã£o de patches metadata-driven.
 *
 * <p>
 * O controller expÃµe {@code POST /api/praxis/config/ai/patch} e faz a mediaÃ§Ã£o entre contrato
 * HTTP, validaÃ§Ã£o de metadados de contrato ({@code contractVersion}/{@code schemaHash}),
 * resoluÃ§Ã£o de contexto principal ({@code tenant/user/environment}) e delegaÃ§Ã£o para o
 * {@link AiOrchestratorService}.
 * </p>
 *
 * <p>
 * PrecedÃªncia documental importante:
 * </p>
 * <ul>
 *   <li>Valores de contrato no body tÃªm precedÃªncia sobre headers homÃ´nimos.</li>
 *   <li>Quando ausentes, versÃ£o e schema hash caem nos defaults do {@link AiContractSpec}.</li>
 *   <li>DivergÃªncia de contrato retorna {@code 409 Conflict} com payload explicativo e headers canÃ´nicos.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/praxis/config/ai")
@RequiredArgsConstructor
@Slf4j
public class AiOrchestratorController {

    static final String CONTRACT_VERSION_HEADER = "X-Praxis-Contract-Version";
    static final String CONTRACT_SCHEMA_HASH_HEADER = "X-Praxis-Schema-Hash";
    static final String RAG_RELEASE_ID_HEADER = "X-Praxis-Rag-Release-Id";
    static final String DEFAULT_CONTRACT_VERSION = AiContractSpec.CONTRACT_VERSION;
    static final String DEFAULT_CONTRACT_SCHEMA_HASH = AiContractSpec.CONTRACT_SCHEMA_HASH;
    static final String CODE_SCHEMA_HASH_MISMATCH = "SCHEMA_HASH_MISMATCH";
    static final String CODE_UNSUPPORTED_CONTRACT = "UNSUPPORTED_CONTRACT";

    private final AiOrchestratorService orchestratorService;
    private final AiInteractionLogger interactionLogger;
    private final AiPrincipalContextResolver principalContextResolver;

    @PostMapping("/patch")
    public ResponseEntity<AiOrchestratorResponse> generatePatch(
            @Valid @RequestBody AiOrchestratorRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = RAG_RELEASE_ID_HEADER, required = false) String ragReleaseIdHeader,
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
            request.setRagReleaseId(firstNonBlank(request.getRagReleaseId(), ragReleaseIdHeader, null));
            request.setStreamTransport(Boolean.FALSE);
            request.setStreamTurnPreclaimed(Boolean.FALSE);
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
            AiOrchestratorResponse response = orchestratorService.generatePatch(
                    request,
                    baseUrl,
                    principalContext.tenantId(),
                    principalContext.userId(),
                    principalContext.environment());
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
            case CODE_SCHEMA_HASH_MISMATCH, CODE_UNSUPPORTED_CONTRACT -> HttpStatus.CONFLICT;
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

    private ContractValidationError validateContractMetadata(String contractVersion, String schemaHash) {
        if (!DEFAULT_CONTRACT_VERSION.equals(contractVersion)) {
            return new ContractValidationError(
                    CODE_UNSUPPORTED_CONTRACT,
                    "Versao de contrato nao suportada.");
        }
        if (!DEFAULT_CONTRACT_SCHEMA_HASH.equals(schemaHash)) {
            return new ContractValidationError(
                    CODE_SCHEMA_HASH_MISMATCH,
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
                .contractVersion(DEFAULT_CONTRACT_VERSION)
                .schemaHash(DEFAULT_CONTRACT_SCHEMA_HASH)
                .path("$.contract")
                .allowedValues(List.of(DEFAULT_CONTRACT_VERSION))
                .providedValue(provided)
                .build();
        if (request != null) {
            response.setComponentId(request.getComponentId());
            response.setComponentType(request.getComponentType());
        }
        interactionLogger.logFrontendResponse(request, response);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(CONTRACT_VERSION_HEADER, DEFAULT_CONTRACT_VERSION)
                .header(CONTRACT_SCHEMA_HASH_HEADER, DEFAULT_CONTRACT_SCHEMA_HASH)
                .body(response);
    }

    private record ContractValidationError(String code, String message) {
    }
}

