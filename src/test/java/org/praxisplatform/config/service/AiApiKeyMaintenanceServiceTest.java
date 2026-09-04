package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.dto.AiApiKeyClearRequest;
import org.praxisplatform.config.repository.UiUserConfigRepository;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiApiKeyMaintenanceServiceTest {

    @Mock private UiUserConfigRepository repository;
    @Mock private UserConfigService userConfigService;
    @Mock private AiApiKeyCryptoService cryptoService;

    @Test
    void payloadMaintenanceInvalidatesPreviousSemanticSource() {
        UiUserConfig current = UiUserConfig.builder()
                .id(UUID.fromString("123e4567-e89b-12d3-a456-426614174050"))
                .tenantId("tenant-a")
                .componentType("praxis-dynamic-page")
                .componentId("page")
                .payload("{\"ai\":{\"apiKeyEncrypted\":\"cipher\",\"apiKeyLast4\":\"1234\"},\"widgets\":[]}")
                .authoringSource("{\"schemaVersion\":\"praxis.ui-authoring-source/v1\"}")
                .version(3L)
                .etag(UUID.fromString("123e4567-e89b-12d3-a456-426614174051"))
                .build();
        when(userConfigService.getByScope(
                UserConfigService.Scope.TENANT,
                "tenant-a",
                null,
                "praxis-dynamic-page",
                "page",
                null))
                .thenReturn(Optional.of(
                        new UserConfigService.ResolvedConfig(current, UserConfigService.Scope.TENANT)));
        AiApiKeyMaintenanceService service = new AiApiKeyMaintenanceService(
                repository,
                userConfigService,
                new ObjectMapper(),
                cryptoService);

        service.clearApiKey(
                AiApiKeyClearRequest.builder()
                        .componentType("praxis-dynamic-page")
                        .componentId("page")
                        .scope("tenant")
                        .build(),
                "tenant-a",
                null,
                "security-admin");

        assertThat(current.getAuthoringSource()).isNull();
        assertThat(current.getVersion()).isEqualTo(4L);
        assertThat(current.getPayload()).isEqualTo("{\"widgets\":[]}");
        verify(repository).save(current);
    }
}
