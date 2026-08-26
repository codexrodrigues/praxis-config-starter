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
 * Receives canonical API Catalog snapshots and exposes the lifecycle of their derived indexes.
 *
 * <p>{@code POST /ingest} validates and persists the supplied metadata before returning
 * {@code 202 Accepted}. The response does not mean that embeddings or RAG documents are ready:
 * callers must poll {@code GET /rag/status} for the same tenant, environment, service and release
 * until it reports {@code READY}, or handle the sanitized diagnostic reported by {@code FAILED}.
 * Rapid requests for the same scope are coalesced into the newest persisted generation.</p>
 *
 * <p>{@code POST /rag/reconcile} is also asynchronous. It requests an idempotent rebuild from the
 * canonical {@code api_metadata} rows and returns the newly pending lifecycle snapshot.</p>
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
