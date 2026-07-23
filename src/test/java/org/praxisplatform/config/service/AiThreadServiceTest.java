package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
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
        lenient().when(threadRepository.save(any(AiThread.class))).thenAnswer(invocation -> {
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

    @Test
    void shouldRetryDeterministicCreationAfterTransientDatabaseConnectionFailure() {
        UUID clientTurnId = UUID.randomUUID();
        AiOrchestratorRequest request = baseRequest(clientTurnId);
        when(threadRepository.save(any(AiThread.class)))
                .thenThrow(new DataAccessResourceFailureException("temporary connection loss"))
                .thenAnswer(invocation -> {
                    AiThread entity = invocation.getArgument(0, AiThread.class);
                    storage.put(entity.getThreadId(), entity);
                    return entity;
                });

        AiThread created = threadService.resolveThread(
                request,
                "tenant-a",
                "user-a",
                "prod",
                "Atualizar tabela");

        assertThat(request.getSessionId()).isEqualTo(created.getThreadId());
        assertThat(storage).containsOnlyKeys(created.getThreadId());
        verify(threadRepository, times(2)).save(any(AiThread.class));
    }

    @Test
    void shouldResolveCommittedDeterministicThreadWhenTheFirstInsertResponseTimesOut() {
        UUID clientTurnId = UUID.randomUUID();
        AiOrchestratorRequest request = baseRequest(clientTurnId);
        when(threadRepository.save(any(AiThread.class)))
                .thenAnswer(invocation -> {
                    AiThread entity = invocation.getArgument(0, AiThread.class);
                    storage.put(entity.getThreadId(), entity);
                    throw new DataAccessResourceFailureException("commit outcome was not observed");
                })
                .thenAnswer(invocation -> invocation.getArgument(0, AiThread.class));

        AiThread resolved = threadService.resolveThread(
                request,
                "tenant-a",
                "user-a",
                "prod",
                "Atualizar tabela");

        assertThat(request.getSessionId()).isEqualTo(resolved.getThreadId());
        assertThat(storage).containsOnlyKeys(resolved.getThreadId());
        verify(threadRepository, times(2)).save(any(AiThread.class));
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
