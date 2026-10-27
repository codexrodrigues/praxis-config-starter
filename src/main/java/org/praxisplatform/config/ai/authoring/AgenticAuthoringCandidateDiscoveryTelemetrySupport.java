package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

final class AgenticAuthoringCandidateDiscoveryTelemetrySupport {

    private static final String SEMANTIC_ROLE_OPERATIONAL_RESOURCE = "semantic-role:operational-resource";
    private static final Pattern EXPLICIT_API_PATH_PATTERN =
            Pattern.compile("(?i)(/api/[a-z0-9][a-z0-9_./-]*)");

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog;
    private final AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer;
    private final AgenticAuthoringIntentCandidateScoringSupport candidateScoringSupport;
    private final AgenticAuthoringIntentQuickReplySupport quickReplySupport;

    AgenticAuthoringCandidateDiscoveryTelemetrySupport(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer,
            AgenticAuthoringIntentCandidateScoringSupport candidateScoringSupport,
            AgenticAuthoringIntentQuickReplySupport quickReplySupport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.apiMetadataCandidateCatalog = apiMetadataCandidateCatalog;
        this.domainCatalogCandidateEnhancer = domainCatalogCandidateEnhancer;
        this.candidateScoringSupport =
                Objects.requireNonNull(candidateScoringSupport, "candidateScoringSupport must not be null");
        this.quickReplySupport =
                Objects.requireNonNull(quickReplySupport, "quickReplySupport must not be null");
    }

    List<String> warnings(AgenticAuthoringLlmIntentResolution llmIntent) {
        List<String> warnings = new ArrayList<>();
        warnings.add("metadata-probe-not-run");
        if (llmIntent == null) {
            warnings.add("semantic-intent-resolution-not-attempted");
        } else {
            warnings.add("llm-intent-resolution-used");
            if (!llmIntent.resolved()) {
                warnings.add(isLlmProviderFailure(llmIntent)
                        ? "llm-intent-resolution-provider-failed-clarification-required"
                        : "llm-intent-resolution-unresolved-clarification-required");
            }
            if (llmIntent.warnings() != null) {
                warnings.addAll(llmIntent.warnings());
            }
        }
        return List.copyOf(warnings);
    }

    boolean isPrimaryLlmIntentProviderFailure(
            boolean shouldResolveLlmIntent,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        return shouldResolveLlmIntent
                && llmIntent != null
                && !llmIntent.resolved()
                && isLlmProviderFailure(llmIntent);
    }

    boolean isLlmProviderFailure(AgenticAuthoringLlmIntentResolution llmIntent) {
        if (llmIntent == null) {
            return false;
        }
        if ("provider_error".equals(valueOrDefault(llmIntent.followUpKind(), ""))) {
            return true;
        }
        return hasLlmWarning(llmIntent, "llm-intent-resolution-failed")
                || hasLlmWarning(llmIntent, "llm-provider-error")
                || hasLlmWarning(llmIntent, "llm-provider-timeout");
    }

    boolean hasLlmWarning(AgenticAuthoringLlmIntentResolution llmIntent, String warning) {
        return llmIntent != null
                && warning != null
                && llmIntent.warnings() != null
                && llmIntent.warnings().contains(warning);
    }

    List<String> sanitizeConsultativeWarnings(
            List<String> warnings,
            String operationKind,
            String artifactKind,
            String changeKind) {
        boolean consultativeComponent = "component".equals(artifactKind)
                && ("answer_component_catalog_question".equals(changeKind)
                || "answer_component_capability_question".equals(changeKind));
        boolean consultativeApiCatalog = "api_catalog".equals(artifactKind);
        if ((!consultativeComponent && !consultativeApiCatalog)
                || (!"explain".equals(operationKind) && !"explore".equals(operationKind))) {
            return warnings == null ? List.of() : warnings;
        }
        return (warnings == null ? List.<String>of() : warnings).stream()
                .filter(this::isDiagnosticWarningCode)
                .distinct()
                .toList();
    }

    boolean isDiagnosticWarningCode(String warning) {
        return warning != null && warning.matches("[a-z0-9._-]+");
    }

