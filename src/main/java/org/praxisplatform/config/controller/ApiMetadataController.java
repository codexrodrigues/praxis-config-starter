package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.service.ApiMetadataIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/api-catalog")
@RequiredArgsConstructor
public class ApiMetadataController {

    private final ApiMetadataIngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingestCatalog(@RequestBody String catalogJson) {
        ingestionService.ingestCatalog(catalogJson);
        return ResponseEntity.accepted().build();
    }
}
