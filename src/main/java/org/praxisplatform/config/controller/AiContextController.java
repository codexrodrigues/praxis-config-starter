package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiContextRuntimeRequest;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiContextService;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Endpoint de composição de contexto AI para um componente específico.
 *
 * <p>
 * A rota permite montar um {@link AiContextDTO} tanto a partir do estado persistido do host
 * ({@code GET}) quanto de um estado transitório enviado pelo cliente ({@code POST}). Em ambos os
 * casos, o resultado é normalizado pelo {@link AiContextService} e pode incluir contexto de schema
 * quando o caller informar path/operação/tipo ou um {@link AiSchemaContext} completo.
 * </p>
 */
@RestController
@RequestMapping("/api/praxis/config/ai-context")
@RequiredArgsConstructor
public class AiContextController {

    private final AiContextService contextService;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;

    @GetMapping("/{componentId}")
    public ResponseEntity<AiContextDTO> getAiContext(
            @PathVariable String componentId,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestParam String componentType,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String aiMode,
            @RequestParam(required = false) Boolean requireSchema,
            @RequestParam(required = false) String resourcePath,
            @RequestParam(required = false) String schemaPath,
            @RequestParam(required = false) String schemaOperation,
            @RequestParam(required = false) String schemaType) {

        JsonNode resolvedState = objectMapper.createObjectNode();
        Optional<UserConfigService.ResolvedConfig> runtimeConfig =
                userConfigService.getResolved(tenantId, userId, componentType, componentId, environment);
        if (runtimeConfig.isPresent()) {
            resolvedState = contextService.parseJson(runtimeConfig.get().config().getPayload());
        }

        AiSchemaContext schemaContext = buildSchemaContext(schemaPath, schemaOperation, schemaType);
        return ResponseEntity.ok(contextService.buildContext(
                componentId,
                componentType,
                aiMode,
                requireSchema,
                resolvedState,
                resourcePath,
                schemaContext));
    }

    @PostMapping("/{componentId}")
    public ResponseEntity<AiContextDTO> postAiContext(
            @PathVariable String componentId,
            @RequestParam String componentType,
            @RequestParam(required = false) String aiMode,
            @RequestParam(required = false) Boolean requireSchema,
            @RequestParam(required = false) String resourcePath,
            @RequestParam(required = false) String schemaPath,
            @RequestParam(required = false) String schemaOperation,
            @RequestParam(required = false) String schemaType,
            @RequestBody(required = false) AiContextRuntimeRequest body) {

        JsonNode currentState = objectMapper.createObjectNode();
        if (body != null && body.getCurrentState() != null) {
            currentState = body.getCurrentState();
        }

        String resolvedResourcePath = resourcePath;
        if (body != null && body.getResourcePath() != null && !body.getResourcePath().isBlank()) {
            resolvedResourcePath = body.getResourcePath();
        }

        AiSchemaContext schemaContext = null;
        if (body != null && body.getSchemaContext() != null) {
            schemaContext = body.getSchemaContext();
        } else {
            schemaContext = buildSchemaContext(schemaPath, schemaOperation, schemaType);
        }

        return ResponseEntity.ok(contextService.buildContext(
                componentId,
                componentType,
                aiMode,
                requireSchema,
                currentState,
                resolvedResourcePath,
                schemaContext));
    }

    private AiSchemaContext buildSchemaContext(String path, String operation, String schemaType) {
        if ((path == null || path.isBlank())
                && (operation == null || operation.isBlank())
                && (schemaType == null || schemaType.isBlank())) {
            return null;
        }
        return AiSchemaContext.builder()
                .path(path)
                .operation(operation)
                .schemaType(schemaType)
                .build();
    }
}
