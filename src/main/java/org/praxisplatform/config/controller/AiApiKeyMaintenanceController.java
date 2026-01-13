package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiApiKeyClearRequest;
import org.praxisplatform.config.dto.AiApiKeyMaintenanceResponse;
import org.praxisplatform.config.dto.AiApiKeyRotateRequest;
import org.praxisplatform.config.service.AiApiKeyAccessGuard;
import org.praxisplatform.config.service.AiApiKeyMaintenanceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/ai/keys")
@RequiredArgsConstructor
public class AiApiKeyMaintenanceController {

    private final AiApiKeyMaintenanceService maintenanceService;
    private final AiApiKeyAccessGuard accessGuard;

    @PostMapping("/clear")
    public ResponseEntity<AiApiKeyMaintenanceResponse> clearApiKey(
            @RequestBody AiApiKeyClearRequest request,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Updated-By", required = false) String updatedBy,
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AiApiKeyAccessGuard.GuardResult auth = accessGuard.authorize(adminToken, authorization);
        if (!auth.allowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AiApiKeyMaintenanceResponse.builder()
                            .success(false)
                            .status("forbidden")
                            .message(auth.message())
                            .build());
        }
        return ResponseEntity.ok(maintenanceService.clearApiKey(request, tenantId, userId, updatedBy));
    }

    @PostMapping("/rotate")
    public ResponseEntity<AiApiKeyMaintenanceResponse> rotateApiKey(
            @RequestBody AiApiKeyRotateRequest request,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Updated-By", required = false) String updatedBy,
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AiApiKeyAccessGuard.GuardResult auth = accessGuard.authorize(adminToken, authorization);
        if (!auth.allowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AiApiKeyMaintenanceResponse.builder()
                            .success(false)
                            .status("forbidden")
                            .message(auth.message())
                            .build());
        }
        return ResponseEntity.ok(maintenanceService.rotateApiKey(request, tenantId, userId, updatedBy));
    }
}