    List<AgenticAuthoringCandidate> filterConsultativeApiCatalogCandidates(
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (!"api_catalog".equals(artifactKind)
                || !candidateScoringSupport.isApiCatalogResourceListPrompt(prompt)
                || candidates == null
                || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        boolean hasGroundedCandidate = candidates.stream()
                .anyMatch(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                        && candidate.score() >= 0.80d);
        if (!hasGroundedCandidate) {
            return candidates;
        }
        List<AgenticAuthoringCandidate> filtered = candidates.stream()
                .filter(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                        || hasEvidence(candidate, "lexical-fallback")
                        || candidate.score() >= 0.70d)
                .toList();
        return filtered.isEmpty() ? candidates : deduplicateCandidates(filtered);
    }

    List<String> llmClarificationQuestions(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringGateResult gate,
            List<String> fallbackQuestions) {
        boolean unconfirmedAiAuthoredResourceSelection = hasLlmWarning(
                llmIntent,
                "llm-resource-selection-unconfirmed-by-ai-authored-focus");
        if (llmIntent == null
                || (!llmIntent.resolved()
                        && !isLlmProviderFailure(llmIntent)
                        && !unconfirmedAiAuthoredResourceSelection)
                || gate == null
                || !"clarification_required".equals(gate.status())
                || llmIntent.clarificationQuestions() == null
                || llmIntent.clarificationQuestions().isEmpty()) {
            return fallbackQuestions;
        }
        List<String> questions = llmIntent.clarificationQuestions().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(question -> !question.isBlank())
                .distinct()
                .toList();
        return questions.isEmpty() ? fallbackQuestions : questions;
    }

    List<String> withWarning(List<String> warnings, String warning) {
        List<String> next = new ArrayList<>(warnings == null ? List.of() : warnings);
        next.add(warning);
        return List.copyOf(next);
    }

    List<String> withoutWarnings(List<String> warnings, String... removedWarnings) {
        if (warnings == null || warnings.isEmpty() || removedWarnings == null || removedWarnings.length == 0) {
            return warnings == null ? List.of() : warnings;
        }
        Set<String> removed = Set.of(removedWarnings);
        return warnings.stream()
                .filter(warning -> !removed.contains(warning))
                .toList();
    }

    List<String> withCandidateProvenanceWarnings(
            List<String> warnings,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<String> next = new ArrayList<>(warnings == null ? List.of() : warnings);
        if (hasEvidence(selectedCandidate, "domain-anchor")) {
            next.add("resource-selection-domain-anchor-selected");
        }
        if (hasEvidence(selectedCandidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)) {
            next.add("resource-selection-domain-catalog-grounding-selected");
        }
        if (hasEvidence(selectedCandidate, "lexical-fallback")) {
            next.add("resource-selection-lexical-fallback-selected");
        }
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        if (usableCandidates.stream().anyMatch(candidate -> hasEvidence(candidate, "domain-anchor"))) {
            next.add("resource-selection-domain-anchor-candidates-present");
        }
        if (usableCandidates.stream().anyMatch(candidate -> hasEvidence(
                candidate,
                AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING))) {
            next.add("resource-selection-domain-catalog-grounding-candidates-present");
        }
        return next.stream().distinct().toList();
    }

    JsonNode withResolutionTelemetry(
            JsonNode diagnostics,
            boolean llmResolutionAttempted,
            AgenticAuthoringLlmIntentResolution llmIntent,
            boolean keywordFallbackApplied,
            boolean semanticPolicyCorrectedAnalyticalDashboardIntent,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        boolean detailedDiagnosticsEnabled = diagnostics != null
                && diagnostics.isObject()
                && diagnostics.path("enabled").asBoolean(false);
        ObjectNode root = diagnostics != null && diagnostics.isObject()
                ? (ObjectNode) diagnostics.deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode telemetry = root.putObject("resolutionTelemetry");
        telemetry.put("schemaVersion", "praxis-agentic-authoring-resolution-telemetry.v1");
        telemetry.put("llmResolutionAttempted", llmResolutionAttempted);
        telemetry.put("llmResolved", llmIntent != null && llmIntent.resolved());
        telemetry.put("fallbackPolicy", "semantic_intent_required");
        telemetry.put("keywordFallbackApplied", keywordFallbackApplied);
        telemetry.put("semanticPolicyApplied", semanticPolicyCorrectedAnalyticalDashboardIntent);
        telemetry.put("selectedCandidateUsesDomainAnchor",
                hasEvidence(selectedCandidate, "domain-anchor") && !hasStrongGroundingEvidence(selectedCandidate));
        telemetry.put("selectedCandidateUsesDomainCatalogGrounding",
                hasEvidence(selectedCandidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING));
        telemetry.put("selectedCandidateUsesLexicalFallback", hasEvidence(selectedCandidate, "lexical-fallback"));
        telemetry.put("selectedCandidateUsesBroadArtifactDiscovery",
                hasEvidence(selectedCandidate, "broad-artifact-discovery"));
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        telemetry.put("candidateSetContainsDomainAnchor",
                usableCandidates.stream().anyMatch(candidate -> hasEvidence(candidate, "domain-anchor")));
        telemetry.put("candidateSetContainsDomainCatalogGrounding",
                usableCandidates.stream().anyMatch(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)));
        telemetry.put("candidateSetContainsLexicalFallback",
                usableCandidates.stream().anyMatch(candidate -> hasEvidence(candidate, "lexical-fallback")));
        telemetry.put("candidateSetContainsBroadArtifactDiscovery",
                usableCandidates.stream().anyMatch(candidate -> hasEvidence(candidate, "broad-artifact-discovery")));
        if (selectedCandidate != null) {
            telemetry.put("selectedResourcePath", valueOrDefault(selectedCandidate.resourcePath(), ""));
            telemetry.set("selectedCandidateEvidence",
                    objectMapper.valueToTree(selectedCandidate.evidence() == null ? List.of() : selectedCandidate.evidence()));
        }
        if (detailedDiagnosticsEnabled) {
            appendProviderInvocationTelemetry(
                    telemetry,
                    llmIntent == null ? List.of() : llmIntent.providerInvocations());
        }
        return root;
    }

