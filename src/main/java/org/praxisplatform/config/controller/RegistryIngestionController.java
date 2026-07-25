package org.praxisplatform.config.controller;

import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.praxisplatform.config.service.AiIntelligenceReleaseService;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de ingestão do registry de definições de componentes.
 *
 * <p>
 * Recebe um snapshot estruturado de componentes, capacidades e schemas auxiliares e o entrega ao
 * {@link RegistryIngestionService}. O retorno {@code 202 Accepted} inclui o recibo reconciliado da
 * publicação, permitindo ao produtor comprovar a paridade entre os chunks esperados e publicados.
 * </p>
 */
@RestController
@RequestMapping("/api/praxis/config/ai-registry")
@RequiredArgsConstructor
public class RegistryIngestionController {

    private final RegistryIngestionService registryIngestionService;
    private final AiIntelligenceReleaseService releaseService;
    private final CanonicalJsonHashService hashService;
    private final ObjectMapper objectMapper;

    @PostMapping("/component-definitions")
    public ResponseEntity<RegistryIngestionService.RegistryReindexResult> ingestRegistry(
            @RequestBody JsonNode payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = "X-Praxis-Intelligence-Release", required = false) String releaseId) {
        RegistryIngestionRequest request = objectMapper.convertValue(payload, RegistryIngestionRequest.class);
        RegistryIngestionService.RegistryReindexResult result =
                registryIngestionService.ingestRegistry(request, tenantId, environment);
        if (releaseId != null && !releaseId.isBlank()) {
            releaseService.observeComponents(
                    tenantId, environment, releaseId, result.componentCount(),
                    hashService.sha256(payload), result.publishedChunkCount(), result.releaseId());
        }
        return ResponseEntity.accepted().body(result);
    }
}
