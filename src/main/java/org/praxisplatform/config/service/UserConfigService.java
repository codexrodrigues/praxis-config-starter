package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.repository.UiUserConfigRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * ServiÃ§o canÃ´nico de persistÃªncia e resoluÃ§Ã£o de configuraÃ§Ãµes de UI.
 *
 * <p>
 * Centraliza a semÃ¢ntica de escopo ({@code USER} vs {@code TENANT}), versionamento, geraÃ§Ã£o de
 * {@code ETag}, limites de payload e sanitizaÃ§Ã£o de segredos antes da escrita em
 * {@code ui_user_config}. Controllers pÃºblicos do starter nÃ£o devem reimplementar essas regras.
 * </p>
 */
@Service
public class UserConfigService {

  private static final int MAX_PAYLOAD_BYTES = 256 * 1024; // 256 KB safeguard
  private static final int MAX_COMPONENT_ID_LENGTH = 255;

  private final UiUserConfigRepository repository;
  private final ObjectMapper objectMapper;
  private final AiApiKeyProtectionService apiKeyProtectionService;
  private final NamedParameterJdbcTemplate jdbcTemplate;

  public UserConfigService(
      UiUserConfigRepository repository,
      ObjectMapper objectMapper,
      AiApiKeyProtectionService apiKeyProtectionService,
      @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.apiKeyProtectionService = apiKeyProtectionService;
    this.jdbcTemplate = jdbcTemplate;
  }

  public enum Scope {
    USER,
    TENANT
  }

  public record ResolvedConfig(UiUserConfig config, Scope scope) {}

  public Optional<ResolvedConfig> getResolved(
      String tenantId, String userId, String componentType, String componentId, String environment) {
    validateComponentType(componentType);
    validateComponentId(componentId);
    if (userId != null && !userId.isBlank()) {
      Optional<UiUserConfig> userConfig =
          findUserConfig(tenantId, userId, componentType, componentId, environment);
      if (userConfig.isPresent()) {
        return Optional.of(new ResolvedConfig(userConfig.get(), Scope.USER));
      }
    }

    Optional<UiUserConfig> tenantConfig =
        findTenantConfig(tenantId, componentType, componentId, environment);
    if (tenantConfig.isPresent()) {
      return Optional.of(new ResolvedConfig(tenantConfig.get(), Scope.TENANT));
    }
    return Optional.empty();
  }

  public Optional<ResolvedConfig> getByScope(
      Scope scope, String tenantId, String userId, String componentType, String componentId, String environment) {
    validateComponentType(componentType);
    validateComponentId(componentId);
    Optional<UiUserConfig> resolved =
        switch (scope) {
          case USER -> findUserConfig(tenantId, userId, componentType, componentId, environment);
          case TENANT -> findTenantConfig(tenantId, componentType, componentId, environment);
        };
    return resolved.map(cfg -> new ResolvedConfig(cfg, scope));
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
    validateComponentType(componentType);
    validateComponentId(componentId);
    if (scope == Scope.USER && (userId == null || userId.isBlank())) {
      throw new IllegalArgumentException("User scope requires X-User-ID header");
    }

    String effectiveUserId = scope == Scope.USER ? userId : null;

    Optional<UiUserConfig> existing =
        findConfig(tenantId, effectiveUserId, componentType, componentId, environment);
    validateIfMatch(existing, ifMatch);
    JsonNode existingPayload = existing.map(cfg -> readJson(cfg.getPayload())).orElse(null);
    JsonNode sanitizedPayload = apiKeyProtectionService.sanitizeForStorage(payload, existingPayload);
    validatePayloadSize(sanitizedPayload);

    String payloadJson = writeJson(sanitizedPayload);
    String tagsJson = tags != null ? writeJson(tags) : null;

    if (ifMatch == null || ifMatch.isBlank()) {
      return upsertWithoutPrecondition(
          tenantId,
          effectiveUserId,
          componentType,
          componentId,
          environment,
          payloadJson,
          tagsJson,
          updatedBy);
    }

    if (existing.isEmpty()) {
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
      try {
        return repository.saveAndFlush(created);
      } catch (DataIntegrityViolationException ex) {
        return recoverConcurrentCreate(
            tenantId,
            effectiveUserId,
            componentType,
            componentId,
            environment,
            payloadJson,
            tagsJson,
            updatedBy,
            ex);
      }
    }

    return updateExisting(existing.get(), payloadJson, tagsJson, updatedBy);
  }

  private UiUserConfig upsertWithoutPrecondition(
      String tenantId,
      String effectiveUserId,
      String componentType,
      String componentId,
      String environment,
      String payloadJson,
      String tagsJson,
      String updatedBy) {
    String conflictTarget = conflictTarget(effectiveUserId, environment);
    UUID insertEtag = UUID.randomUUID();
    UUID updateEtag = UUID.randomUUID();
    UUID id = UUID.randomUUID();

    String sql =
        """
        INSERT INTO ui_user_config (
          id, tenant_id, user_id, component_type, component_id, environment,
          payload, tags, version, etag, created_at, updated_at, updated_by
        )
        VALUES (
          CAST(:id AS uuid), :tenantId, :userId, :componentType, :componentId, :environment,
          CAST(:payload AS jsonb), CAST(:tags AS jsonb), 1, CAST(:insertEtag AS uuid),
          now(), now(), :updatedBy
        )
        ON CONFLICT %s DO UPDATE SET
          payload = EXCLUDED.payload,
          tags = EXCLUDED.tags,
          version = ui_user_config.version + 1,
          etag = CAST(:updateEtag AS uuid),
          updated_at = now(),
          updated_by = EXCLUDED.updated_by
        RETURNING
          id, tenant_id, user_id, component_type, component_id, environment,
          payload::text AS payload, tags::text AS tags, version, etag, created_at, updated_at, updated_by
        """
            .formatted(conflictTarget);

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("tenantId", tenantId)
            .addValue("userId", effectiveUserId)
            .addValue("componentType", componentType)
            .addValue("componentId", componentId)
            .addValue("environment", environment)
            .addValue("payload", payloadJson)
            .addValue("tags", tagsJson)
            .addValue("insertEtag", insertEtag.toString())
            .addValue("updateEtag", updateEtag.toString())
            .addValue("updatedBy", updatedBy);

    return jdbcTemplate.queryForObject(sql, params, this::mapUiUserConfig);
  }

