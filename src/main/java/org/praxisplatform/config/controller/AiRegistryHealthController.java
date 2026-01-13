package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.registry.AiRegistryStatusReport;
import org.praxisplatform.config.registry.AiRegistryStatusService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/ai-registry")
@ConditionalOnProperty(prefix = "praxis.ai.registry.health", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AiRegistryHealthController {

    private final AiRegistryStatusService statusService;

    @GetMapping("/health")
    public ResponseEntity<AiRegistryStatusReport> health() {
        AiRegistryStatusReport report = statusService.getStatus();
        HttpStatus status = report.isReady() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(report);
    }
}
