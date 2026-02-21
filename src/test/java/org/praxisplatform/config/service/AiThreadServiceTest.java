package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.repository.AiThreadRepository;

@ExtendWith(MockitoExtension.class)
class AiThreadServiceTest {

    @Mock
    private AiThreadRepository threadRepository;

    private final Map<UUID, AiThread> storage = new ConcurrentHashMap<>();
    private AiThreadService threadService;

    @BeforeEach
    void setUp() {
        threadService = new AiThreadService(threadRepository);
        storage.clear();
        when(threadRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(storage.get(invocation.getArgument(0))));
        when(threadRepository.save(any(AiThread.class))).thenAnswer(invocation -> {
            AiThread entity = invocation.getArgument(0, AiThread.class);
            storage.put(entity.getThreadId(), entity);
            return entity;
        });
    }

    @Test
    void shouldReuseDeterministicThreadForRetryWithoutSessionId() {
        UUID clientTurnId = UUID.randomUUID();
        AiOrchestratorRequest firstRequest = baseRequest(clientTurnId);
        AiOrchestratorRequest retryRequest = AiOrchestratorRequest.builder()
                .componentId("praxis-grid")
                .componentType("table")
                .variantId("retry-variant")
                .userPrompt("Atualizar tabela")
                .clientTurnId(clientTurnId)
                .build();

        AiThread created = threadService.resolveThread(
                firstRequest,
                "tenant-a",
                "user-a",
                "prod",
                "Atualizar tabela");
        AiThread retried = threadService.resolveThread(
                retryRequest,
                "tenant-a",
                "user-a",
                "prod",
                "Atualizar tabela");

        assertThat(created.getThreadId()).isEqualTo(retried.getThreadId());
        assertThat(retryRequest.getSessionId()).isEqualTo(created.getThreadId());
        assertThat(storage).hasSize(1);
    }

    private AiOrchestratorRequest baseRequest(UUID clientTurnId) {
        return AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(clientTurnId)
                .build();
    }
}
