package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.praxisplatform.config.dto.UpsertUserConfigRequest;
import org.praxisplatform.config.dto.UserConfigResponse;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class UserConfigControllerTest {

  @Mock private UserConfigService service;
  @Mock private AiApiKeyProtectionService apiKeyProtectionService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private UserConfigController controller;

  @BeforeEach
  void setUp() {
    controller = new UserConfigController(service, objectMapper, apiKeyProtectionService);
  }

  @Test
  void shouldResolveUserScopeFromHeaderWhenScopeParamMissing() {
    when(apiKeyProtectionService.sanitizeForResponse(any())).thenAnswer(invocation -> invocation.getArgument(0));

    UiUserConfig config = buildConfig("tenant-a", "user-1", "praxis-table", "table-config:employees", "{\"columns\":[]}");

    when(service.getResolved("tenant-a", "user-1", "praxis-table", "table-config:employees", "local"))
        .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

    ResponseEntity<UserConfigResponse> response =
        controller.getConfigByParams(
            "praxis-table",
            "table-config:employees",
            "tenant-a",
            "user-1",
            "local",
            null,
            null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"" + config.getEtag() + "\"");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getScope()).isEqualTo("user");
    assertThat(response.getBody().getPayload().get("columns").isArray()).isTrue();

    verify(service).getResolved("tenant-a", "user-1", "praxis-table", "table-config:employees", "local");
  }

  @Test
  void shouldUseExplicitTenantScopeWithoutUserFallback() {
    when(apiKeyProtectionService.sanitizeForResponse(any())).thenAnswer(invocation -> invocation.getArgument(0));

    UiUserConfig config = buildConfig("tenant-a", null, "praxis-table", "table-config:employees", "{\"columns\":[]}");

    when(service.getByScope(
            UserConfigService.Scope.TENANT,
            "tenant-a",
            "user-1",
            "praxis-table",
            "table-config:employees",
            null))
        .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.TENANT)));

    ResponseEntity<UserConfigResponse> response =
        controller.getConfigByParams(
            "praxis-table",
            "table-config:employees",
            "tenant-a",
            "user-1",
            null,
            null,
            "tenant");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getScope()).isEqualTo("tenant");

    verify(service)
        .getByScope(
            UserConfigService.Scope.TENANT,
            "tenant-a",
            "user-1",
            "praxis-table",
            "table-config:employees",
            null);
  }

  @Test
  void shouldReturn304WhenIfNoneMatchMatchesResolvedEtag() {
    UiUserConfig config =
        buildConfig("tenant-a", null, "praxis-dynamic-page", "page:demo:sales:dashboard", "{\"widgets\":[]}");

    when(service.getResolved("tenant-a", null, "praxis-dynamic-page", "page:demo:sales:dashboard", null))
        .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.TENANT)));

    ResponseEntity<UserConfigResponse> response =
        controller.getConfigByParams(
            "praxis-dynamic-page",
            "page:demo:sales:dashboard",
            "tenant-a",
            null,
            null,
            "\"" + config.getEtag() + "\"",
            null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"" + config.getEtag() + "\"");
    assertThat(response.getBody()).isNull();
  }

  @Test
  void shouldReturnPersistedPayloadAndQuotedEtagOnUpsert() {
    when(apiKeyProtectionService.sanitizeForResponse(any())).thenAnswer(invocation -> invocation.getArgument(0));

    JsonNode payload = readJson("{\"theme\":\"corporate\"}");
    JsonNode tags = readJson("{\"team\":\"sales\"}");
    UiUserConfig saved =
        buildConfig("tenant-a", "user-9", "praxis-global-config-editor", "praxis:global-config", payload.toString());
    saved.setTags(tags.toString());

    when(service.upsert(
            eq(UserConfigService.Scope.USER),
            eq("tenant-a"),
            eq("user-9"),
            eq("praxis-global-config-editor"),
            eq("praxis:global-config"),
            eq("prod"),
            any(JsonNode.class),
            any(JsonNode.class),
            eq(null),
            eq("qa-user")))
        .thenReturn(saved);

    UpsertUserConfigRequest request = new UpsertUserConfigRequest();
    request.setPayload(payload);
    request.setTags(tags);

    ResponseEntity<UserConfigResponse> response =
        controller.upsertConfigByParams(
            "praxis-global-config-editor",
            "praxis:global-config",
            "tenant-a",
            "user-9",
            "prod",
            "qa-user",
            null,
            null,
            request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"" + saved.getEtag() + "\"");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getScope()).isEqualTo("user");
    assertThat(response.getBody().getPayload().get("theme").asText()).isEqualTo("corporate");
    assertThat(response.getBody().getTags().get("team").asText()).isEqualTo("sales");
  }

  @Test
  void shouldNormalizeWeakIfMatchBeforeDelegatingUpsert() {
    when(apiKeyProtectionService.sanitizeForResponse(any())).thenAnswer(invocation -> invocation.getArgument(0));

    JsonNode payload = readJson("{\"density\":\"compact\"}");
    UiUserConfig saved =
        buildConfig(
            "tenant-a",
            "user-9",
            "praxis-table",
            "table-config:employees",
            payload.toString());

    when(service.upsert(
            eq(UserConfigService.Scope.USER),
            eq("tenant-a"),
            eq("user-9"),
            eq("praxis-table"),
            eq("table-config:employees"),
            eq("local"),
            any(JsonNode.class),
            eq(null),
            eq("etag-123"),
            eq("qa-user")))
        .thenReturn(saved);

    UpsertUserConfigRequest request = new UpsertUserConfigRequest();
    request.setPayload(payload);

    ResponseEntity<UserConfigResponse> response =
        controller.upsertConfigByParams(
            "praxis-table",
            "table-config:employees",
            "tenant-a",
            "user-9",
            "local",
            "qa-user",
            "W/\"etag-123\"",
            null,
            request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void shouldTranslatePreconditionFailureTo412() {
    ResponseEntity<String> response =
        controller.handlePreconditionFailed(
            new UserConfigService.PreconditionFailedException("stale configuration version"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
    assertThat(response.getBody()).isEqualTo("stale configuration version");
  }

  private UiUserConfig buildConfig(
      String tenantId,
      String userId,
      String componentType,
      String componentId,
      String payload) {
    return UiUserConfig.builder()
        .tenantId(tenantId)
        .userId(userId)
        .componentType(componentType)
        .componentId(componentId)
        .payload(payload)
        .version(7L)
        .etag(UUID.randomUUID())
        .build();
  }

  private JsonNode readJson(String raw) {
    try {
      return objectMapper.readTree(raw);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
