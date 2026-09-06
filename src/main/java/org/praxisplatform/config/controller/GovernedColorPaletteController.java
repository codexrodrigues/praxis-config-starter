package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.GovernedColorPaletteCapabilitiesResponse;
import org.praxisplatform.config.dto.GovernedColorPaletteCapabilitiesResponse.Operation;
import org.praxisplatform.config.dto.GovernedColorPalettePreviewRequest;
import org.praxisplatform.config.dto.GovernedColorPalettePreviewResponse;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.GovernedColorPaletteProjectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runtime catalog plus non-persisting authoring preview for governed palette decisions. */
@RestController
@RequestMapping("/api/praxis/config/color-palettes")
@RequiredArgsConstructor
@ConditionalOnBean(GovernedColorPaletteProjectionService.class)
public class GovernedColorPaletteController {

    private final GovernedColorPaletteProjectionService service;
    private final DomainRuleGovernancePrincipalResolver principalResolver;

    @PostMapping("/previews")
    public ResponseEntity<GovernedColorPalettePreviewResponse> preview(
            @RequestBody GovernedColorPalettePreviewRequest previewRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest request) {
        principalResolver.resolve(request, tenantId, environment, "RULE_DEFINITION_AUTHOR");
        if (previewRequest == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.preview(previewRequest.paletteKey(), previewRequest.palette()));
    }

    @GetMapping
    public ResponseEntity<List<GovernedColorPaletteResponse>> list(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(value = "familyKey", required = false) String familyKey,
            HttpServletRequest request) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                request, tenantId, environment, "RULE_DEFINITION_READER");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                .body(service.list(principal.tenantId(), principal.environment(), familyKey));
    }

    @GetMapping("/{paletteKey}")
    public ResponseEntity<GovernedColorPaletteResponse> get(
            @PathVariable String paletteKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestParam(value = "version", required = false) Integer version,
            HttpServletRequest request) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                request, tenantId, environment, "RULE_DEFINITION_READER");
        GovernedColorPaletteResponse palette = service.get(
                principal.tenantId(), principal.environment(), paletteKey, version);
        if (matches(ifNoneMatch, palette.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                    .eTag(quote(palette.etag()))
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                .eTag(quote(palette.etag()))
                .body(palette);
    }

    @GetMapping("/capabilities")
    public ResponseEntity<GovernedColorPaletteCapabilitiesResponse> capabilities(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest request) {
        principalResolver.resolve(request, tenantId, environment, "RULE_DEFINITION_READER");
        List<Operation> operations = List.of(
                new Operation("palette.read", "RULE_DEFINITION_READER", true),
                new Operation(
                        "palette.preview",
                        "RULE_DEFINITION_AUTHOR",
                        request.isUserInRole("RULE_DEFINITION_AUTHOR")),
                new Operation(
                        "palette.author",
                        "RULE_DEFINITION_AUTHOR",
                        request.isUserInRole("RULE_DEFINITION_AUTHOR")),
                new Operation(
                        "palette.approve",
                        "RULE_DEFINITION_APPROVER",
                        request.isUserInRole("RULE_DEFINITION_APPROVER")),
                new Operation(
                        "palette.publish",
                        "RULE_SNAPSHOT_PUBLISHER",
                        request.isUserInRole("RULE_SNAPSHOT_PUBLISHER")));
        return ResponseEntity.ok(new GovernedColorPaletteCapabilitiesResponse(
                GovernedColorPaletteProjectionService.RULE_TYPE,
                GovernedColorPaletteProjectionService.TARGET_LAYER,
                GovernedColorPaletteProjectionService.TARGET_ARTIFACT_TYPE,
                operations,
                List.copyOf(service.supportedPurposes())));
    }

    private String quote(String etag) {
        return "\"" + etag.replace("\"", "") + "\"";
    }

    private boolean matches(String condition, String etag) {
        if (condition == null || etag == null) {
            return false;
        }
        String normalized = etag.replace("\"", "");
        for (String candidate : condition.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value) || normalized.equals(value.replace("W/", "").replace("\"", ""))) {
                return true;
            }
        }
        return false;
    }
}
