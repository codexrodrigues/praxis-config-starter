package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.service.AiContextService;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiContextControllerTest {

  @Mock private AiContextService contextService;
  @Mock private UserConfigService userConfigService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private AiContextController controller;

  @BeforeEach
  void setUp() {
    controller = new AiContextController(contextService, userConfigService, objectMapper);
  }

  @Test
  void shouldReturnCurrentStateWhenComponentTypeIsSelector() {
    String componentId = "praxis-table";
    String componentType = "praxis-table";
    String payload = "{\"columns\":[{\"id\":\"id\"}]}";
    UiUserConfig config =
        UiUserConfig.builder()
            .tenantId("t1")
            .userId("u1")
            .componentType(componentType)
            .componentId(componentId)
            .payload(payload)
            .version(1L)
            .etag(UUID.randomUUID())
            .build();

    when(userConfigService.getResolved("t1", "u1", componentType, componentId, null))
        .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));
    when(contextService.parseJson(payload)).thenReturn(readJson(payload));
    when(contextService.buildContext(
            org.mockito.ArgumentMatchers.eq(componentId),
            org.mockito.ArgumentMatchers.eq(componentType),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any(JsonNode.class),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()))
        .thenAnswer(
            invocation ->
                AiContextDTO.builder()
                    .componentId(invocation.getArgument(0, String.class))
                    .componentType(invocation.getArgument(1, String.class))
                    .currentState(invocation.getArgument(4, JsonNode.class))
                    .build());

    ResponseEntity<AiContextDTO> response =
        controller.getAiContext(
            componentId,
            "t1",
            "u1",
            componentType,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    AiContextDTO dto = response.getBody();
    assertThat(dto).isNotNull();
    assertThat(dto.getComponentType()).isEqualTo(componentType);
    assertThat(dto.getCurrentState().get("columns").get(0).get("id").asText()).isEqualTo("id");

    verify(userConfigService).getResolved("t1", "u1", componentType, componentId, null);
    verify(contextService)
        .buildContext(
            org.mockito.ArgumentMatchers.eq(componentId),
            org.mockito.ArgumentMatchers.eq(componentType),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any(JsonNode.class),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull());
  }

  private JsonNode readJson(String raw) {
    try {
      return objectMapper.readTree(raw);
    } catch (Exception ex) {
      return objectMapper.createObjectNode();
    }
  }
}
