package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.dto.ComponentSearchResult;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
            @RequestParam(defaultValue = "5") int limit) {
        
        List<ApiSearchResult> results = retrievalService.searchApiMetadata(query, method, tags, limit);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/ai-context/search")
    public ResponseEntity<List<ComponentSearchResult>> searchAiContext(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit) {

        List<ComponentSearchResult> results = retrievalService.searchComponentDefinitions(query, limit);
        return ResponseEntity.ok(results);
    }
}
