package org.praxisplatform.config.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.ApiCatalogRequest;
import org.praxisplatform.config.dto.ApiMetadataRagReconcileResponse;
import org.praxisplatform.config.dto.ApiMetadataRagStatusResponse;
import org.praxisplatform.config.service.ApiMetadataIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de ingestÃ£o do catÃ¡logo de APIs publicado para busca e recuperaÃ§Ã£o contextual.
 *
 * <p>
 * Esta superfÃ­cie recebe snapshots do catÃ¡logo em {@code /ingest} e os delega ao
 * {@link ApiMetadataIngestionService}, preservando headers de tenant e ambiente quando fornecidos.
 * O retorno {@code 202 Accepted} indica ingestÃ£o assÃ­ncrona ou desacoplada do ciclo HTTP.
 * </p>
 */
@RestController
@RequestMapping("/api/praxis/config/api-catalog")
@RequiredArgsConstructor
public class ApiMetadataController {

    private final ApiMetadataIngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingestCatalog(
            @RequestBody @Valid ApiCatalogRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        ingestionService.ingestCatalog(request, tenantId, environment);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/rag/status")
    public ResponseEntity<ApiMetadataRagStatusResponse> ragStatus(
            @RequestParam(value = "serviceKey", required = false) String serviceKey,
            @RequestParam(value = "releaseId", required = false) String releaseId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(ingestionService.ragStatus(
                tenantId,
                environment,
                serviceKey,
                releaseId));
    }

    @PostMapping("/rag/reconcile")
    public ResponseEntity<ApiMetadataRagReconcileResponse> reconcileRag(
            @RequestParam(value = "serviceKey", required = false) String serviceKey,
            @RequestParam(value = "releaseId", required = false) String releaseId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ingestionService.reconcileRag(
                        tenantId,
                        environment,
                        serviceKey,
                        releaseId));
    }
}
