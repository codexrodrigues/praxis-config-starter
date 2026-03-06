package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.dto.ComponentSearchResult;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/praxis/config")
@RequiredArgsConstructor
public class ContextRetrievalController {

    private final ContextRetrievalService retrievalService;

    @GetMapping("/api-catalog/search")
    public ResponseEntity<List<ApiSearchResult>> searchApiCatalog(
            @RequestParam String query,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String releaseId,
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        
        List<ApiSearchResult> results =
                retrievalService.searchApiMetadata(query, method, tags, limit, null, tenantId, environment, releaseId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/ai-registry/component-definitions/search")
    public ResponseEntity<List<ComponentSearchResult>> searchComponentDefinitions(
            @RequestParam String query,
            @RequestParam(required = false) String releaseId,
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {

        List<ComponentSearchResult> results =
                retrievalService.searchComponentDefinitions(query, limit, null, tenantId, environment, releaseId);
        return ResponseEntity.ok(results);
    }
}
