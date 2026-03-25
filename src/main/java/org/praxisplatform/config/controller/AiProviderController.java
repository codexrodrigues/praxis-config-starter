package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiProviderCatalogResponse;
import org.praxisplatform.config.dto.AiProviderModelsRequest;
import org.praxisplatform.config.dto.AiProviderModelsResponse;
import org.praxisplatform.config.dto.AiProviderTestRequest;
import org.praxisplatform.config.dto.AiProviderTestResponse;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo operacional e utilitários de teste para provedores AI configuráveis.
 *
 * <p>
 * O controller expõe descoberta de provedores, listagem de modelos e validação de credenciais
 * sob demanda. Essas rotas são usadas por telas de administração e por fluxos de bootstrap do
 * registry para inspecionar capacidade real dos provedores ativos.
 * </p>
 */
@RestController
@RequestMapping("/api/praxis/config/ai/providers")
@RequiredArgsConstructor
public class AiProviderController {

    private final AiProviderManagementService managementService;

    @GetMapping("/catalog")
    public ResponseEntity<AiProviderCatalogResponse> listCatalog() {
        return ResponseEntity.ok(managementService.listCatalog());
    }

    @PostMapping("/models")
    public ResponseEntity<AiProviderModelsResponse> listModels(
            @RequestBody(required = false) AiProviderModelsRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(managementService.listModels(request, tenantId, userId, environment));
    }

    @PostMapping("/test")
    public ResponseEntity<AiProviderTestResponse> testConnection(
            @RequestBody(required = false) AiProviderTestRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(managementService.testConnection(request, tenantId, userId, environment));
    }
}
