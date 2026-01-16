package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiProviderStatusResponse;
import org.praxisplatform.config.service.AiProviderStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/ai")
@RequiredArgsConstructor
public class AiProviderStatusController {

    private final AiProviderStatusService statusService;

    @GetMapping("/status")
    public ResponseEntity<AiProviderStatusResponse> status(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(statusService.getStatus(tenantId, userId, environment));
    }
}
