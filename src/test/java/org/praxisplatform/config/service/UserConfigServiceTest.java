package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.repository.UiUserConfigRepository;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class UserConfigServiceTest {

  @Mock private UiUserConfigRepository repository;
  @Mock private AiApiKeyProtectionService apiKeyProtectionService;

  private UserConfigService service;

  @BeforeEach
  void setUp() {
    service = new UserConfigService(repository, new ObjectMapper(), apiKeyProtectionService);
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

    verify(repository, never()).save(any(UiUserConfig.class));
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
}
