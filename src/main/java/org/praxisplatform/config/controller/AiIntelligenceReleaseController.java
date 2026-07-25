package org.praxisplatform.config.controller;

import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.AiIntelligenceRelease;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.service.AiIntelligenceReleaseService;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/praxis/config/ai-registry/releases")
@RequiredArgsConstructor
public class AiIntelligenceReleaseController {
    private final AiIntelligenceReleaseService service;
    @PostMapping public ResponseEntity<AiIntelligenceRelease> stage(@RequestHeader(value="X-Tenant-ID", required=false) String tenant,
            @RequestHeader(value="X-Env", required=false) String env, @Valid @RequestBody AiIntelligenceReleaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.stage(tenant, env, request));
    }
    @GetMapping("/{releaseId}") public AiIntelligenceRelease get(@RequestHeader(value="X-Tenant-ID", required=false) String tenant,
            @RequestHeader(value="X-Env", required=false) String env, @PathVariable String releaseId) { return service.get(tenant, env, releaseId); }
    @GetMapping("/{releaseId}/cleanup-plan")
    public RagVectorStoreService.SupersededReleaseCleanupPlan cleanupPlan(
            @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
            @RequestHeader(value="X-Env", required=false) String env,
            @PathVariable String releaseId) {
        return service.cleanupPlan(tenant, env, releaseId);
    }
    @PostMapping("/{releaseId}/activate") public AiIntelligenceRelease activate(@RequestHeader(value="X-Tenant-ID", required=false) String tenant,
            @RequestHeader(value="X-Env", required=false) String env, @PathVariable String releaseId) { return service.activate(tenant, env, releaseId); }
    @PostMapping("/{releaseId}/fail") public AiIntelligenceRelease fail(@RequestHeader(value="X-Tenant-ID", required=false) String tenant,
            @RequestHeader(value="X-Env", required=false) String env, @PathVariable String releaseId,
            @RequestBody(required=false) Map<String,String> body) { return service.fail(tenant, env, releaseId, body != null ? body.get("reason") : null); }
}
