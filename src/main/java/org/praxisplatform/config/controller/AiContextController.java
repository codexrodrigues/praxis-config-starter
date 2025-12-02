package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.UiConfiguration;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.service.UiConfigurationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/praxis/config/ai-context")
@RequiredArgsConstructor
public class AiContextController {

    private final UiConfigurationService service;

    @GetMapping("/{componentId}")
    public ResponseEntity<AiContextDTO> getAiContext(
            @PathVariable String componentId,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-App-ID") String appId,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {

        // 1. Get Resolved State (System + User Merge)
        JsonNode resolvedState = service.getResolvedConfig(tenantId, appId, componentId, userId);

        // 2. Get Base System Metadata (for Description/ResourcePath)
        // We assume System config holds the "Truth" about what the component is
        Optional<UiConfiguration> systemConfig = service.getSystemConfig(tenantId, appId, componentId);

        if (systemConfig.isEmpty()) {
            // If no system config, we might return 404 or just the resolved state (which might be empty)
            // For AI Context, it's better to have the metadata. Let's return what we have.
            return ResponseEntity.ok(AiContextDTO.builder()
                    .componentId(componentId)
                    .currentState(resolvedState)
                    .description("Dynamic Component")
                    .build());
        }

        UiConfiguration sys = systemConfig.get();

        AiContextDTO dto = AiContextDTO.builder()
                .componentId(componentId)
                .resourcePath(sys.getResourcePath())
                .description(sys.getAiDescription())
                .currentState(resolvedState)
                .build();

        return ResponseEntity.ok(dto);
    }
}
