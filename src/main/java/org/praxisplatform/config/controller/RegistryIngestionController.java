package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/ai-context")
@RequiredArgsConstructor
public class RegistryIngestionController {

    private final RegistryIngestionService registryIngestionService;

    @PostMapping("/ingest-registry")
    public ResponseEntity<Void> ingestRegistry(@RequestBody String registryJson) {
        registryIngestionService.ingestRegistry(registryJson);
        return ResponseEntity.accepted().build();
    }
}
