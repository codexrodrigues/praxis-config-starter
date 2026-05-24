package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.domain.AiThreadStatus;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.repository.AiActionRepository;
import org.praxisplatform.config.repository.AiMessageRepository;
import org.praxisplatform.config.repository.AiThreadRepository;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiMessageServiceTest {

    @Mock
    private AiThreadRepository threadRepository;
    @Mock
    private AiMessageRepository messageRepository;
    @Mock
    private AiActionRepository actionRepository;
    @Mock
    private AiProvider aiProvider;
    @Mock
    private AiTurnService turnService;

    private AiMessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new AiMessageService(
                threadRepository,
                messageRepository,
                actionRepository,
                new ObjectMapper(),
                aiProvider,
                turnService);
    }

    @Test
    void shouldNotCallBeginTurnWhenStreamTurnIsPreclaimed() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .status(AiThreadStatus.ACTIVE)
                .summary("")
                .createdAt(Instant.now())
                .lastUsedAt(Instant.now())
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .clientTurnId(turnId)
                .userPrompt("Atualizar tabela")
                .streamTransport(Boolean.TRUE)
                .streamTurnPreclaimed(Boolean.TRUE)
                .build();

        when(messageRepository.existsByThreadIdAndTurnIdAndRole(threadId, turnId, "assistant")).thenReturn(false);
        when(messageRepository.existsByThreadIdAndTurnIdAndRole(threadId, turnId, "user")).thenReturn(false);
        when(messageRepository.findMaxSeqByThreadId(threadId)).thenReturn(null);
        when(messageRepository.findRecentByThreadId(eq(threadId), any(Pageable.class))).thenReturn(List.of());
        when(threadRepository.findById(threadId)).thenReturn(Optional.of(thread));

        AiMemoryContext memoryContext = messageService.prepareTurn(thread, request, "Atualizar tabela");

        assertThat(memoryContext).isNotNull();
        assertThat(memoryContext.isCached()).isFalse();
        assertThat(memoryContext.isDeferTurnCompletion()).isTrue();
        verify(turnService, never()).beginTurn(threadId, turnId);
    }

    @Test
    void shouldReturnCanonicalCodeWhenTurnIsAlreadyInProgress() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .status(AiThreadStatus.ACTIVE)
                .summary("")
                .createdAt(Instant.now())
                .lastUsedAt(Instant.now())
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .clientTurnId(turnId)
                .userPrompt("Atualizar tabela")
                .build();

        when(turnService.beginTurn(threadId, turnId)).thenReturn(AiTurnService.TurnDecision.IN_PROGRESS);

        AiMemoryContext memoryContext = messageService.prepareTurn(thread, request, "Atualizar tabela");

        assertThat(memoryContext).isNotNull();
        assertThat(memoryContext.isCached()).isTrue();
        assertThat(memoryContext.getCachedResponse()).isNotNull();
        assertThat(memoryContext.getCachedResponse().getType()).isEqualTo("info");
        assertThat(memoryContext.getCachedResponse().getCode()).isEqualTo("TURN_IN_PROGRESS");
        assertThat(memoryContext.getCachedResponse().getMessage()).isEqualTo("Turno em processamento.");
    }

    @Test
    void shouldDeferTurnCompletionWhenMemoryContextRequiresIt() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiThread thread = AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .userId("user-a")
                .environment("prod")
                .componentId("praxis-table")
                .componentType("praxis-table")
                .status(AiThreadStatus.ACTIVE)
                .summary("")
                .createdAt(Instant.now())
                .lastUsedAt(Instant.now())
                .build();
        AiMemoryContext memoryContext = new AiMemoryContext(
                threadId,
                turnId,
                "",
                List.of(),
                8,
                false,
                null,
                true);
        AiOrchestratorResponse response = AiOrchestratorResponse.builder()
                .type("patch")
                .message("ok")
                .build();

        when(threadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(messageRepository.findMaxSeqByThreadId(threadId)).thenReturn(null);

        messageService.storeAssistantResponse(memoryContext, response);

        verify(turnService, never()).completeTurn(threadId, turnId);
    }
}
