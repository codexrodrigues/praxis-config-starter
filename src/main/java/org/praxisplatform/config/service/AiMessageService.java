package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.AiAction;
import org.praxisplatform.config.domain.AiMessage;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AiChatMessage;
import org.praxisplatform.config.dto.AiMemoryInfo;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.repository.AiActionRepository;
import org.praxisplatform.config.repository.AiMessageRepository;
import org.praxisplatform.config.repository.AiThreadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiMessageService {

    private static final int DEFAULT_WINDOW_SIZE = 8;
    private static final int DEFAULT_SUMMARY_TRIGGER_MESSAGES = 14;
    private static final int DEFAULT_SUMMARY_MAX_CHARS = 900;
    private static final int DEFAULT_SUMMARY_SOURCE_MAX_CHARS = 6000;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?\\d{1,3}\\s*)?(\\(?\\d{2,3}\\)?\\s*)?\\d{4,5}[-\\s]?\\d{4}");
    private static final Pattern CPF_PATTERN =
            Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
    private static final Pattern CNPJ_PATTERN =
            Pattern.compile("\\b\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}\\b");
    private static final Pattern CARD_PATTERN =
            Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");

    @org.springframework.beans.factory.annotation.Value("${praxis.ai.memory.summary.enabled:true}")
    private boolean summaryEnabled;

    @org.springframework.beans.factory.annotation.Value("${praxis.ai.memory.summary.trigger-messages:14}")
    private int summaryTriggerMessages;

    @org.springframework.beans.factory.annotation.Value("${praxis.ai.memory.summary.max-chars:900}")
    private int summaryMaxChars;

    @org.springframework.beans.factory.annotation.Value("${praxis.ai.memory.summary.source-max-chars:6000}")
    private int summarySourceMaxChars;

    @org.springframework.beans.factory.annotation.Value("${praxis.ai.memory.prune.redact:true}")
    private boolean summaryRedactEnabled;

    @org.springframework.beans.factory.annotation.Value("${praxis.ai.memory.scrub.enabled:true}")
    private boolean scrubEnabled;

    private final AiThreadRepository threadRepository;
    private final AiMessageRepository messageRepository;
    private final AiActionRepository actionRepository;
    private final ObjectMapper objectMapper;
    private final AiProvider aiProvider;
    private final AiTurnService turnService;

    @Transactional
    public AiMemoryContext prepareTurn(
            AiThread thread,
            AiOrchestratorRequest request,
            String resolvedUserPrompt) {
        UUID threadId = thread.getThreadId();
        UUID turnId = request.getClientTurnId();
        if (turnId == null) {
            turnId = UUID.randomUUID();
            request.setClientTurnId(turnId);
        }

        AiTurnService.TurnDecision decision = turnService.beginTurn(threadId, turnId);
        if (decision == AiTurnService.TurnDecision.DONE) {
            AiOrchestratorResponse cached = loadCachedResponse(threadId, turnId);
            if (cached == null) {
                cached = AiOrchestratorResponse.builder()
                        .type("info")
                        .message("Turno ja processado.")
                        .build();
            }
            return new AiMemoryContext(
                    threadId,
                    turnId,
                    thread.getSummary(),
                    Collections.emptyList(),
                    DEFAULT_WINDOW_SIZE,
                    true,
                    cached);
        }
        if (decision == AiTurnService.TurnDecision.IN_PROGRESS) {
            AiOrchestratorResponse cached = AiOrchestratorResponse.builder()
                    .type("info")
                    .message("Turno em processamento.")
                    .build();
            return new AiMemoryContext(
                    threadId,
                    turnId,
                    thread.getSummary(),
                    Collections.emptyList(),
                    DEFAULT_WINDOW_SIZE,
                    true,
                    cached);
        }

        AiOrchestratorResponse cached = loadCachedResponse(threadId, turnId);
        if (cached != null) {
            turnService.completeTurn(threadId, turnId);
            return new AiMemoryContext(
                    threadId,
                    turnId,
                    thread.getSummary(),
                    Collections.emptyList(),
                    DEFAULT_WINDOW_SIZE,
                    true,
                    cached);
        }

        appendUserMessages(threadId, turnId, request, resolvedUserPrompt);
        List<AiChatMessage> window = loadWindow(threadId, DEFAULT_WINDOW_SIZE);

        return new AiMemoryContext(
                threadId,
                turnId,
                thread.getSummary(),
                window,
                DEFAULT_WINDOW_SIZE,
                false,
                null);
    }

    @Transactional
    public void storeAssistantResponse(
            AiMemoryContext memoryContext,
            AiOrchestratorResponse response) {
        if (memoryContext == null || response == null || memoryContext.isCached()) {
            return;
        }
        UUID threadId = memoryContext.getThreadId();
        UUID turnId = memoryContext.getTurnId();
        if (threadId == null || turnId == null) {
            return;
        }
        AiThread locked = threadRepository.findById(threadId).orElse(null);
        if (locked == null) {
            return;
        }
        int nextSeq = nextSeq(threadId);
        String assistantContent = buildAssistantContent(response);
        if (assistantContent != null && !assistantContent.isBlank()) {
            ScrubbedContent scrubbed = scrubContent(assistantContent);
            AiMessage message = AiMessage.builder()
                    .threadId(threadId)
                    .seq(nextSeq)
                    .role("assistant")
                    .turnId(turnId)
                    .content(scrubbed.content)
                    .redacted(scrubbed.redacted)
                    .build();
            messageRepository.save(message);
        }
        String actionType = resolveActionType(response);
        String payload = serializeResponse(response);
        if (payload != null) {
            AiAction action = AiAction.builder()
                    .threadId(threadId)
                    .turnId(turnId)
                    .actionType(actionType)
                    .payload(payload)
                    .build();
            actionRepository.save(action);
        }
        turnService.completeTurn(threadId, turnId);
    }

    public void applyMemoryMetadata(AiOrchestratorResponse response, AiMemoryContext memoryContext) {
        applyMemoryMetadata(response, memoryContext, false);
    }

    public void applyMemoryMetadata(
            AiOrchestratorResponse response,
            AiMemoryContext memoryContext,
            boolean summaryUpdated) {
        if (response == null || memoryContext == null) {
            return;
        }
        response.setSessionId(memoryContext.getThreadId());
        response.setMemory(AiMemoryInfo.builder()
                .summaryUpdated(summaryUpdated)
                .windowSize(memoryContext.getWindowSize())
                .cached(memoryContext.isCached())
                .build());
    }

    public void expireTurn(AiMemoryContext memoryContext) {
        if (memoryContext == null || memoryContext.isCached()) {
            return;
        }
        turnService.expireTurn(memoryContext.getThreadId(), memoryContext.getTurnId());
    }

    private AiOrchestratorResponse loadCachedResponse(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return null;
        }
        if (!messageRepository.existsByThreadIdAndTurnIdAndRole(threadId, turnId, "assistant")) {
            return null;
        }
        AiOrchestratorResponse cached = actionRepository.findFirstByThreadIdAndTurnId(threadId, turnId)
                .map(action -> deserializeResponse(action.getPayload()))
                .orElse(null);
        if (cached == null) {
            String assistantMessage = loadLastAssistantMessage(threadId, turnId);
            return AiOrchestratorResponse.builder()
                    .type("info")
                    .message(assistantMessage != null ? assistantMessage : "Turno ja processado.")
                    .build();
        }
        return cached;
    }

    private String loadLastAssistantMessage(UUID threadId, UUID turnId) {
        List<AiMessage> messages = messageRepository.findByThreadIdAndTurnIdAndRoleOrderBySeqDesc(
                threadId,
                turnId,
                "assistant");
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (AiMessage msg : messages) {
            if (msg.getContent() != null && !msg.getContent().isBlank()) {
                return msg.getContent();
            }
        }
        return null;
    }

    private void appendUserMessages(
            UUID threadId,
            UUID turnId,
            AiOrchestratorRequest request,
            String resolvedUserPrompt) {
        if (threadId == null || request == null) {
            return;
        }
        if (messageRepository.existsByThreadIdAndTurnIdAndRole(threadId, turnId, "user")) {
            return;
        }
        threadRepository.findById(threadId).orElse(null);
        List<AiChatMessage> incoming = request.getMessages() != null
                ? request.getMessages()
                : List.of();
        List<AiChatMessage> toPersist = new ArrayList<>();
        for (AiChatMessage msg : incoming) {
            if (msg == null) continue;
            String role = msg.getRole() != null ? msg.getRole().toLowerCase(Locale.ROOT) : "user";
            if (!"user".equals(role)) {
                continue;
            }
            if (msg.getContent() == null || msg.getContent().isBlank()) {
                continue;
            }
            toPersist.add(msg);
        }
        if (toPersist.isEmpty() && resolvedUserPrompt != null && !resolvedUserPrompt.isBlank()) {
            toPersist.add(AiChatMessage.builder()
                    .role("user")
                    .content(resolvedUserPrompt)
                    .build());
        }
        if (toPersist.isEmpty()) {
            return;
        }
        int seq = nextSeq(threadId);
        for (AiChatMessage msg : toPersist) {
            ScrubbedContent scrubbed = scrubContent(msg.getContent());
            AiMessage message = AiMessage.builder()
                    .threadId(threadId)
                    .seq(seq++)
                    .role("user")
                    .turnId(turnId)
                    .content(scrubbed.content)
                    .redacted(scrubbed.redacted)
                    .build();
            messageRepository.save(message);
        }
    }

    private int nextSeq(UUID threadId) {
        Integer maxSeq = messageRepository.findMaxSeqByThreadId(threadId);
        return (maxSeq != null ? maxSeq : 0) + 1;
    }

    private List<AiChatMessage> loadWindow(UUID threadId, int windowSize) {
        if (threadId == null || windowSize <= 0) {
            return Collections.emptyList();
        }
        List<AiMessage> recent = messageRepository.findRecentByThreadId(
                threadId,
                PageRequest.of(0, windowSize));
        if (recent == null || recent.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiChatMessage> window = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            AiMessage message = recent.get(i);
            window.add(AiChatMessage.builder()
                    .role(message.getRole())
                    .content(message.getContent())
                    .build());
        }
        return window;
    }

    private String serializeResponse(AiOrchestratorResponse response) {
        try {
            return objectMapper.valueToTree(response).toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private AiOrchestratorResponse deserializeResponse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, AiOrchestratorResponse.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveActionType(AiOrchestratorResponse response) {
        String type = response.getType();
        if (type == null || type.isBlank()) {
            return "INFO";
        }
        return type.trim().toUpperCase(Locale.ROOT);
    }

    private String buildAssistantContent(AiOrchestratorResponse response) {
        if (response.getMessage() != null && !response.getMessage().isBlank()) {
            return response.getMessage();
        }
        if (response.getExplanation() != null && !response.getExplanation().isBlank()) {
            return response.getExplanation();
        }
        if (response.getType() != null && !response.getType().isBlank()) {
            return response.getType();
        }
        return null;
    }

    @Transactional
    public boolean summarizeIfNeeded(AiMemoryContext memoryContext) {
        if (memoryContext == null || memoryContext.isCached()) {
            return false;
        }
        if (!summaryEnabled) {
            return false;
        }
        UUID threadId = memoryContext.getThreadId();
        if (threadId == null) {
            return false;
        }
        long total = messageRepository.countByThreadId(threadId);
        int trigger = summaryTriggerMessages > 0 ? summaryTriggerMessages : DEFAULT_SUMMARY_TRIGGER_MESSAGES;
        if (total <= trigger) {
            return false;
        }
        Integer maxSeq = messageRepository.findMaxSeqByThreadId(threadId);
        if (maxSeq == null || maxSeq <= DEFAULT_WINDOW_SIZE) {
            return false;
        }
        int cutoffSeq = maxSeq - DEFAULT_WINDOW_SIZE;
        List<AiMessage> toSummarize = messageRepository.findByThreadIdAndSeqLessThanEqualOrderBySeqAsc(
                threadId,
                cutoffSeq);
        if (toSummarize == null || toSummarize.isEmpty()) {
            return false;
        }
        boolean hasFresh = false;
        for (AiMessage msg : toSummarize) {
            if (!msg.isRedacted() && msg.getContent() != null && !msg.getContent().isBlank()) {
                hasFresh = true;
                break;
            }
        }
        if (!hasFresh) {
            return false;
        }
        String transcript = buildTranscript(toSummarize);
        if (transcript.isBlank()) {
            return false;
        }
        AiThread thread = threadRepository.findById(threadId).orElse(null);
        if (thread == null) {
            return false;
        }
        String summaryPrompt = buildSummaryPrompt(thread.getSummary(), transcript);
        String summary;
        try {
            summary = aiProvider.generateText(summaryPrompt);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(AiMessageService.class)
                    .warn("[AiMessageService] Summary generation failed: {}", ex.getMessage());
            return false;
        }
        if (summary == null || summary.isBlank()) {
            return false;
        }
        summary = normalizeSummary(summary);
        thread.setSummary(summary);
        threadRepository.save(thread);
        if (summaryRedactEnabled) {
            messageRepository.redactMessagesUpToSeq(threadId, cutoffSeq);
        }
        return true;
    }

    private String buildTranscript(List<AiMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (AiMessage msg : messages) {
            if (msg == null || msg.getContent() == null || msg.getContent().isBlank()) {
                continue;
            }
            sb.append(msg.getRole() != null ? msg.getRole() : "user")
                    .append(": ")
                    .append(msg.getContent().trim())
                    .append("\n");
            int limit = summarySourceMaxChars > 0 ? summarySourceMaxChars : DEFAULT_SUMMARY_SOURCE_MAX_CHARS;
            if (sb.length() >= limit) {
                break;
            }
        }
        return sb.toString().trim();
    }

    private String buildSummaryPrompt(String previousSummary, String transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("Resuma a conversa abaixo em bullets com o formato:\n")
                .append("- objetivo:\n")
                .append("- decisoes:\n")
                .append("- restricoes:\n")
                .append("- pendencias:\n\n")
                .append("Use frases curtas em portugues, sem dados sensiveis. Limite 900 caracteres.\n\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("Resumo anterior:\n")
                    .append(previousSummary.trim())
                    .append("\n\n");
        }
        sb.append("Mensagens:\n").append(transcript);
        return sb.toString();
    }

    private String normalizeSummary(String summary) {
        String trimmed = summary.trim();
        int limit = summaryMaxChars > 0 ? summaryMaxChars : DEFAULT_SUMMARY_MAX_CHARS;
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit);
    }

    private ScrubbedContent scrubContent(String content) {
        if (content == null || content.isBlank()) {
            return new ScrubbedContent(content, false);
        }
        if (!scrubEnabled) {
            return new ScrubbedContent(content, false);
        }
        String scrubbed = content;
        scrubbed = EMAIL_PATTERN.matcher(scrubbed).replaceAll("[REDACTED_EMAIL]");
        scrubbed = PHONE_PATTERN.matcher(scrubbed).replaceAll("[REDACTED_PHONE]");
        scrubbed = CPF_PATTERN.matcher(scrubbed).replaceAll("[REDACTED_CPF]");
        scrubbed = CNPJ_PATTERN.matcher(scrubbed).replaceAll("[REDACTED_CNPJ]");
        scrubbed = redactCardNumbers(scrubbed);
        boolean redacted = !scrubbed.equals(content);
        return new ScrubbedContent(scrubbed, redacted);
    }

    private String redactCardNumbers(String input) {
        Matcher matcher = CARD_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        boolean replaced = false;
        while (matcher.find()) {
            String candidate = matcher.group();
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() >= 13 && digits.length() <= 19 && passesLuhn(digits)) {
                matcher.appendReplacement(sb, "[REDACTED_CARD]");
                replaced = true;
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(candidate));
            }
        }
        if (!replaced) {
            return input;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean passesLuhn(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private static final class ScrubbedContent {
        private final String content;
        private final boolean redacted;

        private ScrubbedContent(String content, boolean redacted) {
            this.content = content;
            this.redacted = redacted;
        }
    }
}
