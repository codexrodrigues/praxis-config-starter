package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogIngestionResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainCatalogReleaseResponse;
import org.praxisplatform.config.repository.DomainCatalogItemRepository;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.service.DomainCatalogIngestionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestao e consulta inicial do catalogo semantico publicado por {@code /schemas/domain}.
 */
@RestController("configDomainCatalogController")
@RequestMapping("/api/praxis/config/domain-catalog")
@RequiredArgsConstructor
@ConditionalOnBean({DomainCatalogReleaseRepository.class, DomainCatalogItemRepository.class})
public class DomainCatalogController {

    private final DomainCatalogIngestionService domainCatalogIngestionService;

    @PostMapping("/ingest")
    public ResponseEntity<DomainCatalogIngestionResponse> ingest(
            @RequestBody JsonNode payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        DomainCatalogIngestionResponse response = domainCatalogIngestionService.ingest(payload, tenantId, environment);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/items")
    public ResponseEntity<List<DomainCatalogItemResponse>> items(
            @RequestParam String releaseKey,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String contextKey,
            @RequestParam(required = false) String nodeType,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(domainCatalogIngestionService.search(releaseKey, type, contextKey, nodeType, q, limit));
    }

    @GetMapping("/items/latest")
    public ResponseEntity<List<DomainCatalogItemResponse>> latestItems(
            @RequestParam(required = false) String serviceKey,
            @RequestParam(required = false) String resourceKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String contextKey,
            @RequestParam(required = false) String nodeType,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(domainCatalogIngestionService.searchLatest(
                serviceKey,
                resourceKey,
                tenantId,
                environment,
                type,
                contextKey,
                nodeType,
                q,
                limit));
    }

    @GetMapping("/context")
    public ResponseEntity<DomainCatalogContextResponse> context(
            @RequestParam(required = false) String serviceKey,
            @RequestParam(required = false) String resourceKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String contextKey,
            @RequestParam(required = false) String nodeType,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(domainCatalogIngestionService.contextLatest(
                serviceKey,
                resourceKey,
                tenantId,
                environment,
                type,
                contextKey,
                nodeType,
                q,
                limit));
    }

    @GetMapping("/relationships/latest")
    public ResponseEntity<List<DomainCatalogItemResponse>> latestRelationships(
            @RequestParam(required = false) String serviceKey,
            @RequestParam(required = false) String resourceKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) String sourceNodeKey,
            @RequestParam(required = false) String targetNodeKey,
            @RequestParam(required = false) String edgeType,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(domainCatalogIngestionService.relationshipsLatest(
                serviceKey,
                resourceKey,
                tenantId,
                environment,
                sourceNodeKey,
                targetNodeKey,
                edgeType,
                q,
                limit));
    }

    @GetMapping("/releases")
    public ResponseEntity<List<DomainCatalogReleaseResponse>> releases(
            @RequestParam(required = false) String serviceKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(domainCatalogIngestionService.releases(serviceKey, tenantId, environment, limit));
    }
}
