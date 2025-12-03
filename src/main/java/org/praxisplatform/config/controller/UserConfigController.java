package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.dto.UpsertUserConfigRequest;
import org.praxisplatform.config.dto.UserConfigResponse;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/ui")
@RequiredArgsConstructor
@Slf4j
public class UserConfigController {

  private final UserConfigService service;
  private final ObjectMapper objectMapper;

  @GetMapping("/{componentType}/{componentId}")
  public ResponseEntity<UserConfigResponse> getConfig(
      @PathVariable String componentType,
      @PathVariable String componentId,
      @RequestHeader("X-Tenant-ID") String tenantId,
      @RequestHeader(value = "X-User-ID", required = false) String userId,
      @RequestHeader(value = "X-Env", required = false) String environment,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
      @RequestParam(value = "scope", required = false) String scopeParam) {

    UserConfigService.Scope scope = resolveScope(scopeParam, userId);

    Optional<UserConfigService.ResolvedConfig> resolved =
        scopeParam == null
            ? service.getResolved(tenantId, userId, componentType, componentId, environment)
            : service.getByScope(scope, tenantId, userId, componentType, componentId, environment);

    if (resolved.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    UiUserConfig cfg = resolved.get().config();
    String etag = cfg.getEtag() != null ? cfg.getEtag().toString() : null;

    if (etag != null && matches(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(formatEtag(etag)).build();
    }

    UserConfigResponse body =
        UserConfigResponse.builder()
            .componentType(componentType)
            .componentId(componentId)
            .environment(environment)
            .scope(resolved.get().scope().name().toLowerCase())
            .version(cfg.getVersion())
            .etag(etag)
            .payload(readJson(cfg.getPayload()))
            .tags(readJson(cfg.getTags()))
            .build();

    return ResponseEntity.ok().eTag(formatEtag(etag)).body(body);
  }

  @PutMapping("/{componentType}/{componentId}")
  public ResponseEntity<UserConfigResponse> upsertConfig(
      @PathVariable String componentType,
      @PathVariable String componentId,
      @RequestHeader("X-Tenant-ID") String tenantId,
      @RequestHeader(value = "X-User-ID", required = false) String userId,
      @RequestHeader(value = "X-Env", required = false) String environment,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(value = "X-Updated-By", required = false) String updatedBy,
      @RequestParam(value = "scope", required = false) String scopeParam,
      @Valid @RequestBody UpsertUserConfigRequest request) {

    UserConfigService.Scope scope = resolveScope(scopeParam, userId);

    UiUserConfig saved =
        service.upsert(
            scope,
            tenantId,
            userId,
            componentType,
            componentId,
            environment,
            request.getPayload(),
            request.getTags(),
            ifMatch,
            updatedBy);

    String etag = saved.getEtag() != null ? saved.getEtag().toString() : null;

    UserConfigResponse body =
        UserConfigResponse.builder()
            .componentType(componentType)
            .componentId(componentId)
            .environment(environment)
            .scope(scope.name().toLowerCase())
            .version(saved.getVersion())
            .etag(etag)
            .payload(readJson(saved.getPayload()))
            .tags(readJson(saved.getTags()))
            .build();

    return ResponseEntity.ok().eTag(formatEtag(etag)).body(body);
  }

  @DeleteMapping("/{componentType}/{componentId}")
  public ResponseEntity<Void> deleteConfig(
      @PathVariable String componentType,
      @PathVariable String componentId,
      @RequestHeader("X-Tenant-ID") String tenantId,
      @RequestHeader(value = "X-User-ID", required = false) String userId,
      @RequestHeader(value = "X-Env", required = false) String environment,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestParam(value = "scope", required = false) String scopeParam) {

    UserConfigService.Scope scope = resolveScope(scopeParam, userId);

    service.delete(scope, tenantId, userId, componentType, componentId, environment, ifMatch);
    return ResponseEntity.noContent().build();
  }

  private UserConfigService.Scope resolveScope(String scopeParam, String userId) {
    if (scopeParam == null || scopeParam.isBlank()) {
      // Auto: se houver userId, assume USER; senão TENANT
      return (userId != null && !userId.isBlank())
          ? UserConfigService.Scope.USER
          : UserConfigService.Scope.TENANT;
    }
    String normalized = scopeParam.trim().toLowerCase();
    return switch (normalized) {
      case "user" -> UserConfigService.Scope.USER;
      case "tenant" -> UserConfigService.Scope.TENANT;
      default -> throw new IllegalArgumentException("Invalid scope. Use user or tenant.");
    };
  }

  private boolean matches(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null || etag == null) return false;
    String clean = stripQuotes(ifNoneMatch);
    return clean.equals(etag);
  }

  private String stripQuotes(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }

  private String formatEtag(String etag) {
    return etag == null ? null : "\"" + etag + "\"";
  }

  private JsonNode readJson(String raw) {
    if (raw == null) return null;
    try {
      return objectMapper.readTree(raw);
    } catch (Exception e) {
      log.warn("Failed to parse stored JSON", e);
      return null;
    }
  }

  @ExceptionHandler(UserConfigService.PreconditionFailedException.class)
  public ResponseEntity<String> handlePrecondition(UserConfigService.PreconditionFailedException ex) {
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(ex.getMessage());
  }

  @ExceptionHandler(UserConfigService.PayloadTooLargeException.class)
  public ResponseEntity<String> handlePayloadTooLarge(UserConfigService.PayloadTooLargeException ex) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ex.getMessage());
  }

  @ExceptionHandler(UserConfigService.NotFoundException.class)
  public ResponseEntity<String> handleNotFound(UserConfigService.NotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(ex.getMessage());
  }
}
