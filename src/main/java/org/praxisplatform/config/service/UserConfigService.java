package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.repository.UiUserConfigRepository;
import org.springframework.beans.factory.ObjectProvider;
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
  private static final int MAX_SCOPE_ID_LENGTH = 255;
  private static final int MAX_COMPONENT_TYPE_LENGTH = 64;
  private static final int MAX_COMPONENT_ID_LENGTH = 255;
  private static final int MAX_ENVIRONMENT_LENGTH = 64;

  private final UiUserConfigRepository repository;
  private final ObjectMapper objectMapper;
  private final AiApiKeyProtectionService apiKeyProtectionService;
  private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;

  public UserConfigService(
      UiUserConfigRepository repository,
      ObjectMapper objectMapper,
      AiApiKeyProtectionService apiKeyProtectionService,
      @Qualifier("configNamedParameterJdbcTemplate") ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.apiKeyProtectionService = apiKeyProtectionService;
    this.jdbcTemplateProvider = jdbcTemplateProvider;
  }

  public enum Scope {
    USER,
    TENANT
  }

  public record ResolvedConfig(UiUserConfig config, Scope scope) {}

  public Optional<ResolvedConfig> getResolved(
      String tenantId, String userId, String componentType, String componentId, String environment) {
    ConfigIdentity identity = normalizeIdentity(tenantId, userId, componentType, componentId, environment);
    if (identity.userId() != null) {
      Optional<UiUserConfig> userConfig =
          findUserConfig(identity);
      if (userConfig.isPresent()) {
        return Optional.of(new ResolvedConfig(userConfig.get(), Scope.USER));
      }
    }

    Optional<UiUserConfig> tenantConfig =
        findTenantConfig(identity);
    if (tenantConfig.isPresent()) {
      return Optional.of(new ResolvedConfig(tenantConfig.get(), Scope.TENANT));
    }
    return Optional.empty();
  }

  public Optional<ResolvedConfig> getByScope(
      Scope scope, String tenantId, String userId, String componentType, String componentId, String environment) {
    ConfigIdentity identity = normalizeIdentity(tenantId, userId, componentType, componentId, environment);
    if (scope == Scope.USER && identity.userId() == null) {
      throw new IllegalArgumentException("User scope requires X-User-ID header");
    }
    Optional<UiUserConfig> resolved =
        switch (scope) {
          case USER -> findUserConfig(identity);
          case TENANT -> findTenantConfig(identity);
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
    ConfigIdentity identity = normalizeIdentity(tenantId, userId, componentType, componentId, environment);
    String normalizedUpdatedBy = optionalIdentityValue(updatedBy, "updatedBy", MAX_SCOPE_ID_LENGTH);
    if (scope == Scope.USER && identity.userId() == null) {
      throw new IllegalArgumentException("User scope requires X-User-ID header");
    }

    ConfigIdentity effectiveIdentity = scope == Scope.USER ? identity : identity.withUserId(null);

    Optional<UiUserConfig> existing =
        findConfig(effectiveIdentity);
    validateIfMatch(existing, ifMatch);
    JsonNode existingPayload = existing.map(cfg -> readJson(cfg.getPayload())).orElse(null);
    JsonNode sanitizedPayload = apiKeyProtectionService.sanitizeForStorage(payload, existingPayload);
    validatePayloadSize(sanitizedPayload);

    String payloadJson = writeJson(sanitizedPayload);
    String tagsJson = tags != null ? writeJson(tags) : null;

    if (ifMatch == null || ifMatch.isBlank()) {
      return upsertWithoutPrecondition(
          effectiveIdentity,
          payloadJson,
          tagsJson,
          normalizedUpdatedBy);
    }

    if (existing.isEmpty()) {
      UiUserConfig created =
          UiUserConfig.builder()
              .tenantId(effectiveIdentity.tenantId())
              .userId(effectiveIdentity.userId())
              .componentType(effectiveIdentity.componentType())
              .componentId(effectiveIdentity.componentId())
              .environment(effectiveIdentity.environment())
              .payload(payloadJson)
              .tags(tagsJson)
              .version(1L)
              .etag(UUID.randomUUID())
              .updatedBy(normalizedUpdatedBy)
              .build();
      try {
        return repository.saveAndFlush(created);
      } catch (DataIntegrityViolationException ex) {
        return recoverConcurrentCreate(
            effectiveIdentity,
            payloadJson,
            tagsJson,
            normalizedUpdatedBy,
            ex);
      }
    }

    return updateExisting(existing.get(), payloadJson, tagsJson, normalizedUpdatedBy);
  }

  private UiUserConfig upsertWithoutPrecondition(
      ConfigIdentity identity,
      String payloadJson,
      String tagsJson,
      String updatedBy) {
    String conflictTarget = conflictTarget(identity.userId(), identity.environment());
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
            .addValue("tenantId", identity.tenantId())
            .addValue("userId", identity.userId())
            .addValue("componentType", identity.componentType())
            .addValue("componentId", identity.componentId())
            .addValue("environment", identity.environment())
            .addValue("payload", payloadJson)
            .addValue("tags", tagsJson)
            .addValue("insertEtag", insertEtag.toString())
            .addValue("updateEtag", updateEtag.toString())
            .addValue("updatedBy", updatedBy);

    return jdbcTemplate().queryForObject(sql, params, this::mapUiUserConfig);
  }

  private NamedParameterJdbcTemplate jdbcTemplate() {
    NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    if (jdbcTemplate == null) {
      throw new IllegalStateException("configNamedParameterJdbcTemplate is required for ui_user_config writes.");
    }
    return jdbcTemplate;
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
      ConfigIdentity identity,
      String payloadJson,
      String tagsJson,
      String updatedBy,
      DataIntegrityViolationException cause) {
    UiUserConfig current =
        findConfig(identity)
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
    ConfigIdentity identity = normalizeIdentity(tenantId, userId, componentType, componentId, environment);
    if (scope == Scope.USER && identity.userId() == null) {
      throw new IllegalArgumentException("User scope requires X-User-ID header");
    }

    ConfigIdentity effectiveIdentity = scope == Scope.USER ? identity : identity.withUserId(null);
    Optional<UiUserConfig> existing =
        findConfig(effectiveIdentity);
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

    HttpEntityTagCondition condition = HttpEntityTagCondition.parse(ifMatch);
    String current = String.valueOf(existing.get().getEtag());
    if (!condition.matchesStrong(current)) {
      throw new PreconditionFailedException(
          "If-Match precondition failed: stale configuration version");
    }
  }

  private Optional<UiUserConfig> findConfig(ConfigIdentity identity) {
    if (identity.userId() != null) {
      return findUserConfig(identity);
    }
    return findTenantConfig(identity);
  }

  private Optional<UiUserConfig> findUserConfig(ConfigIdentity identity) {
    if (identity.environment() == null) {
      return repository
          .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
              identity.tenantId(), identity.componentType(), identity.componentId(), identity.userId());
    }
    return repository
        .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdOrderByUpdatedAtDesc(
            identity.tenantId(), identity.componentType(), identity.componentId(), identity.environment(), identity.userId());
  }

  private Optional<UiUserConfig> findTenantConfig(ConfigIdentity identity) {
    if (identity.environment() == null) {
      return repository
          .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
              identity.tenantId(), identity.componentType(), identity.componentId());
    }
    return repository
        .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdIsNullOrderByUpdatedAtDesc(
            identity.tenantId(), identity.componentType(), identity.componentId(), identity.environment());
  }

  private ConfigIdentity normalizeIdentity(
      String tenantId, String userId, String componentType, String componentId, String environment) {
    return new ConfigIdentity(
        requiredIdentityValue(tenantId, "tenantId", MAX_SCOPE_ID_LENGTH),
        optionalIdentityValue(userId, "userId", MAX_SCOPE_ID_LENGTH),
        requiredIdentityValue(componentType, "componentType", MAX_COMPONENT_TYPE_LENGTH),
        requiredIdentityValue(componentId, "componentId", MAX_COMPONENT_ID_LENGTH),
        optionalIdentityValue(environment, "environment", MAX_ENVIRONMENT_LENGTH));
  }

  private String requiredIdentityValue(String value, String fieldName, int maxLength) {
    String normalized = normalizeToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    validateMaxLength(fieldName, normalized, maxLength);
    return normalized;
  }

  private String optionalIdentityValue(String value, String fieldName, int maxLength) {
    String normalized = normalizeToNull(value);
    if (normalized == null) {
      return null;
    }
    validateMaxLength(fieldName, normalized, maxLength);
    return normalized;
  }

  private String normalizeToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private void validateMaxLength(String fieldName, String value, int maxLength) {
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(
          fieldName + " exceeds max length of " + maxLength + " characters");
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

  private record ConfigIdentity(
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment) {
    ConfigIdentity withUserId(String userId) {
      return new ConfigIdentity(tenantId, userId, componentType, componentId, environment);
    }
  }
}
