package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class UserConfigServiceTest {

  @Mock private UiUserConfigRepository repository;
  @Mock private AiApiKeyProtectionService apiKeyProtectionService;
  @Mock private NamedParameterJdbcTemplate jdbcTemplate;

  private UserConfigService service;

  @BeforeEach
  void setUp() {
    service = new UserConfigService(repository, new ObjectMapper(), apiKeyProtectionService, jdbcTemplate);
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
                    "stale-etag",
                    "qa-user"))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("If-Match precondition failed");

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
                    "stale-etag"))
        .isInstanceOf(UserConfigService.PreconditionFailedException.class)
        .hasMessageContaining("If-Match precondition failed");

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
}
