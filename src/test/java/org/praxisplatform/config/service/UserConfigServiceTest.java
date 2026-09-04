package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.repository.UiUserConfigRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class UserConfigServiceTest {

  @Mock private UiUserConfigRepository repository;
  @Mock private AiApiKeyProtectionService apiKeyProtectionService;
  @Mock private NamedParameterJdbcTemplate jdbcTemplate;
  @Mock private ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;

  private UserConfigService service;

  @BeforeEach
  void setUp() {
    lenient().when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
    service = new UserConfigService(repository, new ObjectMapper(), apiKeyProtectionService, jdbcTemplateProvider);
  }

  @Test
  void shouldCreateUserConfigWithInsertOnlySemantics() throws Exception {
    JsonNode payload = readJson("{\"widgets\":[{\"key\":\"critical-employees\"}]}");
    JsonNode sanitizedPayload = readJson("{\"widgets\":[{\"key\":\"critical-employees\"}]}");
    JsonNode tags = readJson("{\"source\":\"agentic-authoring\"}");

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-dynamic-page", "absence-dashboard", "user-1"))
        .thenReturn(Optional.empty());
    when(apiKeyProtectionService.sanitizeForStorage(payload, null)).thenReturn(sanitizedPayload);
    when(repository.saveAndFlush(any(UiUserConfig.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    UiUserConfig created =
        service.create(
            UserConfigService.Scope.USER,
            " tenant-a ",
            " user-1 ",
            " praxis-dynamic-page ",
            " absence-dashboard ",
            null,
            payload,
            tags,
            " authoring-user ");

    assertThat(created.getTenantId()).isEqualTo("tenant-a");
    assertThat(created.getUserId()).isEqualTo("user-1");
    assertThat(created.getComponentType()).isEqualTo("praxis-dynamic-page");
    assertThat(created.getComponentId()).isEqualTo("absence-dashboard");
    assertThat(created.getVersion()).isEqualTo(1L);
    assertThat(created.getEtag()).isNotNull();
    assertThat(created.getPayload()).isEqualTo(sanitizedPayload.toString());
    assertThat(created.getTags()).isEqualTo(tags.toString());
    assertThat(created.getUpdatedBy()).isEqualTo("authoring-user");
    verify(repository).saveAndFlush(created);
  }

  @Test
  void shouldCreateExecutablePayloadAndAuthoringSourceInOneRevision() throws Exception {
    JsonNode payload = readJson("{\"widgets\":[]}");
    JsonNode authoringSource = readJson("""
        {
          "schemaVersion":"praxis.ui-authoring-source/v1",
          "kind":"ui-composition-plan",
          "source":{"version":"1.0","kind":"praxis.ui-composition-plan","widgets":[]},
          "sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "materialization":{"kind":"widget-page-definition","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
          "provenance":{"resultEventId":"00000000-0000-0000-0000-000000000001"}
        }
        """);

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-dynamic-page", "absence-dashboard", "user-1"))
        .thenReturn(Optional.empty());
    when(apiKeyProtectionService.sanitizeForStorage(payload, null)).thenReturn(payload);
    when(apiKeyProtectionService.sanitizeForStorage(authoringSource, null)).thenReturn(authoringSource);
    when(repository.saveAndFlush(any(UiUserConfig.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    UiUserConfig created = service.createAuthored(
        UserConfigService.Scope.USER,
        "tenant-a",
        "user-1",
        "praxis-dynamic-page",
        "absence-dashboard",
        null,
        payload,
        authoringSource,
        null,
        "authoring-user");

    assertThat(readJson(created.getAuthoringSource())).isEqualTo(authoringSource);
    assertThat(readJson(created.getPayload())).isEqualTo(payload);
    assertThat(created.getVersion()).isEqualTo(1L);
  }

  @Test
  void shouldPreserveAttestedSourceWhenGenericWriteDoesNotChangePayload() throws Exception {
    UUID currentEtag = UUID.fromString("123e4567-e89b-12d3-a456-426614174031");
    JsonNode payload = readJson("{\"widgets\":[]}");
    JsonNode source = readJson("{\"schemaVersion\":\"praxis.ui-authoring-source/v1\"}");
    UiUserConfig current = UiUserConfig.builder()
        .id(UUID.fromString("123e4567-e89b-12d3-a456-426614174030"))
        .tenantId("tenant-a")
        .componentType("praxis-dynamic-page")
        .componentId("absence-dashboard")
        .payload(payload.toString())
        .authoringSource(source.toString())
        .version(2L)
        .etag(currentEtag)
        .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
                "tenant-a", "praxis-dynamic-page", "absence-dashboard"))
        .thenReturn(Optional.of(current));
    when(apiKeyProtectionService.sanitizeForStorage(payload, payload)).thenReturn(payload);
    when(repository.updateIfCurrent(
            any(UUID.class), anyString(), any(), any(), anyLong(), any(UUID.class),
            any(UUID.class), any(), anyString()))
        .thenReturn(1);

    service.upsert(
        UserConfigService.Scope.TENANT,
        "tenant-a",
        null,
        "praxis-dynamic-page",
        "absence-dashboard",
        null,
        payload,
        null,
        "\"" + currentEtag + "\"",
        "manual-editor");

    ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
    verify(repository).updateIfCurrent(
        any(UUID.class), anyString(), sourceCaptor.capture(), any(), anyLong(), any(UUID.class),
        any(UUID.class), any(), anyString());
    assertThat(readJson(sourceCaptor.getValue())).isEqualTo(source);
  }

  @Test
  void shouldClearAttestedSourceWhenGenericWriteChangesPayload() throws Exception {
    UUID currentEtag = UUID.fromString("123e4567-e89b-12d3-a456-426614174041");
    JsonNode previousPayload = readJson("{\"widgets\":[]}");
    JsonNode changedPayload = readJson("{\"widgets\":[{\"key\":\"manual\"}]}");
    UiUserConfig current = UiUserConfig.builder()
        .id(UUID.fromString("123e4567-e89b-12d3-a456-426614174040"))
        .tenantId("tenant-a")
        .componentType("praxis-dynamic-page")
        .componentId("absence-dashboard")
        .payload(previousPayload.toString())
        .authoringSource("{\"schemaVersion\":\"praxis.ui-authoring-source/v1\"}")
        .version(2L)
        .etag(currentEtag)
        .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
                "tenant-a", "praxis-dynamic-page", "absence-dashboard"))
        .thenReturn(Optional.of(current));
    when(apiKeyProtectionService.sanitizeForStorage(changedPayload, previousPayload))
        .thenReturn(changedPayload);
    when(repository.updateIfCurrent(
            any(UUID.class), anyString(), any(), any(), anyLong(), any(UUID.class),
            any(UUID.class), any(), anyString()))
        .thenReturn(1);

    service.upsert(
        UserConfigService.Scope.TENANT,
        "tenant-a",
        null,
        "praxis-dynamic-page",
        "absence-dashboard",
        null,
        changedPayload,
        null,
        "\"" + currentEtag + "\"",
        "manual-editor");

    ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
    verify(repository).updateIfCurrent(
        any(UUID.class), anyString(), sourceCaptor.capture(), any(), anyLong(), any(UUID.class),
        any(UUID.class), any(), anyString());
    assertThat(sourceCaptor.getValue()).isNull();
    assertThat(current.getAuthoringSource()).isNull();
  }

  @Test
  void shouldRejectCreateReplayWhenExactConfigAlreadyExists() throws Exception {
    JsonNode payload = readJson("{\"widgets\":[]}");
    UiUserConfig existing =
        UiUserConfig.builder()
            .tenantId("tenant-a")
            .componentType("praxis-dynamic-page")
            .componentId("absence-dashboard")
            .payload("{\"widgets\":[{\"key\":\"existing\"}]}")
            .version(3L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174020"))
            .build();
    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
                "tenant-a", "praxis-dynamic-page", "absence-dashboard"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.create(
                    UserConfigService.Scope.TENANT,
                    "tenant-a",
                    null,
                    "praxis-dynamic-page",
                    "absence-dashboard",
                    null,
                    payload,
                    null,
                    "authoring-user"))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("configuration already exists");

    verify(repository, never()).saveAndFlush(any(UiUserConfig.class));
    verifyNoInteractions(apiKeyProtectionService);
  }

  @Test
  void shouldConvertConcurrentCreateConflictWithoutOverwritingWinner() throws Exception {
    JsonNode payload = readJson("{\"widgets\":[{\"key\":\"candidate\"}]}");
    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-dynamic-page", "absence-dashboard", "user-1"))
        .thenReturn(Optional.empty());
    when(apiKeyProtectionService.sanitizeForStorage(payload, null)).thenReturn(payload);
    when(repository.saveAndFlush(any(UiUserConfig.class)))
        .thenThrow(new DataIntegrityViolationException("unique config identity"));

    assertThatThrownBy(
            () ->
                service.create(
                    UserConfigService.Scope.USER,
                    "tenant-a",
                    "user-1",
                    "praxis-dynamic-page",
                    "absence-dashboard",
                    null,
                    payload,
                    null,
                    "authoring-user"))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("configuration already exists");

    verify(repository).saveAndFlush(any(UiUserConfig.class));
    verify(jdbcTemplate, never())
        .queryForObject(
            anyString(),
            any(MapSqlParameterSource.class),
            ArgumentMatchers.<RowMapper<UiUserConfig>>any());
  }

  @Test
  void shouldNotFallbackToLegacyComponentTypeWhenSelectorMissing() {
    UiUserConfig legacy =
        UiUserConfig.builder()
            .tenantId("t1")
            .componentType("table")
            .componentId("table-config:customers")
            .payload("{\"columns\":[]}")
            .version(1L)
            .etag(UUID.randomUUID())
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
            "t1", "praxis-table", "table-config:customers"))
        .thenReturn(Optional.empty());

    Optional<UserConfigService.ResolvedConfig> resolved =
        service.getResolved("t1", null, "praxis-table", "table-config:customers", null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void shouldRejectUpsertWhenIfMatchIsStale() throws Exception {
    JsonNode payload = new ObjectMapper().readTree("{\"columns\":[\"id\"]}");
    UiUserConfig current =
        UiUserConfig.builder()
            .id(UUID.fromString("123e4567-e89b-12d3-a456-426614174099"))
            .tenantId("tenant-a")
            .userId("user-1")
            .componentType("praxis-table")
            .componentId("table-config:employees")
            .payload("{\"columns\":[]}")
            .version(2L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.of(current));
    assertThatThrownBy(
            () ->
                service.upsert(
                    UserConfigService.Scope.USER,
                    "tenant-a",
                    "user-1",
                    "praxis-table",
                    "table-config:employees",
                    null,
                    payload,
                    null,
                    "\"stale-etag\"",
                    "qa-user"))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("If-Match precondition failed");

    verify(repository, never()).saveAndFlush(any(UiUserConfig.class));
  }

  @Test
  void shouldAcceptUpsertWhenIfMatchListContainsCurrentStrongEtag() throws Exception {
    JsonNode payload = new ObjectMapper().readTree("{\"columns\":[\"id\"]}");
    UiUserConfig current =
        UiUserConfig.builder()
            .id(UUID.fromString("123e4567-e89b-12d3-a456-426614174097"))
            .tenantId("tenant-a")
            .userId("user-1")
            .componentType("praxis-table")
            .componentId("table-config:employees")
            .payload("{\"columns\":[]}")
            .version(2L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.of(current));
    when(apiKeyProtectionService.sanitizeForStorage(payload, readJson("{\"columns\":[]}"))).thenReturn(payload);
    when(repository.updateIfCurrent(
            any(UUID.class),
            anyString(),
            any(),
            any(),
            anyLong(),
            any(UUID.class),
            any(UUID.class),
            any(),
            anyString()))
        .thenReturn(1);

    UiUserConfig saved =
        service.upsert(
            UserConfigService.Scope.USER,
            "tenant-a",
            "user-1",
            "praxis-table",
            "table-config:employees",
            null,
            payload,
            null,
            "\"stale\", \"123e4567-e89b-12d3-a456-426614174000\"",
            "qa-user");

    assertThat(saved).isSameAs(current);
    assertThat(saved.getVersion()).isEqualTo(3L);
    assertThat(saved.getEtag()).isNotEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    verify(repository).updateIfCurrent(
        any(UUID.class),
        anyString(),
        any(),
        any(),
        anyLong(),
        any(UUID.class),
        any(UUID.class),
        any(),
        anyString());
  }

  @Test
  void shouldRejectUpdateWhenAnotherWriterConsumesTheMatchedEtagFirst() throws Exception {
    JsonNode payload = readJson("{\"columns\":[\"id\"]}");
    UUID currentEtag = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    UiUserConfig current = UiUserConfig.builder()
        .id(UUID.fromString("123e4567-e89b-12d3-a456-426614174098"))
        .tenantId("tenant-a")
        .userId("user-1")
        .componentType("praxis-table")
        .componentId("table-config:employees")
        .payload("{\"columns\":[]}")
        .version(2L)
        .etag(currentEtag)
        .build();
    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.of(current));
    when(apiKeyProtectionService.sanitizeForStorage(payload, readJson("{\"columns\":[]}"))).thenReturn(payload);
    when(repository.updateIfCurrent(
            any(UUID.class),
            anyString(),
            any(),
            any(),
            anyLong(),
            any(UUID.class),
            any(UUID.class),
            any(),
            anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.upsert(
        UserConfigService.Scope.USER,
        "tenant-a",
        "user-1",
        "praxis-table",
        "table-config:employees",
        null,
        payload,
        null,
        "\"" + currentEtag + "\"",
        "qa-user"))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("changed concurrently");

    assertThat(current.getVersion()).isEqualTo(2L);
    assertThat(current.getEtag()).isEqualTo(currentEtag);
  }

  @Test
  void shouldRejectWeakIfMatchEvenWhenValueMatchesCurrentEtag() throws Exception {
    JsonNode payload = new ObjectMapper().readTree("{\"columns\":[\"id\"]}");
    UiUserConfig current =
        UiUserConfig.builder()
            .tenantId("tenant-a")
            .userId("user-1")
            .componentType("praxis-table")
            .componentId("table-config:employees")
            .payload("{\"columns\":[]}")
            .version(2L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.of(current));

    assertThatThrownBy(
            () ->
                service.upsert(
                    UserConfigService.Scope.USER,
                    "tenant-a",
                    "user-1",
                    "praxis-table",
                    "table-config:employees",
                    null,
                    payload,
                    null,
                    "W/\"123e4567-e89b-12d3-a456-426614174000\"",
                    "qa-user"))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("If-Match precondition failed");

    verify(repository, never()).saveAndFlush(any(UiUserConfig.class));
  }

  @Test
  void shouldRejectMalformedIfMatchHeaderBeforePersisting() throws Exception {
    JsonNode payload = new ObjectMapper().readTree("{\"columns\":[\"id\"]}");
    UiUserConfig current =
        UiUserConfig.builder()
            .tenantId("tenant-a")
            .userId("user-1")
            .componentType("praxis-table")
            .componentId("table-config:employees")
            .payload("{\"columns\":[]}")
            .version(2L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.of(current));

    assertThatThrownBy(
            () ->
                service.upsert(
                    UserConfigService.Scope.USER,
                    "tenant-a",
                    "user-1",
                    "praxis-table",
                    "table-config:employees",
                    null,
                    payload,
                    null,
                    "123e4567-e89b-12d3-a456-426614174000",
                    "qa-user"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid ETag condition header");

    verify(repository, never()).saveAndFlush(any(UiUserConfig.class));
  }

  @Test
  void shouldRejectDeleteWhenIfMatchIsStale() {
    UiUserConfig current =
        UiUserConfig.builder()
            .tenantId("tenant-a")
            .userId("user-1")
            .componentType("praxis-table")
            .componentId("table-config:employees")
            .payload("{\"columns\":[]}")
            .version(2L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174001"))
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.of(current));

    assertThatThrownBy(
            () ->
                service.delete(
                    UserConfigService.Scope.USER,
                    "tenant-a",
                    "user-1",
                    "praxis-table",
                    "table-config:employees",
                    null,
                    "\"stale-etag\""))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("If-Match precondition failed");

    verify(repository, never()).delete(any(UiUserConfig.class));
  }

  @Test
  void shouldAllowDeleteWhenIfMatchWildcardTargetsExistingConfig() {
    UiUserConfig current =
        UiUserConfig.builder()
            .tenantId("tenant-a")
            .userId("user-1")
            .componentType("praxis-table")
            .componentId("table-config:employees")
            .payload("{\"columns\":[]}")
            .version(2L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174001"))
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.of(current));

    service.delete(
        UserConfigService.Scope.USER,
        "tenant-a",
        "user-1",
        "praxis-table",
        "table-config:employees",
        null,
        "*");

    verify(repository).delete(current);
  }

  @Test
  void shouldAllowDeleteWhenIfMatchListContainsCurrentStrongEtag() {
    UiUserConfig current =
        UiUserConfig.builder()
            .tenantId("tenant-a")
            .userId("user-1")
            .componentType("praxis-table")
            .componentId("table-config:employees")
            .payload("{\"columns\":[]}")
            .version(2L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174001"))
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.of(current));

    service.delete(
        UserConfigService.Scope.USER,
        "tenant-a",
        "user-1",
        "praxis-table",
        "table-config:employees",
        null,
        "\"stale\", \"123e4567-e89b-12d3-a456-426614174001\"");

    verify(repository).delete(current);
  }

  @Test
  void shouldRejectIfMatchWildcardWhenTargetDoesNotExist() {
    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees", "user-1"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.delete(
                    UserConfigService.Scope.USER,
                    "tenant-a",
                    "user-1",
                    "praxis-table",
                    "table-config:employees",
                    null,
                    "*"))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("configuration not found");

    verify(repository, never()).delete(any(UiUserConfig.class));
  }

  @Test
  void shouldUseAtomicUpsertWhenUpsertHasNoIfMatch() throws Exception {
    UiUserConfig atomicResult =
        UiUserConfig.builder()
            .tenantId("tenant-a")
            .userId(null)
            .componentType("praxis-tabs")
            .componentId("tabs:rk=table-connections-lab|ct=praxis-tabs|id=table-connections-tabs|ik=0")
            .environment("local")
            .payload("{\"selectedIndex\":1}")
            .version(2L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174002"))
            .updatedBy("browser-smoke")
            .build();

    UiUserConfig saved = runAtomicUpsert(null, "local", atomicResult);

    assertThat(saved).isSameAs(atomicResult);
    assertThat(saved.getPayload()).isEqualTo("{\"selectedIndex\":1}");
    assertThat(saved.getVersion()).isEqualTo(2L);
    assertThat(saved.getUpdatedBy()).isEqualTo("browser-smoke");
    verify(repository, never()).saveAndFlush(any(UiUserConfig.class));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .queryForObject(
            sqlCaptor.capture(),
            any(MapSqlParameterSource.class),
            ArgumentMatchers.<RowMapper<UiUserConfig>>any());
    assertThat(sqlCaptor.getValue())
        .contains(
            "ON CONFLICT (tenant_id, component_type, component_id, environment) WHERE environment IS NOT NULL AND user_id IS NULL");
  }

  @Test
  void shouldUseAtomicUpsertConflictTargetForTenantGlobalScope() throws Exception {
    UiUserConfig saved = runAtomicUpsert(null, null, atomicResult(null, null));

    assertThat(saved.getVersion()).isEqualTo(2L);
    verifyAtomicUpsertSql()
        .contains(
            "ON CONFLICT (tenant_id, component_type, component_id) WHERE environment IS NULL AND user_id IS NULL");
  }

  @Test
  void shouldUseAtomicUpsertConflictTargetForUserGlobalScope() throws Exception {
    UiUserConfig saved = runAtomicUpsert("user-1", null, atomicResult("user-1", null));

    assertThat(saved.getVersion()).isEqualTo(2L);
    verifyAtomicUpsertSql()
        .contains(
            "ON CONFLICT (tenant_id, component_type, component_id, user_id) WHERE environment IS NULL AND user_id IS NOT NULL");
  }

  @Test
  void shouldUseAtomicUpsertConflictTargetForUserEnvironmentScope() throws Exception {
    UiUserConfig saved = runAtomicUpsert("user-1", "local", atomicResult("user-1", "local"));

    assertThat(saved.getVersion()).isEqualTo(2L);
    verifyAtomicUpsertSql()
        .contains(
            "ON CONFLICT (tenant_id, user_id, component_type, component_id, environment)");
  }

  @Test
  void shouldNormalizeBlankEnvironmentToGlobalScopeBeforeAtomicUpsert() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode payload = mapper.readTree("{\"density\":\"compact\"}");
    UiUserConfig atomicResult =
        UiUserConfig.builder()
            .tenantId("tenant-a")
            .componentType("praxis-table")
            .componentId("table-config:employees")
            .environment(null)
            .payload(payload.toString())
            .version(1L)
            .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174010"))
            .build();

    when(repository
            .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
                "tenant-a", "praxis-table", "table-config:employees"))
        .thenReturn(Optional.empty());
    when(apiKeyProtectionService.sanitizeForStorage(payload, null)).thenReturn(payload);
    when(jdbcTemplate.queryForObject(
            anyString(),
            any(MapSqlParameterSource.class),
            ArgumentMatchers.<RowMapper<UiUserConfig>>any()))
        .thenReturn(atomicResult);

    UiUserConfig saved =
        service.upsert(
            UserConfigService.Scope.TENANT,
            " tenant-a ",
            null,
            " praxis-table ",
            " table-config:employees ",
            "   ",
            payload,
            null,
            null,
            " qa-user ");

    assertThat(saved.getEnvironment()).isNull();
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbcTemplate)
        .queryForObject(
            anyString(),
            paramsCaptor.capture(),
            ArgumentMatchers.<RowMapper<UiUserConfig>>any());
    MapSqlParameterSource params = paramsCaptor.getValue();
    assertThat(params.getValue("tenantId")).isEqualTo("tenant-a");
    assertThat(params.getValue("componentType")).isEqualTo("praxis-table");
    assertThat(params.getValue("componentId")).isEqualTo("table-config:employees");
    assertThat(params.getValue("environment")).isNull();
    assertThat(params.getValue("updatedBy")).isEqualTo("qa-user");
    verifyAtomicUpsertSql()
        .contains(
            "ON CONFLICT (tenant_id, component_type, component_id) WHERE environment IS NULL AND user_id IS NULL");
  }

  @Test
  void shouldRejectOversizedComponentTypeBeforeRepositoryLookup() {
    String componentType = "x".repeat(65);

    assertThatThrownBy(
            () ->
                service.getResolved(
                    "tenant-a",
                    null,
                    componentType,
                    "table-config:employees",
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("componentType exceeds max length of 64 characters");

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectOversizedEnvironmentBeforeRepositoryLookup() {
    String environment = "e".repeat(65);

    assertThatThrownBy(
            () ->
                service.getResolved(
                    "tenant-a",
                    null,
                    "praxis-table",
                    "table-config:employees",
                    environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("environment exceeds max length of 64 characters");

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectBlankTenantBeforeRepositoryLookup() {
    assertThatThrownBy(
            () ->
                service.getResolved(
                    "   ",
                    null,
                    "praxis-table",
                    "table-config:employees",
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId is required");

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRequireUserIdForExplicitUserScopeReads() {
    assertThatThrownBy(
            () ->
                service.getByScope(
                    UserConfigService.Scope.USER,
                    "tenant-a",
                    "   ",
                    "praxis-table",
                    "table-config:employees",
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("User scope requires X-User-ID header");

    verifyNoInteractions(repository);
  }

  private UiUserConfig runAtomicUpsert(String userId, String environment, UiUserConfig atomicResult)
      throws Exception {
    reset(repository, apiKeyProtectionService, jdbcTemplate);
    ObjectMapper mapper = new ObjectMapper();
    JsonNode payload = mapper.readTree("{\"selectedIndex\":1}");
    String componentId =
        "tabs:rk=table-connections-lab|ct=praxis-tabs|id=table-connections-tabs|ik=0";

    when(apiKeyProtectionService.sanitizeForStorage(payload, null)).thenReturn(payload);
    if (userId == null && environment == null) {
      when(repository
              .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
                  "tenant-a", "praxis-tabs", componentId))
          .thenReturn(Optional.empty());
    } else if (userId == null) {
      when(repository
              .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdIsNullOrderByUpdatedAtDesc(
                  "tenant-a", "praxis-tabs", componentId, environment))
          .thenReturn(Optional.empty());
    } else if (environment == null) {
      when(repository
              .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
                  "tenant-a", "praxis-tabs", componentId, userId))
          .thenReturn(Optional.empty());
    } else {
      when(repository
              .findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdOrderByUpdatedAtDesc(
                  "tenant-a", "praxis-tabs", componentId, environment, userId))
          .thenReturn(Optional.empty());
    }
    when(jdbcTemplate.queryForObject(
            anyString(),
            any(MapSqlParameterSource.class),
            ArgumentMatchers.<RowMapper<UiUserConfig>>any()))
        .thenReturn(atomicResult);

    return service.upsert(
        userId == null ? UserConfigService.Scope.TENANT : UserConfigService.Scope.USER,
        "tenant-a",
        userId,
        "praxis-tabs",
        componentId,
        environment,
        payload,
        null,
        null,
        "browser-smoke");
  }

  private UiUserConfig atomicResult(String userId, String environment) {
    return UiUserConfig.builder()
        .tenantId("tenant-a")
        .userId(userId)
        .componentType("praxis-tabs")
        .componentId("tabs:rk=table-connections-lab|ct=praxis-tabs|id=table-connections-tabs|ik=0")
        .environment(environment)
        .payload("{\"selectedIndex\":1}")
        .version(2L)
        .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174002"))
        .updatedBy("browser-smoke")
        .build();
  }

  private org.assertj.core.api.AbstractStringAssert<?> verifyAtomicUpsertSql() {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .queryForObject(
            sqlCaptor.capture(),
            any(MapSqlParameterSource.class),
            ArgumentMatchers.<RowMapper<UiUserConfig>>any());
    return assertThat(sqlCaptor.getValue());
  }

  private JsonNode readJson(String raw) throws Exception {
    return new ObjectMapper().readTree(raw);
  }
}