    private void appendProviderInvocationTelemetry(
            ObjectNode telemetry,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        List<AiProviderInvocationTelemetry> invocations = providerInvocations == null
                ? List.of()
                : providerInvocations.stream().filter(Objects::nonNull).limit(12).toList();
        var items = telemetry.putArray("providerInvocations");
        long totalLatencyMs = 0L;
        int successCount = 0;
        int failureCount = 0;
        long inputTokens = 0L;
        long outputTokens = 0L;
        long cacheReadInputTokens = 0L;
        long cacheWriteInputTokens = 0L;
        long totalTokens = 0L;
        boolean usageAvailable = false;
        for (AiProviderInvocationTelemetry invocation : invocations) {
            ObjectNode item = items.addObject();
            item.put("phase", valueOrDefault(invocation.phase(), "unknown"));
            item.put("attempt", Math.max(1, invocation.attempt()));
            item.put("provider", valueOrDefault(invocation.provider(), "unknown"));
            item.put("model", valueOrDefault(invocation.model(), "unknown"));
            putNonBlank(item, "transport", invocation.transport());
            item.put("status", valueOrDefault(invocation.status(), "unknown"));
            putNonBlank(item, "failureKind", invocation.failureKind());
            item.put("latencyMs", Math.max(0L, invocation.latencyMs()));
            putNonNegative(item, "inputTokens", invocation.inputTokens());
            putNonNegative(item, "outputTokens", invocation.outputTokens());
            putNonNegative(item, "cacheReadInputTokens", invocation.cacheReadInputTokens());
            putNonNegative(item, "cacheWriteInputTokens", invocation.cacheWriteInputTokens());
            putNonNegative(item, "totalTokens", invocation.totalTokens());
            putNonBlank(item, "responseId", invocation.responseId());
            putNonBlank(item, "finishReason", invocation.finishReason());

            totalLatencyMs += Math.max(0L, invocation.latencyMs());
            successCount += "success".equals(invocation.status()) ? 1 : 0;
            failureCount += "failure".equals(invocation.status()) ? 1 : 0;
            inputTokens += nonNegativeOrZero(invocation.inputTokens());
            outputTokens += nonNegativeOrZero(invocation.outputTokens());
            cacheReadInputTokens += nonNegativeOrZero(invocation.cacheReadInputTokens());
            cacheWriteInputTokens += nonNegativeOrZero(invocation.cacheWriteInputTokens());
            totalTokens += nonNegativeOrZero(invocation.totalTokens());
            usageAvailable = usageAvailable
                    || invocation.inputTokens() != null
                    || invocation.outputTokens() != null
                    || invocation.totalTokens() != null;
        }
        ObjectNode aggregate = telemetry.putObject("providerInvocationAggregate");
        aggregate.put("invocationCount", invocations.size());
        aggregate.put("successCount", successCount);
        aggregate.put("failureCount", failureCount);
        aggregate.put("latencyMs", totalLatencyMs);
        aggregate.put("usageAvailable", usageAvailable);
        if (usageAvailable) {
            aggregate.put("inputTokens", inputTokens);
            aggregate.put("outputTokens", outputTokens);
            aggregate.put("cacheReadInputTokens", cacheReadInputTokens);
            aggregate.put("cacheWriteInputTokens", cacheWriteInputTokens);
            aggregate.put("totalTokens", totalTokens);
        }
        aggregate.put("rawPromptCopied", false);
        aggregate.put("rawResponseCopied", false);
        aggregate.put("credentialsCopied", false);
    }

    private void putNonBlank(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private void putNonNegative(ObjectNode node, String field, Integer value) {
        if (value != null && value >= 0) {
            node.put(field, value);
        }
    }

    private long nonNegativeOrZero(Integer value) {
        return value != null && value >= 0 ? value.longValue() : 0L;
    }

    boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(evidence);
    }

