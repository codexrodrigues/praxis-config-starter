package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.AiAssistantObservation;
import org.praxisplatform.config.domain.AiAssistantObservationFeedback;
import org.praxisplatform.config.dto.AiAssistantObservationFeedbackRequest;
import org.praxisplatform.config.dto.AiAssistantObservationFeedbackResponse;
import org.praxisplatform.config.dto.AiAssistantObservationResponse;
import org.praxisplatform.config.dto.AiAssistantObservationSummaryResponse;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.repository.AiAssistantObservationFeedbackRepository;
import org.praxisplatform.config.repository.AiAssistantObservationRepository;
import org.praxisplatform.config.repository.AiAssistantObservationRepository.AiAssistantObservationSummaryRow;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiAssistantObservationService {

    public static final String SURFACE_SYNC_PATCH = "sync_patch";
    public static final String SURFACE_PATCH_STREAM = "patch_stream";
    public static final String SURFACE_AGENTIC_AUTHORING_STREAM = "agentic_authoring_stream";

    private static final Set<String> FEEDBACK_RATINGS = Set.of(
            "positive", "negative", "inaccurate", "unsafe", "irrelevant", "incomplete");
    private static final int PREVIEW_LIMIT = 360;
    private static final int ERROR_PREVIEW_LIMIT = 500;

    private final AiAssistantObservationRepository observationRepository;
    private final AiAssistantObservationFeedbackRepository feedbackRepository;
    private final AiSensitiveDataRedactor sensitiveDataRedactor;
    private final ObjectMapper objectMapper;

    @Value("${praxis.ai.observability.enabled:true}")
    private boolean enabled;

    @Value("${praxis.ai.observability.hash-secret:praxis-ai-observation-v1}")
    private String hashSecret;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public UUID capture(
            AiOrchestratorRequest request,
            String tenantId,
            String userId,
            String environment,
            String surface,
            String prompt) {
        if (!enabled || request == null) {
            return null;
        }
        if (request.getObservationId() != null) {
            return request.getObservationId();
        }
        String normalizedPrompt = normalizePrompt(prompt);
        AiAssistantObservation observation = AiAssistantObservation.builder()
                .observationId(UUID.randomUUID())
                .tenantId(required(tenantId, "tenantId"))
                .userId(clean(userId))
                .environment(clean(environment))
                .requestId(clean(MDC.get("requestId")))
                .surface(nonBlank(surface, SURFACE_SYNC_PATCH))
                .componentId(clean(request.getComponentId()))
                .componentType(clean(request.getComponentType()))
                .routeKey(clean(request.getResourcePath()))
                .variantId(clean(request.getVariantId()))
                .schemaHash(clean(request.getSchemaHash()))
                .contractVersion(clean(request.getContractVersion()))
                .sessionId(request.getSessionId())
                .clientTurnId(request.getClientTurnId())
                .threadId(request.getSessionId())
                .turnId(request.getClientTurnId())
                .promptHash(hashPrompt(tenantId, normalizedPrompt))
                .promptPreview(promptPreview(normalizedPrompt))
                .promptLength(normalizedPrompt != null ? normalizedPrompt.length() : 0)
                .promptRedacted(true)
                .admissionOutcome("captured")
                .qualityOutcome("unresolved")
                .safeMetadata(buildPromptMetadata(normalizedPrompt))
                .build();
        try {
            observationRepository.saveAndFlush(observation);
            request.setObservationId(observation.getObservationId());
            return observation.getObservationId();
        } catch (DataAccessException ex) {
            logObservationSkipped("capture", ex);
            return null;
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public UUID captureStream(
            AiOrchestratorRequest request,
            AiPrincipalContext principalContext,
            String surface,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String prompt) {
        UUID observationId = capture(
                request,
                principalContext != null ? principalContext.tenantId() : null,
                principalContext != null ? principalContext.userId() : null,
                principalContext != null ? principalContext.environment() : null,
                surface,
                prompt);
        link(observationId, threadId, turnId, streamId);
        return observationId;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public void markAdmission(UUID observationId, String admissionOutcome, String code, String message) {
        if (observationId == null) {
            return;
        }
        try {
            observationRepository.findById(observationId).ifPresent(observation -> {
                observation.setAdmissionOutcome(nonBlank(admissionOutcome, "allowed"));
                if (code != null && !code.isBlank()) {
                    observation.setErrorCode(clean(code));
                }
                if (message != null && !message.isBlank()) {
                    observation.setErrorMessagePreview(preview(message, ERROR_PREVIEW_LIMIT));
                }
                if (!"allowed".equalsIgnoreCase(observation.getAdmissionOutcome())) {
                    observation.setTerminalOutcome("no_llm_call");
                }
                observationRepository.save(observation);
            });
        } catch (DataAccessException ex) {
            logObservationSkipped("markAdmission", ex);
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public void markCompleted(UUID observationId, AiOrchestratorResponse response, long latencyMs) {
        if (observationId == null) {
            return;
        }
        try {
            observationRepository.findById(observationId).ifPresent(observation -> {
                observation.setAdmissionOutcome(nonBlank(observation.getAdmissionOutcome(), "allowed"));
                if (!"no_llm_call".equalsIgnoreCase(observation.getTerminalOutcome())) {
                    observation.setTerminalOutcome(resolveTerminalOutcome(response));
                }
                if (latencyMs > 0) {
                    observation.setLatencyMs(latencyMs);
                }
                if (response != null) {
                    observation.setErrorCode(clean(response.getCode()));
                    if ("error".equalsIgnoreCase(response.getType())) {
                        observation.setErrorCategory("orchestrator");
                        observation.setErrorMessagePreview(preview(response.getMessage(), ERROR_PREVIEW_LIMIT));
                    }
                }
                observationRepository.save(observation);
            });
        } catch (DataAccessException ex) {
            logObservationSkipped("markCompleted", ex);
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public void markFailed(UUID observationId, Throwable error, long latencyMs) {
        if (observationId == null) {
            return;
        }
        try {
            observationRepository.findById(observationId).ifPresent(observation -> {
                observation.setTerminalOutcome("error");
                observation.setQualityOutcome(nonBlank(observation.getQualityOutcome(), "needs_review"));
                observation.setErrorCategory(error != null ? error.getClass().getSimpleName() : "unknown");
                observation.setErrorMessagePreview(preview(error != null ? error.getMessage() : null, ERROR_PREVIEW_LIMIT));
                observation.setLatencyMs(Math.max(latencyMs, 0L));
                observationRepository.save(observation);
            });
        } catch (DataAccessException ex) {
            logObservationSkipped("markFailed", ex);
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public void recordLlmCall(UUID observationId, String provider, String model, long durationMs, Throwable error) {
        recordLlmCall(observationId, provider, model, null, null, durationMs, error);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public void recordLlmCall(
            UUID observationId,
            String provider,
            String model,
            String callType,
            Integer promptLength,
            long durationMs,
            Throwable error) {
        if (observationId == null) {
            return;
        }
        try {
            observationRepository.findById(observationId).ifPresent(observation -> {
                observation.setLlmCallCount(observation.getLlmCallCount() + 1);
                observation.setProvider(clean(provider));
                observation.setModel(clean(model));
                observation.setLatencyMs(Math.max(durationMs, 0L));
                observation.setSafeMetadata(appendLlmCallMetadata(
                        observation.getSafeMetadata(),
                        callType,
                        promptLength,
                        durationMs,
                        provider,
                        model,
                        error));
                if (error != null) {
                    observation.setErrorCategory(error.getClass().getSimpleName());
                    observation.setErrorMessagePreview(preview(error.getMessage(), ERROR_PREVIEW_LIMIT));
                }
                observationRepository.save(observation);
            });
        } catch (DataAccessException ex) {
            logObservationSkipped("recordLlmCall", ex);
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public void markTerminal(UUID observationId, String terminalOutcome, String code, String message) {
        if (observationId == null) {
            return;
        }
        try {
            observationRepository.findById(observationId).ifPresent(observation -> {
                if ("captured".equalsIgnoreCase(observation.getAdmissionOutcome())) {
                    observation.setAdmissionOutcome("allowed");
                }
                observation.setTerminalOutcome(nonBlank(terminalOutcome, "result"));
                if (code != null && !code.isBlank()) {
                    observation.setErrorCode(clean(code));
                }
                if (message != null && !message.isBlank()) {
                    observation.setErrorMessagePreview(preview(message, ERROR_PREVIEW_LIMIT));
                }
                if ("error".equalsIgnoreCase(terminalOutcome) || "timeout".equalsIgnoreCase(terminalOutcome)) {
                    observation.setQualityOutcome("needs_review");
                    observation.setErrorCategory(nonBlank(observation.getErrorCategory(), "stream"));
                }
                observationRepository.save(observation);
            });
        } catch (DataAccessException ex) {
            logObservationSkipped("markTerminal", ex);
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public void link(UUID observationId, UUID threadId, UUID turnId, UUID streamId) {
        if (observationId == null) {
            return;
        }
        try {
            observationRepository.findById(observationId).ifPresent(observation -> {
                if (threadId != null) {
                    observation.setThreadId(threadId);
                    observation.setSessionId(threadId);
                }
                if (turnId != null) {
                    observation.setTurnId(turnId);
                    observation.setClientTurnId(turnId);
                }
                if (streamId != null) {
                    observation.setStreamId(streamId);
                }
                observationRepository.save(observation);
            });
        } catch (DataAccessException ex) {
            logObservationSkipped("link", ex);
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.NOT_SUPPORTED)
    public Optional<UUID> findObservationId(UUID threadId, UUID turnId, UUID streamId) {
        if (streamId != null) {
            Optional<AiAssistantObservation> byStream = findByStreamId(streamId);
            if (byStream.isPresent()) {
                return byStream.map(AiAssistantObservation::getObservationId);
            }
        }
        if (threadId != null && turnId != null) {
            return findByThreadIdAndTurnId(threadId, turnId)
                    .map(AiAssistantObservation::getObservationId);
        }
        return Optional.empty();
    }

    private Optional<AiAssistantObservation> findByStreamId(UUID streamId) {
        try {
            return observationRepository.findFirstByStreamIdOrderByCreatedAtDesc(streamId);
        } catch (DataAccessException ex) {
            logObservationSkipped("findByStreamId", ex);
            return Optional.empty();
        }
    }

    private Optional<AiAssistantObservation> findByThreadIdAndTurnId(UUID threadId, UUID turnId) {
        try {
            return observationRepository.findFirstByThreadIdAndTurnIdOrderByCreatedAtDesc(threadId, turnId);
        } catch (DataAccessException ex) {
            logObservationSkipped("findByThreadIdAndTurnId", ex);
            return Optional.empty();
        }
    }

    private void logObservationSkipped(String operation, DataAccessException ex) {
        log.warn("[AiAssistantObservationService] Observation {} skipped: {}", operation, ex.getMessage());
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<AiAssistantObservationResponse> search(
            AiPrincipalContext principalContext,
            Instant from,
            Instant to,
            String surface,
            String componentId,
            String componentType,
            String admissionOutcome,
            String terminalOutcome,
            String qualityOutcome,
            String promptHash,
            Boolean hasFeedback,
            int limit) {
        AiPrincipalContext principal = requirePrincipal(principalContext);
        return observationRepository.search(
                        principal.tenantId(),
                        principal.environment(),
                        from,
                        to,
                        clean(surface),
                        clean(componentId),
                        clean(componentType),
                        clean(admissionOutcome),
                        clean(terminalOutcome),
                        clean(qualityOutcome),
                        clean(promptHash),
                        hasFeedback,
                        PageRequest.of(0, normalizeLimit(limit)))
                .stream()
                .map(observation -> toResponse(observation, false))
                .toList();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public AiAssistantObservationResponse get(UUID observationId, AiPrincipalContext principalContext) {
        AiPrincipalContext principal = requirePrincipal(principalContext);
        AiAssistantObservation observation = observationRepository.findById(observationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Observation not found."));
        if (!sameScope(observation, principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Observation access denied.");
        }
        return toResponse(observation, true);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public AiAssistantObservationSummaryResponse summarize(
            AiPrincipalContext principalContext,
            Instant from,
            Instant to,
            int limit) {
        AiPrincipalContext principal = requirePrincipal(principalContext);
        List<AiAssistantObservationSummaryResponse.Row> rows = observationRepository.summarize(
                        principal.tenantId(),
                        principal.environment(),
                        from,
                        to,
                        PageRequest.of(0, normalizeLimit(limit)))
                .stream()
                .map(this::toSummaryRow)
                .toList();
        return new AiAssistantObservationSummaryResponse(rows);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public AiAssistantObservationFeedbackResponse attachFeedback(
            UUID observationId,
            AiAssistantObservationFeedbackRequest request,
            AiPrincipalContext principalContext) {
        AiPrincipalContext principal = requirePrincipal(principalContext);
        AiAssistantObservation observation = observationRepository.findById(observationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Observation not found."));
        if (!sameScope(observation, principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Observation access denied.");
        }
        String rating = normalizeRating(request != null ? request.rating() : null);
        AiAssistantObservationFeedback feedback = AiAssistantObservationFeedback.builder()
                .feedbackId(UUID.randomUUID())
                .observation(observation)
                .tenantId(principal.tenantId())
                .environment(principal.environment())
                .userId(principal.userId())
                .rating(rating)
                .reasonCode(clean(request != null ? request.reasonCode() : null))
                .commentPreview(preview(request != null ? request.comment() : null, PREVIEW_LIMIT))
                .safeMetadata("{}")
                .build();
        AiAssistantObservationFeedback saved = feedbackRepository.save(feedback);
        observation.setQualityOutcome(resolveQualityOutcome(rating));
        observationRepository.save(observation);
        return toFeedbackResponse(saved);
    }

    private AiAssistantObservationResponse toResponse(AiAssistantObservation observation, boolean includeFeedback) {
        List<AiAssistantObservationFeedbackResponse> feedback = includeFeedback
                ? feedbackRepository.findByObservation_ObservationIdOrderByCreatedAtDesc(observation.getObservationId())
                        .stream()
                        .map(this::toFeedbackResponse)
                        .toList()
                : List.of();
        return new AiAssistantObservationResponse(
                observation.getObservationId(),
                observation.getRequestId(),
                observation.getTenantId(),
                observation.getEnvironment(),
                observation.getUserId(),
                observation.getSurface(),
                observation.getComponentId(),
                observation.getComponentType(),
                observation.getRouteKey(),
                observation.getVariantId(),
                observation.getSchemaHash(),
                observation.getContractVersion(),
                observation.getSessionId(),
                observation.getClientTurnId(),
                observation.getThreadId(),
                observation.getTurnId(),
                observation.getStreamId(),
                observation.getPromptHash(),
                observation.getPromptPreview(),
                observation.getPromptLength(),
                observation.getAdmissionOutcome(),
                observation.getTerminalOutcome(),
                observation.getQualityOutcome(),
                observation.getErrorCategory(),
                observation.getErrorCode(),
                observation.getErrorMessagePreview(),
                observation.getProvider(),
                observation.getModel(),
                observation.getLlmCallCount(),
                observation.getLatencyMs(),
                observation.getCreatedAt(),
                observation.getUpdatedAt(),
                feedback);
    }

    private AiAssistantObservationFeedbackResponse toFeedbackResponse(AiAssistantObservationFeedback feedback) {
        return new AiAssistantObservationFeedbackResponse(
                feedback.getFeedbackId(),
                feedback.getObservation() != null ? feedback.getObservation().getObservationId() : null,
                feedback.getRating(),
                feedback.getReasonCode(),
                feedback.getCommentPreview(),
                feedback.getCreatedAt());
    }

    private AiAssistantObservationSummaryResponse.Row toSummaryRow(AiAssistantObservationSummaryRow row) {
        return new AiAssistantObservationSummaryResponse.Row(
                row.getAdmissionOutcome(),
                row.getTerminalOutcome(),
                row.getQualityOutcome(),
                row.getComponentId(),
                row.getComponentType(),
                row.getTotal());
    }

    private String resolveTerminalOutcome(AiOrchestratorResponse response) {
        if (response == null) {
            return "error";
        }
        if ("error".equalsIgnoreCase(response.getType())) {
            return "error";
        }
        return "result";
    }

    private String resolveQualityOutcome(String rating) {
        return switch (rating) {
            case "positive" -> "user_positive";
            case "negative", "inaccurate", "unsafe", "irrelevant", "incomplete" -> "user_negative";
            default -> "needs_review";
        };
    }

    private String normalizeRating(String rating) {
        String normalized = clean(rating);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating is required.");
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!FEEDBACK_RATINGS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating is not supported.");
        }
        return normalized;
    }

    private AiPrincipalContext requirePrincipal(AiPrincipalContext principalContext) {
        if (principalContext == null || principalContext.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identity context is required.");
        }
        return principalContext;
    }

    private boolean sameScope(AiAssistantObservation observation, AiPrincipalContext principal) {
        return observation != null
                && principal != null
                && safeEquals(observation.getTenantId(), principal.tenantId())
                && safeEquals(observation.getEnvironment(), principal.environment());
    }

    private String buildPromptMetadata(String prompt) {
        ObjectNode node = objectMapper.createObjectNode();
        String value = prompt != null ? prompt : "";
        node.put("schemaVersion", "praxis-ai-assistant-observation-metadata.v1");
        node.put("containsSecretLike", containsAny(value, "token", "secret", "api key", "apikey", "senha", "password"));
        node.put("containsEmailLike", value.contains("@"));
        node.put("containsUrlLike", value.contains("http://") || value.contains("https://"));
        node.put("containsPromptInjectionLike", containsAny(value.toLowerCase(Locale.ROOT),
                "ignore as instrucoes", "system prompt", "developer message", "jailbreak", "bypass"));
        return node.toString();
    }

    private String appendLlmCallMetadata(
            String safeMetadata,
            String callType,
            Integer promptLength,
            long durationMs,
            String provider,
            String model,
            Throwable error) {
        ObjectNode root = parseSafeMetadataObject(safeMetadata);
        ObjectNode call = objectMapper.createObjectNode();
        call.put("callType", nonBlank(callType, "unknown"));
        call.put("durationMs", Math.max(durationMs, 0L));
        if (promptLength != null && promptLength >= 0) {
            call.put("promptLength", promptLength);
        }
        String cleanProvider = clean(provider);
        if (cleanProvider != null) {
            call.put("provider", cleanProvider);
        }
        String cleanModel = clean(model);
        if (cleanModel != null) {
            call.put("model", cleanModel);
        }
        if (error != null) {
            call.put("errorCategory", error.getClass().getSimpleName());
            String message = preview(error.getMessage(), ERROR_PREVIEW_LIMIT);
            if (message != null) {
                call.put("errorMessagePreview", message);
            }
        }
        root.set("lastLlmCall", call);
        ArrayNode calls = root.withArray("llmCalls");
        calls.add(call.deepCopy());
        while (calls.size() > 8) {
            calls.remove(0);
        }
        return root.toString();
    }

    private ObjectNode parseSafeMetadataObject(String safeMetadata) {
        if (safeMetadata != null && !safeMetadata.isBlank()) {
            try {
                var parsed = objectMapper.readTree(safeMetadata);
                if (parsed != null && parsed.isObject()) {
                    return (ObjectNode) parsed;
                }
            } catch (Exception ignored) {
                // Observation metadata is best-effort; never fail the user flow because of telemetry.
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", "praxis-ai-assistant-observation-metadata.v1");
        return root;
    }

    private String promptPreview(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        if (containsAny(prompt.toLowerCase(Locale.ROOT), "api key", "apikey", "token", "secret", "password", "senha")) {
            return "[redacted: secret-like content detected]";
        }
        return preview(prompt, PREVIEW_LIMIT);
    }

    private String preview(String value, int limit) {
        String cleaned = cleanControlChars(sensitiveDataRedactor.redactText(value));
        if (cleaned == null) {
            return null;
        }
        int safeLimit = Math.max(16, limit);
        if (cleaned.length() <= safeLimit) {
            return cleaned;
        }
        return cleaned.substring(0, safeLimit) + "... [truncated]";
    }

    private String hashPrompt(String tenantId, String prompt) {
        String normalized = normalizePrompt(prompt);
        String material = nonBlank(tenantId, "unknown") + ":" + nonBlank(normalized, "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(nonBlank(hashSecret, "praxis-ai-observation-v1")
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash AI observation prompt.", ex);
        }
    }

    private String normalizePrompt(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 200);
    }

    private String required(String value, String field) {
        String normalized = clean(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, field + " is required.");
        }
        return normalized;
    }

    private String nonBlank(String value, String fallback) {
        String normalized = clean(value);
        return normalized != null ? normalized : fallback;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String cleanControlChars(String value) {
        String normalized = clean(value);
        if (normalized == null) {
            return null;
        }
        return normalized.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsAny(String value, String... terms) {
        if (value == null || terms == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (term != null && !term.isBlank() && lower.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
