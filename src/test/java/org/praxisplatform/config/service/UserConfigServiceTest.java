package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
}