  private String conflictTarget(String effectiveUserId, String environment) {
    if (effectiveUserId == null && environment == null) {
      return "(tenant_id, component_type, component_id) WHERE environment IS NULL AND user_id IS NULL";
    }
    if (effectiveUserId == null) {
      return "(tenant_id, component_type, component_id, environment) WHERE environment IS NOT NULL AND user_id IS NULL";
    }
    if (environment == null) {
      return "(tenant_id, component_type, component_id, user_id) WHERE environment IS NULL AND user_id IS NOT NULL";
    }
    return "(tenant_id, user_id, component_type, component_id, environment)";
  }

  private UiUserConfig mapUiUserConfig(ResultSet rs, int rowNum) throws SQLException {
    return UiUserConfig.builder()
        .id(rs.getObject("id", UUID.class))
        .tenantId(rs.getString("tenant_id"))
        .userId(rs.getString("user_id"))
        .componentType(rs.getString("component_type"))
        .componentId(rs.getString("component_id"))
        .environment(rs.getString("environment"))
        .payload(rs.getString("payload"))
        .tags(rs.getString("tags"))
        .version(rs.getLong("version"))
        .etag(rs.getObject("etag", UUID.class))
        .createdAt(toInstant(rs, "created_at"))
        .updatedAt(toInstant(rs, "updated_at"))
        .updatedBy(rs.getString("updated_by"))
        .build();
  }

  private Instant toInstant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column) != null ? rs.getTimestamp(column).toInstant() : null;
  }

  private UiUserConfig recoverConcurrentCreate(
      String tenantId,
      String effectiveUserId,
      String componentType,
      String componentId,
      String environment,
      String payloadJson,
      String tagsJson,
      String updatedBy,
      DataIntegrityViolationException cause) {
    UiUserConfig current =
        findConfig(tenantId, effectiveUserId, componentType, componentId, environment)
            .orElseThrow(() -> cause);
    return updateExisting(current, payloadJson, tagsJson, updatedBy);
  }

  private UiUserConfig updateExisting(
      UiUserConfig current, String payloadJson, String tagsJson, String updatedBy) {
    current.setPayload(payloadJson);
    current.setTags(tagsJson);
    current.setVersion(current.getVersion() + 1);
    current.setEtag(UUID.randomUUID());
    current.setUpdatedBy(updatedBy);
    return repository.saveAndFlush(current);
  }

  public void delete(
      Scope scope,
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment,
      String ifMatch) {
    validateComponentType(componentType);
    validateComponentId(componentId);
    if (scope == Scope.USER && (userId == null || userId.isBlank())) {
      throw new IllegalArgumentException("User scope requires X-User-ID header");
    }

    String effectiveUserId = scope == Scope.USER ? userId : null;
    Optional<UiUserConfig> existing =
        findConfig(tenantId, effectiveUserId, componentType, componentId, environment);
    validateIfMatch(existing, ifMatch);
    if (existing.isEmpty()) {
      throw new NotFoundException("Configuration not found for the requested scope");
    }
    repository.delete(existing.get());
  }

  private void validateIfMatch(Optional<UiUserConfig> existing, String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) {
      return;
    }

    if (existing.isEmpty()) {
      throw new PreconditionFailedException(
          "If-Match precondition failed: configuration not found");
    }

    String expected = ifMatch.trim();
    if ("*".equals(expected)) {
      return;
    }

    String current = String.valueOf(existing.get().getEtag());
    if (!current.equals(expected)) {
      throw new PreconditionFailedException(
          "If-Match precondition failed: stale configuration version");
    }
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
      return repository
          .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
              tenantId, componentType, componentId, userId);
    }
    return repository
        .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdOrderByUpdatedAtDesc(
            tenantId, componentType, componentId, environment, userId);
  }

  private Optional<UiUserConfig> findTenantConfig(
      String tenantId, String componentType, String componentId, String environment) {
    if (environment == null) {
      return repository
          .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
              tenantId, componentType, componentId);
    }
    return repository
        .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdIsNullOrderByUpdatedAtDesc(
            tenantId, componentType, componentId, environment);
  }

  private void validateComponentType(String componentType) {
    if (componentType == null || componentType.isBlank()) {
      throw new IllegalArgumentException("componentType is required");
    }
  }

  private void validateComponentId(String componentId) {
    if (componentId == null || componentId.isBlank()) {
      throw new IllegalArgumentException("componentId is required");
    }
    if (componentId.length() > MAX_COMPONENT_ID_LENGTH) {
      throw new IllegalArgumentException(
          "componentId exceeds max length of " + MAX_COMPONENT_ID_LENGTH + " characters");
    }
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

  private JsonNode readJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (Exception e) {
      return objectMapper.createObjectNode();
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

  public static class PreconditionFailedException extends RuntimeException {
    public PreconditionFailedException(String message) {
      super(message);
    }
  }
}
