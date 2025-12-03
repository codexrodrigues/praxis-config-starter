package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.repository.UiUserConfigRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserConfigService {

  private static final int MAX_PAYLOAD_BYTES = 256 * 1024; // 256 KB safeguard

  private final UiUserConfigRepository repository;
  private final ObjectMapper objectMapper;

  public enum Scope {
    USER,
    TENANT
  }

  public record ResolvedConfig(UiUserConfig config, Scope scope) {}

  public Optional<ResolvedConfig> getResolved(
      String tenantId, String userId, String componentType, String componentId, String environment) {
    if (userId != null && !userId.isBlank()) {
      Optional<UiUserConfig> userConfig = findUserConfig(tenantId, userId, componentType, componentId, environment);
      if (userConfig.isPresent()) {
        return Optional.of(new ResolvedConfig(userConfig.get(), Scope.USER));
      }
    }

    Optional<UiUserConfig> tenantConfig =
        findTenantConfig(tenantId, componentType, componentId, environment);
    return tenantConfig.map(cfg -> new ResolvedConfig(cfg, Scope.TENANT));
  }

  public Optional<ResolvedConfig> getByScope(
      Scope scope, String tenantId, String userId, String componentType, String componentId, String environment) {
    return switch (scope) {
      case USER -> findUserConfig(tenantId, userId, componentType, componentId, environment)
          .map(cfg -> new ResolvedConfig(cfg, Scope.USER));
      case TENANT -> findTenantConfig(tenantId, componentType, componentId, environment)
          .map(cfg -> new ResolvedConfig(cfg, Scope.TENANT));
    };
  }

  public UiUserConfig upsert(
      Scope scope,
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment,
      JsonNode payload,
      JsonNode tags,
      String ifMatch,
      String updatedBy) {
    validatePayloadSize(payload);
    if (scope == Scope.USER && (userId == null || userId.isBlank())) {
      throw new IllegalArgumentException("User scope requires X-User-ID header");
    }
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new PreconditionFailedException("If-Match header is required");
    }

    String payloadJson = writeJson(payload);
    String tagsJson = tags != null ? writeJson(tags) : null;
    String effectiveUserId = scope == Scope.USER ? userId : null;

    Optional<UiUserConfig> existing =
        findConfig(tenantId, effectiveUserId, componentType, componentId, environment);

    if (existing.isEmpty()) {
      if (!"*".equals(ifMatch)) {
        throw new PreconditionFailedException("Resource does not exist; use If-Match: * to create");
      }
      UiUserConfig created =
          UiUserConfig.builder()
              .tenantId(tenantId)
              .userId(effectiveUserId)
              .componentType(componentType)
              .componentId(componentId)
              .environment(environment)
              .payload(payloadJson)
              .tags(tagsJson)
              .version(1L)
              .etag(UUID.randomUUID())
              .updatedBy(updatedBy)
              .build();
      return repository.save(created);
    }

    UiUserConfig current = existing.get();
    if (!etagMatches(current.getEtag(), ifMatch)) {
      throw new PreconditionFailedException("If-Match does not match current ETag");
    }

    current.setPayload(payloadJson);
    current.setTags(tagsJson);
    current.setVersion(current.getVersion() + 1);
    current.setEtag(UUID.randomUUID());
    current.setUpdatedBy(updatedBy);
    return repository.save(current);
  }

  public void delete(
      Scope scope,
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment,
      String ifMatch) {
    if (scope == Scope.USER && (userId == null || userId.isBlank())) {
      throw new IllegalArgumentException("User scope requires X-User-ID header");
    }
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new PreconditionFailedException("If-Match header is required");
    }

    String effectiveUserId = scope == Scope.USER ? userId : null;
    Optional<UiUserConfig> existing =
        findConfig(tenantId, effectiveUserId, componentType, componentId, environment);
    if (existing.isEmpty()) {
      throw new NotFoundException("Configuration not found for the requested scope");
    }
    UiUserConfig current = existing.get();
    if (!etagMatches(current.getEtag(), ifMatch)) {
      throw new PreconditionFailedException("If-Match does not match current ETag");
    }
    repository.delete(current);
  }

  private Optional<UiUserConfig> findConfig(
      String tenantId, String userId, String componentType, String componentId, String environment) {
    if (userId != null) {
      return findUserConfig(tenantId, userId, componentType, componentId, environment);
    }
    return findTenantConfig(tenantId, componentType, componentId, environment);
  }

  private Optional<UiUserConfig> findUserConfig(
      String tenantId, String userId, String componentType, String componentId, String environment) {
    if (environment == null) {
      return repository.findByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserId(
          tenantId, componentType, componentId, userId);
    }
    return repository.findByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserId(
        tenantId, componentType, componentId, environment, userId);
  }

  private Optional<UiUserConfig> findTenantConfig(
      String tenantId, String componentType, String componentId, String environment) {
    if (environment == null) {
      return repository
          .findByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNull(
              tenantId, componentType, componentId);
    }
    return repository.findByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdIsNull(
        tenantId, componentType, componentId, environment);
  }

  private void validatePayloadSize(JsonNode payload) {
    try {
      int size = objectMapper.writeValueAsBytes(payload).length;
      if (size > MAX_PAYLOAD_BYTES) {
        throw new PayloadTooLargeException("Payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
      }
    } catch (PayloadTooLargeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON payload", e);
    }
  }

  private String writeJson(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to serialize JSON", e);
    }
  }

  private boolean etagMatches(UUID current, String ifMatchHeader) {
    return current != null && current.toString().equals(stripQuotes(ifMatchHeader));
  }

  private String stripQuotes(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }

  public static class PreconditionFailedException extends RuntimeException {
    public PreconditionFailedException(String message) {
      super(message);
    }
  }

  public static class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) {
      super(message);
    }
  }

  public static class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
      super(message);
    }
  }
}
