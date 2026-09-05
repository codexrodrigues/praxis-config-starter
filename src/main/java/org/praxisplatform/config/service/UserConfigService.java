package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  private static final int MAX_AUTHORING_SOURCE_BYTES = 256 * 1024;
  private static final int MAX_SCOPE_ID_LENGTH = 255;
  private static final int MAX_COMPONENT_TYPE_LENGTH = 64;
  private static final int MAX_COMPONENT_ID_LENGTH = 255;
  private static final int MAX_ENVIRONMENT_LENGTH = 64;
  private static final String AUTHORING_SOURCE_SCHEMA_VERSION = "praxis.ui-authoring-source/v1";
  private static final String AUTHORING_SOURCE_KIND = "ui-composition-plan";
  private static final String UI_COMPOSITION_PLAN_KIND = "praxis.ui-composition-plan";
  private static final String UI_COMPOSITION_PLAN_VERSION = "1.0";
  private static final String MATERIALIZATION_KIND = "widget-page-definition";

  private final UiUserConfigRepository repository;
  private final ObjectMapper objectMapper;
  private final AiApiKeyProtectionService apiKeyProtectionService;
  private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;
  private final CanonicalJsonHashService canonicalJsonHashService;

  public UserConfigService(
      UiUserConfigRepository repository,
      ObjectMapper objectMapper,
      AiApiKeyProtectionService apiKeyProtectionService,
      @Qualifier("configNamedParameterJdbcTemplate") ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider,
      CanonicalJsonHashService canonicalJsonHashService) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.apiKeyProtectionService = apiKeyProtectionService;
    this.jdbcTemplateProvider = jdbcTemplateProvider;
    this.canonicalJsonHashService = canonicalJsonHashService;
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

  /** Creates a scoped configuration and fails when that exact identity already exists. */
  public UiUserConfig create(
      Scope scope,
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment,
      JsonNode payload,
      JsonNode tags,
      String updatedBy) {
    return createInternal(
        scope,
        tenantId,
        userId,
        componentType,
        componentId,
        environment,
        payload,
        null,
        tags,
        updatedBy);
  }

  /**
   * Creates an executable configuration and its server-attested semantic source atomically.
   */
  public UiUserConfig createAuthored(
      Scope scope,
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment,
      JsonNode payload,
      JsonNode authoringSource,
      JsonNode tags,
      String updatedBy) {
    if (authoringSource == null || !authoringSource.isObject()) {
      throw new IllegalArgumentException("authoringSource must be a JSON object");
    }
    return createInternal(
        scope,
        tenantId,
        userId,
        componentType,
        componentId,
        environment,
        payload,
        authoringSource,
        tags,
        updatedBy);
  }

  private UiUserConfig createInternal(
      Scope scope,
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment,
      JsonNode payload,
      JsonNode authoringSource,
      JsonNode tags,
      String updatedBy) {
    ConfigIdentity identity = normalizeIdentity(tenantId, userId, componentType, componentId, environment);
    String normalizedUpdatedBy = optionalIdentityValue(updatedBy, "updatedBy", MAX_SCOPE_ID_LENGTH);
    if (scope == Scope.USER && identity.userId() == null) {
      throw new IllegalArgumentException("User scope requires X-User-ID header");
    }
    ConfigIdentity effectiveIdentity = scope == Scope.USER ? identity : identity.withUserId(null);
    if (findConfig(effectiveIdentity).isPresent()) {
      throw new PreconditionFailedException("Create precondition failed: configuration already exists");
    }

    JsonNode sanitizedPayload = apiKeyProtectionService.sanitizeForStorage(payload, null);
    validatePayloadSize(sanitizedPayload);
    JsonNode sanitizedAuthoringSource = sanitizeAuthoringSource(authoringSource, null);
    if (sanitizedAuthoringSource != null) {
      sanitizedAuthoringSource = attestAuthoringSource(
          sanitizedAuthoringSource, sanitizedPayload, effectiveIdentity);
    }
    UiUserConfig created =
        UiUserConfig.builder()
            .tenantId(effectiveIdentity.tenantId())
            .userId(effectiveIdentity.userId())
            .componentType(effectiveIdentity.componentType())
            .componentId(effectiveIdentity.componentId())
            .environment(effectiveIdentity.environment())
            .payload(writeJson(sanitizedPayload))
            .authoringSource(writeNullableJson(sanitizedAuthoringSource))
            .tags(tags != null ? writeJson(tags) : null)
            .version(1L)
            .etag(UUID.randomUUID())
            .updatedBy(normalizedUpdatedBy)
            .build();
    try {
      return repository.saveAndFlush(created);
    } catch (DataIntegrityViolationException ex) {
      throw new PreconditionFailedException("Create precondition failed: configuration already exists");
    }
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
    return upsertInternal(
        scope,
        tenantId,
        userId,
        componentType,
        componentId,
        environment,
        payload,
        null,
        false,
        tags,
        ifMatch,
        updatedBy);
  }

  /**
   * Upserts an executable configuration and replaces its semantic source in the same revision.
   */
  public UiUserConfig upsertAuthored(
      Scope scope,
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment,
      JsonNode payload,
      JsonNode authoringSource,
      JsonNode tags,
      String ifMatch,
      String updatedBy) {
    if (authoringSource == null || !authoringSource.isObject()) {
      throw new IllegalArgumentException("authoringSource must be a JSON object");
    }
    return upsertInternal(
        scope,
        tenantId,
        userId,
        componentType,
        componentId,
        environment,
        payload,
        authoringSource,
        true,
        tags,
        ifMatch,
        updatedBy);
  }

  private UiUserConfig upsertInternal(
      Scope scope,
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String environment,
      JsonNode payload,
      JsonNode authoringSource,
      boolean replaceAuthoringSource,
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
    JsonNode resolvedAuthoringSource = replaceAuthoringSource
        ? sanitizeAuthoringSource(
            authoringSource,
            existing.map(cfg -> readJson(cfg.getAuthoringSource())).orElse(null))
        : authoringSourceForGenericWrite(existing, existingPayload, sanitizedPayload);
    if (replaceAuthoringSource) {
      resolvedAuthoringSource = attestAuthoringSource(
          resolvedAuthoringSource, sanitizedPayload, effectiveIdentity);
    }

    String payloadJson = writeJson(sanitizedPayload);
    String authoringSourceJson = writeNullableJson(resolvedAuthoringSource);
    String tagsJson = tags != null ? writeJson(tags) : null;

    if (ifMatch == null || ifMatch.isBlank()) {
      return upsertWithoutPrecondition(
          effectiveIdentity,
          payloadJson,
          authoringSourceJson,
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
              .authoringSource(authoringSourceJson)
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
            authoringSourceJson,
            tagsJson,
            normalizedUpdatedBy,
            ex);
      }
    }

    return updateExisting(
        existing.get(), payloadJson, authoringSourceJson, tagsJson, normalizedUpdatedBy);
  }

  private UiUserConfig upsertWithoutPrecondition(
      ConfigIdentity identity,
      String payloadJson,
      String authoringSourceJson,
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
          payload, authoring_source, tags, version, etag, created_at, updated_at, updated_by
        )
        VALUES (
          CAST(:id AS uuid), :tenantId, :userId, :componentType, :componentId, :environment,
          CAST(:payload AS jsonb), CAST(:authoringSource AS jsonb), CAST(:tags AS jsonb), 1, CAST(:insertEtag AS uuid),
          now(), now(), :updatedBy
        )
        ON CONFLICT %s DO UPDATE SET
          payload = EXCLUDED.payload,
          authoring_source = EXCLUDED.authoring_source,
          tags = EXCLUDED.tags,
          version = ui_user_config.version + 1,
          etag = CAST(:updateEtag AS uuid),
          updated_at = now(),
          updated_by = EXCLUDED.updated_by
        RETURNING
          id, tenant_id, user_id, component_type, component_id, environment,
          payload::text AS payload, authoring_source::text AS authoring_source,
          tags::text AS tags, version, etag, created_at, updated_at, updated_by
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
            .addValue("authoringSource", authoringSourceJson)
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
        .authoringSource(rs.getString("authoring_source"))
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
      String authoringSourceJson,
      String tagsJson,
      String updatedBy,
      DataIntegrityViolationException cause) {
    UiUserConfig current =
        findConfig(identity)
            .orElseThrow(() -> cause);
    return updateExisting(current, payloadJson, authoringSourceJson, tagsJson, updatedBy);
  }

  private UiUserConfig updateExisting(
      UiUserConfig current,
      String payloadJson,
      String authoringSourceJson,
      String tagsJson,
      String updatedBy) {
    UUID expectedEtag = current.getEtag();
    UUID nextEtag = UUID.randomUUID();
    long nextVersion = current.getVersion() + 1;
    Instant updatedAt = Instant.now();
    int updated = repository.updateIfCurrent(
        current.getId(),
        payloadJson,
        authoringSourceJson,
        tagsJson,
        nextVersion,
        expectedEtag,
        nextEtag,
        updatedAt,
        updatedBy);
    if (updated != 1) {
      throw new PreconditionFailedException(
          "If-Match precondition failed: configuration changed concurrently");
    }
    current.setPayload(payloadJson);
    current.setAuthoringSource(authoringSourceJson);
    current.setTags(tagsJson);
    current.setVersion(nextVersion);
    current.setEtag(nextEtag);
    current.setUpdatedAt(updatedAt);
    current.setUpdatedBy(updatedBy);
    return current;
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

  private JsonNode sanitizeAuthoringSource(JsonNode authoringSource, JsonNode existingAuthoringSource) {
    if (authoringSource == null || authoringSource.isNull()) {
      return null;
    }
    JsonNode sanitized = apiKeyProtectionService.sanitizeForStorage(
        authoringSource,
        existingAuthoringSource);
    validateAuthoringSourceSize(sanitized);
    return sanitized;
  }

  /**
   * Recomputes the server-owned integrity fields from the public materialization returned to
   * runtime consumers. This must happen after secret protection because encryption and response
   * redaction may materially change the payload supplied by the authoring boundary.
   */
  private JsonNode attestAuthoringSource(
      JsonNode authoringSource,
      JsonNode persistedPayload,
      ConfigIdentity identity) {
    if (!(authoringSource instanceof ObjectNode sourceEnvelope)
        || !AUTHORING_SOURCE_SCHEMA_VERSION.equals(sourceEnvelope.path("schemaVersion").asText())
        || !AUTHORING_SOURCE_KIND.equals(sourceEnvelope.path("kind").asText())) {
      throw new IllegalArgumentException("Invalid ui composition authoring source envelope");
    }
    JsonNode source = sourceEnvelope.path("source");
    if (!source.isObject()
        || !UI_COMPOSITION_PLAN_KIND.equals(source.path("kind").asText())
        || !UI_COMPOSITION_PLAN_VERSION.equals(source.path("version").asText())) {
      throw new IllegalArgumentException("Invalid ui composition plan authoring source");
    }
    JsonNode materializationNode = sourceEnvelope.path("materialization");
    if (!(materializationNode instanceof ObjectNode materialization)
        || !MATERIALIZATION_KIND.equals(materialization.path("kind").asText())
        || !identity.componentType().equals(materialization.path("componentType").asText())
        || !identity.componentId().equals(materialization.path("componentId").asText())) {
      throw new IllegalArgumentException("Invalid ui composition materialization identity");
    }
    ObjectNode attested = sourceEnvelope.deepCopy();
    attested.put("sourceSha256", canonicalJsonHashService.sha256(attested.path("source")));
    JsonNode publicMaterialization = apiKeyProtectionService.sanitizeForResponse(persistedPayload);
    attested.withObject("/materialization")
        .put("sha256", canonicalJsonHashService.sha256(publicMaterialization));
    validateAuthoringSourceSize(attested);
    return attested;
  }

  /**
   * A generic UI write cannot attest a new semantic source. Preserve the existing source only
   * when the executable payload is materially unchanged; otherwise clear it so reopen never
   * presents stale semantics as the source of the current runtime page.
   */
  private JsonNode authoringSourceForGenericWrite(
      Optional<UiUserConfig> existing,
      JsonNode existingPayload,
      JsonNode nextPayload) {
    if (existing.isEmpty() || existingPayload == null || !existingPayload.equals(nextPayload)) {
      return null;
    }
    return readJson(existing.get().getAuthoringSource());
  }

  private void validateAuthoringSourceSize(JsonNode authoringSource) {
    if (authoringSource == null) {
      return;
    }
    try {
      int size = objectMapper.writeValueAsBytes(authoringSource).length;
      if (size > MAX_AUTHORING_SOURCE_BYTES) {
        throw new PayloadTooLargeException(
            "Authoring source exceeds " + MAX_AUTHORING_SOURCE_BYTES + " bytes");
      }
    } catch (PayloadTooLargeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid authoring source JSON", e);
    }
  }

  private String writeJson(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to serialize JSON", e);
    }
  }

  private String writeNullableJson(JsonNode node) {
    return node == null || node.isNull() ? null : writeJson(node);
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