    boolean isGovernedDeterministicResolution(
            boolean deterministicFallbackApplied,
            AgenticAuthoringGateResult gate,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        if (!deterministicFallbackApplied
                || selectedCandidate == null
                || gate == null
                || !"eligible".equals(gate.status())
                || "unknown".equals(valueOrUnknown(operationKind))
                || "unknown".equals(valueOrUnknown(artifactKind))
                || "unknown".equals(valueOrUnknown(changeKind))) {
            return false;
        }
        if (hasEvidence(selectedCandidate, "lexical-fallback")
                || hasEvidence(selectedCandidate, "weak-evidence")) {
            return false;
        }
        boolean groundedByLlmAuthoredToolSearch = hasEvidence(selectedCandidate, "tool-search-api-resources")
                && hasEvidence(selectedCandidate, "semantic-retrieval")
                && hasTrustedSelectionEvidence(selectedCandidate)
                && hasEvidence(selectedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE);
        if (hasEvidence(selectedCandidate, "broad-artifact-discovery")
                && !groundedByLlmAuthoredToolSearch
                && !hasStrongGroundingEvidence(selectedCandidate)) {
            return false;
        }
        if (hasEvidence(selectedCandidate, "domain-anchor") && !hasStrongGroundingEvidence(selectedCandidate)) {
            return false;
        }
        return selectedCandidate.score() >= 0.75d
                || hasStrongGroundingEvidence(selectedCandidate)
                || hasEvidence(selectedCandidate, "semantic-retrieval")
                || hasEvidence(selectedCandidate, "tool-search-api-resources")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "explicit-resource-path");
    }

    boolean shouldSuppressWeakUnresolvedLlmSelection(
            boolean deterministicFallbackApplied,
            AgenticAuthoringLlmIntentResolution llmIntent,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        if (!deterministicFallbackApplied
                || llmIntent == null
                || llmIntent.resolved()
                || selectedCandidate == null) {
            return false;
        }
        if (!"unknown".equals(valueOrUnknown(operationKind))
                && !"unknown".equals(valueOrUnknown(artifactKind))) {
            return false;
        }
        return hasEvidence(selectedCandidate, "lexical-fallback")
                || hasEvidence(selectedCandidate, "weak-evidence")
                || hasEvidence(selectedCandidate, "broad-artifact-discovery");
    }

    boolean shouldDeferWeakLexicalApiCatalogSelection(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return quickReplySupport.isApiCatalogQuestion(operationKind, artifactKind, changeKind)
                && isWeakLexicalCandidate(selectedCandidate)
                && !hasTrustedSelectionEvidence(selectedCandidate);
    }

    boolean shouldDeferWeakLexicalConsultativeSelection(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        String normalizedChangeKind = valueOrDefault(changeKind, "");
        return ("explore".equals(operationKind) || "explain".equals(operationKind))
                && !"api_catalog".equals(artifactKind)
                && normalizedChangeKind.startsWith("answer_")
                && isWeakLexicalCandidate(selectedCandidate)
                && !hasTrustedSelectionEvidence(selectedCandidate);
    }

    boolean shouldDeferUnresolvedLlmWeakLexicalSelection(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringCandidate selectedCandidate,
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        return llmIntent != null
                && !llmIntent.resolved()
                && valueOrDefault(llmIntent.selectedResourcePath(), "").isBlank()
                && isWeakLexicalCandidate(selectedCandidate)
                && !hasTrustedSelectionEvidence(selectedCandidate)
                && candidateScoringSupport.strongestPromptCandidateAlignmentScore(
                        prompt,
                        candidates == null || candidates.isEmpty()
                                ? List.of(selectedCandidate)
                                : candidates) < 6;
    }

    boolean isWeakLexicalCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if (hasStrongGroundingEvidence(candidate)
                || hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                || hasEvidence(candidate, "semantic-retrieval")
                || hasEvidence(candidate, "schema-available")) {
            return false;
        }
        if (hasEvidence(candidate, "lexical-fallback") || hasEvidence(candidate, "weak-evidence")) {
            return true;
        }
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        return bundle != null
                && "lexical_fallback".equals(valueOrDefault(bundle.retrievalSource(), ""));
    }

    boolean hasTrustedSelectionEvidence(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return hasStrongGroundingEvidence(candidate)
                || hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                || hasEvidence(candidate, "semantic-retrieval")
                || hasEvidence(candidate, "tool-search-api-resources")
                || hasEvidence(candidate, "explicit-source-match")
                || hasEvidence(candidate, "explicit-resource-path")
                || hasEvidence(candidate, "quick-reply-context")
                || hasEvidence(candidate, "current-page");
    }

    boolean hasStrongGroundingEvidence(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidenceBundle() == null) {
            return hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING);
        }
        if (hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)) {
            return true;
        }
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        if ("lexical_fallback".equals(valueOrDefault(bundle.retrievalSource(), ""))) {
            return false;
        }
        double strongestEvidence = bundle.evidence().stream()
                .mapToDouble(AgenticAuthoringEvidenceBundle.Evidence::confidence)
                .max()
                .orElse(0d);
        boolean structuralEvidence = bundle.evidence().stream()
                .map(AgenticAuthoringEvidenceBundle.Evidence::kind)
                .map(kind -> valueOrDefault(kind, ""))
                .anyMatch(kind -> kind.equals("schema_grounding")
                        || kind.equals("domain_catalog_grounding")
                        || kind.equals("operation_grounding")
                        || kind.equals("resource_capability_hint")
                        || kind.equals("retrieved_candidate"));
        return strongestEvidence >= 0.62d && structuralEvidence;
    }

    boolean isLlmFollowUpKind(AgenticAuthoringLlmIntentResolution llmIntent, String expected) {
        if (llmIntent == null || llmIntent.followUpKind() == null || expected == null) {
            return false;
        }
        return expected.equals(normalize(llmIntent.followUpKind()));
    }

    AgenticAuthoringPendingClarification pendingClarification(
            String effectivePrompt,
            AgenticAuthoringGateResult gate,
            String assistantMessage,
            List<String> questions,
            String clientTurnId,
            List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        if (gate == null || !"clarification_required".equals(gate.status()) || questions == null || questions.isEmpty()) {
            return null;
        }
        String prompt = valueOrDefault(effectivePrompt, "").trim();
        if (prompt.isBlank()) {
            return null;
        }
        String message = valueOrDefault(assistantMessage, "").trim();
        if (message.isBlank()) {
            message = questions.get(0);
        }
        return new AgenticAuthoringPendingClarification(
                prompt,
                List.copyOf(questions),
                message,
                clientTurnId,
                pendingClarificationDiagnostics(attachmentSummaries));
    }

    JsonNode pendingClarificationDiagnostics(List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        if (attachmentSummaries == null || attachmentSummaries.isEmpty()) {
            return null;
        }
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.set("attachmentSummaries", objectMapper.valueToTree(attachmentSummaries));
        return diagnostics;
    }

    boolean isExplicitCreateConfirmation(String prompt) {
        return containsAny(prompt,
                "confirmed: criar",
                "confirmed: create",
                "confirmado: criar",
                "confirmado: crie",
                "sim, crie",
                "sim crie",
                "pode criar",
                "pode fazer",
                "fazer agora",
                "faz agora",
                "pode montar",
                "pode gerar",
                "confirmo criar",
                "confirmo, crie");
    }

    boolean isConsultativePrompt(String prompt) {
        return containsAny(prompt, "melhor forma", "como visualizar", "visualizar informacoes",
                "visualizar informacao", "visualizar", "analisar", "analise", "explorar", "explore",
                "me ajude", "ajude", "escolher", "sugerir", "sugira", "como ver",
                "opcao", "opcoes", "alternativa", "alternativas", "indicar", "indique", "voce indica", "me indica",
                "recomendar", "recomende", "recomendacao", "recomendacoes", "comparar", "compare",
                "comparativo", "orientar", "oriente", "me oriente", "faz mais sentido", "devo usar",
                "ver", "visao", "mostrar", "mostre");
    }

    boolean isApiCatalogQuestion(String prompt) {
        if (!containsApiCatalogSubject(prompt)) {
            return false;
        }
        return containsAny(prompt,
                "qual", "quais", "que api", "que apis", "que endpoint", "que endpoints",
                "que schema", "que schemas", "o que", "essa", "esse", "esta", "este", "existe", "existem",
                "listar", "liste", "mostrar", "mostre", "consultar", "consulta", "devo usar",
                "usar para", "campos existem", "suporta", "permite", "relacionad", "complement",
                "combinar", "combine", "vincul");
    }

    private boolean containsApiCatalogSubject(String prompt) {
        String wordPadded = " " + prompt.replaceAll("[^a-z0-9]+", " ") + " ";
        if (wordPadded.contains(" api ") || wordPadded.contains(" apis ")
                || wordPadded.contains(" acao ") || wordPadded.contains(" acoes ")
                || wordPadded.contains(" action ") || wordPadded.contains(" actions ")) {
            return true;
        }
        return containsAny(prompt, "endpoint", "endpoints", "schema", "schemas", "filtro", "filtros", "filtrar");
    }

    boolean isBareDomainPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank() || !explicitResourcePath(normalized).isBlank()) {
            return false;
        }
        String[] tokens = normalized.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        return tokens.length <= 2;
    }

    List<AgenticAuthoringCandidate> discoverCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target,
            String tenantId,
            String environment) {
        String effectiveArtifactKind = discoveryArtifactKindForPrompt(prompt, artifactKind);
        if ("component".equals(effectiveArtifactKind)) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()
                && !candidateScoringSupport.shouldDetachCurrentTarget(prompt, effectiveArtifactKind, target)) {
            String operation = target.submitMethod() == null || target.submitMethod().isBlank()
                    ? "post"
                    : target.submitMethod();
            candidates.add(candidateScoringSupport.candidate(
                    target.resourcePath(),
                    operation,
                    0.95d,
                    "resource resolved from current target widget",
                    "current-page"));
        }
        List<AgenticAuthoringCandidate> metadataCandidates = apiMetadataCandidateCatalog == null
                ? List.of()
                : apiMetadataCandidateCatalog.discover(prompt, effectiveArtifactKind, tenantId, environment, null);
        if (metadataCandidates.isEmpty()
                && "api_catalog".equals(effectiveArtifactKind)
                && apiMetadataCandidateCatalog != null) {
            metadataCandidates = apiMetadataCandidateCatalog.discover("", "unknown", tenantId, environment, null);
        }
        if (metadataCandidates.isEmpty() && apiMetadataCandidateCatalog != null) {
            metadataCandidates = apiMetadataCandidateCatalog.discover("", effectiveArtifactKind, tenantId, environment, null);
        }
        if (metadataCandidates.isEmpty()
                && "api_catalog".equals(effectiveArtifactKind)
                && apiMetadataCandidateCatalog != null) {
            metadataCandidates = apiMetadataCandidateCatalog.discover("", "unknown", tenantId, environment, null);
        }
        if (!metadataCandidates.isEmpty()) {
            candidates.addAll(metadataCandidates);
        }
        return groundCandidates(prompt, deduplicateCandidates(candidates), tenantId, environment);
    }

    List<AgenticAuthoringCandidate> discoverInitialCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target,
            String tenantId,
            String environment) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        String effectiveArtifactKind = discoveryArtifactKindForPrompt(prompt, artifactKind);
        if ("component".equals(effectiveArtifactKind)) {
            return List.of();
        }
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()
                && !candidateScoringSupport.shouldDetachCurrentTarget(prompt, effectiveArtifactKind, target)) {
            String operation = target.submitMethod() == null || target.submitMethod().isBlank()
                    ? "post"
                    : target.submitMethod();
            candidates.add(candidateScoringSupport.candidate(
                    target.resourcePath(),
                    operation,
                    0.95d,
                    "resource resolved from current target widget",
                    "current-page"));
        }
        if (apiMetadataCandidateCatalog != null) {
            if (!"unknown".equals(effectiveArtifactKind)) {
                candidates.addAll(apiMetadataCandidateCatalog.discover(
                        prompt,
                        effectiveArtifactKind,
                        tenantId,
                        environment,
                        null));
                if (hasExplicitSourceCandidate(candidates)) {
                    return groundCandidates(prompt, explicitSourceDiscoveryScope(candidates), tenantId, environment);
                }
            }
            candidates.addAll(apiMetadataCandidateCatalog.discover("", "unknown", tenantId, environment, null));
            if (hasExplicitSourceCandidate(candidates)) {
                return groundCandidates(prompt, explicitSourceDiscoveryScope(candidates), tenantId, environment);
            }
            candidates.addAll(apiMetadataCandidateCatalog.discover(
                    "",
                    !"unknown".equals(effectiveArtifactKind) ? effectiveArtifactKind : "unknown",
                    tenantId,
                    environment,
                    null));
        }
        return groundCandidates(prompt, deduplicateCandidates(candidates), tenantId, environment);
    }

    List<AgenticAuthoringCandidate> discoverPreLlmContextCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target) {
        String effectiveArtifactKind = discoveryArtifactKindForPrompt(prompt, artifactKind);
        if ("component".equals(effectiveArtifactKind)
                || target == null
                || target.resourcePath() == null
                || target.resourcePath().isBlank()
                || candidateScoringSupport.shouldDetachCurrentTarget(prompt, effectiveArtifactKind, target)) {
            return List.of();
        }
        String operation = target.submitMethod() == null || target.submitMethod().isBlank()
                ? "post"
                : target.submitMethod();
        return List.of(candidateScoringSupport.candidate(
                target.resourcePath(),
                operation,
                0.95d,
                "resource resolved from current target widget",
                "current-page"));
    }

    String discoveryArtifactKindForPrompt(String prompt, String artifactKind) {
        String effectiveArtifactKind = valueOrUnknown(artifactKind);
        if (candidateScoringSupport.isExplicitGovernedBusinessRulePrompt(prompt)) {
            return "form";
        }
        return effectiveArtifactKind;
    }

    boolean hasExplicitSourceCandidate(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && candidates.stream().anyMatch(candidate -> hasEvidence(candidate, "explicit-source-match"));
    }

    List<AgenticAuthoringCandidate> explicitSourceDiscoveryScope(List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> hasEvidence(candidate, "explicit-source-match")
                        || hasEvidence(candidate, "context-hint")
                        || hasEvidence(candidate, "quick-reply-context")
                        || hasEvidence(candidate, "current-page")
                        || hasEvidence(candidate, "explicit-resource-path"))
                .toList();
    }

    List<AgenticAuthoringCandidate> groundCandidates(
            String prompt,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        if (domainCatalogCandidateEnhancer == null || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<AgenticAuthoringCandidate> explicitSourceCandidates = candidates.stream()
                .filter(candidate -> hasEvidence(candidate, "explicit-source-match"))
                .toList();
        if (!explicitSourceCandidates.isEmpty()) {
            List<AgenticAuthoringCandidate> merged = new ArrayList<>(explicitSourceCandidates);
            candidates.stream()
                    .filter(candidate -> !hasEvidence(candidate, "explicit-source-match"))
                    .forEach(merged::add);
            return pruneWeakLexicalCandidatesWhenGrounded(prompt, deduplicateCandidates(merged));
        }
        return pruneWeakLexicalCandidatesWhenGrounded(prompt, deduplicateCandidates(
                domainCatalogCandidateEnhancer.enhance(prompt, candidates, tenantId, environment)));
    }

    List<AgenticAuthoringCandidate> candidatesForLlmIntent(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> explicitSourceCandidates = candidates.stream()
                .filter(candidate -> hasEvidence(candidate, "explicit-source-match"))
                .toList();
        if (explicitSourceCandidates.isEmpty()) {
            return candidates;
        }
        List<AgenticAuthoringCandidate> trustedContextCandidates = candidates.stream()
                .filter(candidate -> !hasEvidence(candidate, "explicit-source-match"))
                .filter(candidate -> hasEvidence(candidate, "context-hint")
                        || hasEvidence(candidate, "quick-reply-context")
                        || hasEvidence(candidate, "current-page")
                        || hasEvidence(candidate, "explicit-resource-path"))
                .toList();
        List<AgenticAuthoringCandidate> scoped = new ArrayList<>(explicitSourceCandidates);
        scoped.addAll(trustedContextCandidates);
        return deduplicateCandidates(scoped);
    }

    List<AgenticAuthoringCandidate> pruneWeakLexicalCandidatesWhenGrounded(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        boolean hasGroundedCandidate = candidates.stream()
                .anyMatch(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING));
        if (!hasGroundedCandidate) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> !isWeakLexicalCandidate(candidate)
                        || candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, candidate))
                .toList();
    }

    List<AgenticAuthoringCandidate> deduplicateCandidates(List<AgenticAuthoringCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt(candidateScoringSupport::evidenceStrength)
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed()))
                .collect(
                        Collectors.toMap(
                                this::candidateOperationIdentity,
                                candidate -> candidate,
                                this::preferredCandidateForSameResource,
                                LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    private CandidateOperationIdentity candidateOperationIdentity(AgenticAuthoringCandidate candidate) {
        List<String> scope = candidateEvidenceScope(candidate);
        return new CandidateOperationIdentity(
                candidate.resourcePath(),
                valueOrDefault(candidate.operation(), "").toLowerCase(Locale.ROOT),
                candidate.schemaUrl(),
                candidate.submitUrl(),
                valueOrDefault(candidate.submitMethod(), "").toLowerCase(Locale.ROOT),
                scope == null ? candidate.evidenceBundle() : scope);
    }

    private record CandidateOperationIdentity(
            String resourcePath,
            String operation,
            String schemaUrl,
            String submitUrl,
            String submitMethod,
            Object scope) {
    }

    JsonNode verifiedOperationForCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate candidate) {
        if (request == null || request.contextHints() == null || candidate == null
                || !hasVerifiedOperationalBindingEvidence(candidate)
                || !candidateHasConsistentSource(candidate, AgenticAuthoringCandidateProvenancePolicy.DOMAIN_BINDING)
                || candidate.evidenceBundle() == null) {
            return null;
        }
        List<String> scope = candidateEvidenceScope(candidate);
        if (scope == null || scope.get(0).isBlank() || scope.get(1).isBlank()) {
            return null;
        }
        // The stream boundary strips this envelope from client context and the engine
        // reconstructs it from the current principal's operational verification tool.
        JsonNode envelope = request.contextHints().path("verifiedDomainOperations");
        JsonNode entries = envelope.path("entries");
        if (!"praxis-agentic-authoring-verified-domain-operations.v2".equals(jsonText(envelope, "schemaVersion"))
                || !"schemas.filtered+resource.capabilities+schemas.actions".equals(jsonText(envelope, "source"))
                || !entries.isArray() || !envelope.path("operationCount").canConvertToInt()
                || envelope.path("operationCount").asInt() != entries.size()) {
            return null;
        }
        for (JsonNode entry : entries) {
            if (!"resource_operation".equals(jsonText(entry, "kind"))
                    || !"principal_capability".equals(jsonText(entry, "verificationMode"))
                    || !entry.path("availability").path("allowed").asBoolean(false)
                    || !"resource_capabilities".equals(jsonText(entry.path("availability"), "resolution"))
                    || jsonText(entry, "resourceKey").isBlank() || jsonText(entry, "apiPath").isBlank()
                    || jsonText(entry, "operationId").isBlank()
                    || !Objects.equals(candidate.resourcePath(), jsonText(entry, "resourcePath"))
                    || !sameCandidateMethod(candidate.operation(), jsonText(entry, "apiMethod"))
                    || !Objects.equals(candidate.schemaUrl(), jsonText(entry, "schemaUrl"))
                    || !scope.get(2).equals(jsonText(entry, "sourceRelease"))) {
                continue;
            }
            boolean read = "get".equalsIgnoreCase(candidate.operation());
            if (read ? candidate.submitUrl() != null || candidate.submitMethod() != null
                    : !Objects.equals(candidate.submitUrl(), jsonText(entry, "apiPath"))
                            || !sameCandidateMethod(candidate.submitMethod(), jsonText(entry, "apiMethod"))) {
                continue;
            }
            List<AgenticAuthoringEvidenceBundle.Evidence> evidence = candidate.evidenceBundle().evidence();
            if (hasOperationEvidenceRef(evidence, "domain_knowledge_binding", "retrieved_candidate", jsonText(entry, "bindingKey"))
                    && hasOperationEvidenceRef(evidence, "/schemas/filtered", "schema_grounding", jsonText(entry, "schemaUrl"))
                    && hasOperationEvidenceRef(evidence, "capabilities", "operation_grounding", jsonText(entry, "metadataUrl"))
                    && evidence.stream().anyMatch(item -> "/schemas/filtered".equals(item.source())
                            && "schema_grounding".equals(item.kind())
                            && item.matchedTerms().stream().anyMatch(term -> sameCandidateMethod(candidate.operation(), term)))
                    && evidence.stream().anyMatch(item -> "capabilities".equals(item.source())
                            && "operation_grounding".equals(item.kind())
                            && item.matchedTerms().contains(jsonText(entry, "operationId")))) {
                return entry;
            }
        }
        return null;
    }

    private boolean hasOperationEvidenceRef(
            List<AgenticAuthoringEvidenceBundle.Evidence> evidence,
            String source,
            String kind,
            String ref) {
        return !ref.isBlank() && evidence.stream().anyMatch(item -> source.equals(item.source())
                && kind.equals(item.kind()) && ref.equals(item.ref()));
    }

    AgenticAuthoringCandidate preferredCandidateForSameResource(
            AgenticAuthoringCandidate left,
            AgenticAuthoringCandidate right) {
        // Post-semantic reconciliation, not intent inference. Keep the already verified
        // operational candidate intact instead of replacing it with documentary grounding.
        // Never transfer its verification markers to a different candidate/provenance.
        if (canRetainVerifiedBinding(left, right)) {
            return left;
        }
        if (canRetainVerifiedBinding(right, left)) {
            return right;
        }
        int leftStrength = candidateScoringSupport.evidenceStrength(left);
        int rightStrength = candidateScoringSupport.evidenceStrength(right);
        AgenticAuthoringCandidate preferred;
        AgenticAuthoringCandidate secondary;
        if (leftStrength != rightStrength) {
            preferred = leftStrength > rightStrength ? left : right;
            secondary = leftStrength > rightStrength ? right : left;
        } else {
            preferred = left.score() >= right.score() ? left : right;
            secondary = left.score() >= right.score() ? right : left;
        }
        return mergeCandidateEvidence(preferred, secondary);
    }

    boolean canRetainVerifiedBinding(
            AgenticAuthoringCandidate binding,
            AgenticAuthoringCandidate catalog) {
        return binding != null && catalog != null
                && hasVerifiedOperationalBindingEvidence(binding)
                && !hasEvidence(binding, "lexical-fallback")
                && !hasEvidence(binding, "weak-evidence")
                && candidateHasConsistentSource(binding, AgenticAuthoringCandidateProvenancePolicy.DOMAIN_BINDING)
                && candidateHasConsistentSource(catalog, AgenticAuthoringCandidateProvenancePolicy.DOMAIN_CATALOG)
                && !hasVerifiedOperationalBindingEvidence(catalog)
                && compatibleCandidateEvidence(binding, catalog);
    }

    boolean hasVerifiedOperationalBindingEvidence(AgenticAuthoringCandidate candidate) {
        return hasEvidence(candidate, "domain-binding")
                && hasEvidence(candidate, "schema-grounding-verified")
                && hasEvidence(candidate, "resource-capabilities-verified");
    }

    boolean candidateHasConsistentSource(AgenticAuthoringCandidate candidate, String source) {
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        return source.equals(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(candidate)))
                && (bundle == null || source.equals(bundle.retrievalSource()));
    }

    private boolean compatibleCandidateEvidence(
            AgenticAuthoringCandidate left,
            AgenticAuthoringCandidate right) {
        if (!sameNonBlankCandidateValue(left.resourcePath(), right.resourcePath())
                || !sameNonBlankCandidateValue(left.schemaUrl(), right.schemaUrl())
                || left.operation() == null || left.operation().isBlank()
                || !sameCandidateMethod(left.operation(), right.operation())
                || !Objects.equals(left.submitUrl(), right.submitUrl())
                || !sameCandidateMethod(left.submitMethod(), right.submitMethod())) {
            return false;
        }
        List<String> leftScope = candidateEvidenceScope(left);
        List<String> rightScope = candidateEvidenceScope(right);
        return leftScope != null && leftScope.equals(rightScope);
    }

    private boolean sameNonBlankCandidateValue(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    boolean sameCandidateMethod(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    List<String> candidateEvidenceScope(AgenticAuthoringCandidate candidate) {
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        if (bundle == null || bundle.evidence().isEmpty()) {
            return List.of("", "", "");
        }
        if (bundle.evidence().stream().anyMatch(Objects::isNull)) {
            return null;
        }
        List<List<String>> scopes = bundle.evidence().stream()
                .map(item -> List.of(item.tenantId(), item.environment(), item.releaseId()))
                .distinct()
                .toList();
        return scopes.size() == 1 ? scopes.get(0) : null;
    }

    private AgenticAuthoringCandidate mergeCandidateEvidence(
            AgenticAuthoringCandidate preferred,
            AgenticAuthoringCandidate secondary) {
        if (preferred == null || secondary == null || secondary.evidence() == null || secondary.evidence().isEmpty()) {
            return preferred;
        }
        String preferredSource = AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(preferred));
        String secondarySource = AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(secondary));
        if (!preferredSource.equals(secondarySource)
                || !compatibleCandidateEvidence(preferred, secondary)) {
            return preferred;
        }
        List<String> preferredEvidence = preferred.evidence() == null ? List.of() : preferred.evidence();
        LinkedHashSet<String> mergedEvidence = new LinkedHashSet<>(preferredEvidence);
        mergedEvidence.addAll(secondary.evidence());
        if (mergedEvidence.size() == preferredEvidence.size()) {
            return preferred;
        }
        return new AgenticAuthoringCandidate(
                preferred.resourcePath(),
                preferred.operation(),
                preferred.schemaUrl(),
                preferred.submitUrl(),
                preferred.submitMethod(),
                preferred.score(),
                preferred.reason(),
                List.copyOf(mergedEvidence),
                preferred.evidenceBundle() != null ? preferred.evidenceBundle() : secondary.evidenceBundle());
    }

    private String explicitResourcePath(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        Matcher matcher = EXPLICIT_API_PATH_PATTERN.matcher(prompt);
        return matcher.find() ? normalizePath(matcher.group(1).replaceAll("[.,;:!?]+$", "")) : "";
    }

    private boolean containsAny(String value, String... tokens) {
        return AgenticAuthoringTextNormalizationSupport.containsAny(value, tokens);
    }

    private String normalize(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalize(value);
    }

    private String normalizePath(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalizePath(value);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return AgenticAuthoringTextNormalizationSupport.valueOrDefault(value, defaultValue);
    }

    private String valueOrUnknown(String value) {
        return AgenticAuthoringTextNormalizationSupport.valueOrUnknown(value);
    }

    private String jsonText(JsonNode node, String fieldName) {
        return AgenticAuthoringTextNormalizationSupport.jsonText(node, fieldName);
    }
}
