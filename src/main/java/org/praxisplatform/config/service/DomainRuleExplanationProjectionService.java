package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleMaterializationResponse;
import org.praxisplatform.config.dto.DomainRuleTimelineEventResponse;
import org.praxisplatform.config.dto.DomainRuleTimelineResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicValidationOptions;

/** Builds the canonical sanitized evidence used by the read-only decision explanation tool. */
public class DomainRuleExplanationProjectionService {

    private static final String REDACTION_POLICY_REF = "domain-decision-explanation-redaction.v1";
    private static final List<String> ALWAYS_OMITTED_FIELDS = List.of(
            "tenantId",
            "environment",
            "createdByType",
            "createdBy",
            "approvedBy",
            "timeline.actorType",
            "timeline.actor",
            "materializedPayload",
            "materialization.validationResult",
            "materialization.decisionDiagnostics",
            "materialization.appliedByType",
            "materialization.appliedBy",
            "workspace.rationale",
            "scenario.facts");
    private static final JsonLogicValidationOptions VALIDATION_OPTIONS =
            new JsonLogicValidationOptions(
                    List.of(),
                    null,
                    true,
                    "2026-01-01T00:00:00.000Z",
                    "UTC",
                    true);

    private final DomainRuleService domainRuleService;
    private final DomainRuleDefinitionFingerprint definitionFingerprint;
    private final ObjectMapper objectMapper;
    private final PraxisJsonLogicEngine jsonLogicEngine;
    private final Set<String> knownOperators;

    public DomainRuleExplanationProjectionService(
            DomainRuleService domainRuleService,
            DomainRuleDefinitionFingerprint definitionFingerprint,
            ObjectMapper objectMapper) {
        this(domainRuleService, definitionFingerprint, objectMapper, new PraxisJsonLogicEngine());
    }

    DomainRuleExplanationProjectionService(
            DomainRuleService domainRuleService,
            DomainRuleDefinitionFingerprint definitionFingerprint,
            ObjectMapper objectMapper,
            PraxisJsonLogicEngine jsonLogicEngine) {
        this.domainRuleService = Objects.requireNonNull(domainRuleService, "domainRuleService must not be null");
        this.definitionFingerprint = Objects.requireNonNull(
                definitionFingerprint, "definitionFingerprint must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.jsonLogicEngine = Objects.requireNonNull(jsonLogicEngine, "jsonLogicEngine must not be null");
        LinkedHashSet<String> operators = new LinkedHashSet<>();
        this.jsonLogicEngine.listOperatorDescriptors().forEach(descriptor -> operators.add(descriptor.operator()));
        this.knownOperators = Set.copyOf(operators);
    }

    /**
     * Resolves and attests one exact definition under the supplied governed principal.
     *
     * <p>The expected key and version are untrusted selection hints. They are reconciled before
     * timeline or materialization reads so stale selections cannot produce mixed-version evidence.</p>
     */
    public DomainRuleExplanationEvidenceProjection project(
            UUID definitionId,
            String expectedRuleKey,
            Integer expectedVersion,
            DomainRuleGovernancePrincipal principal) {
        if (definitionId == null) {
            throw new ConfigurationIngestionException("definitionId is required");
        }
        if (principal == null) {
            throw new ConfigurationIngestionException("Governed domain-rule principal is required");
        }

        DomainRuleDefinitionResponse definition = domainRuleService.definition(definitionId, principal);
        requireExactSelection(definition, expectedRuleKey, expectedVersion);

        DomainRuleTimelineResponse timeline = domainRuleService.definitionTimeline(
                definitionId,
                principal.tenantId(),
                principal.environment());
        List<DomainRuleMaterializationResponse> materializations = domainRuleService.materializations(
                principal.tenantId(),
                principal.environment(),
                definitionId,
                null,
                null,
                null,
                null);

        String definitionHash = definitionFingerprint.sha256(fingerprintSource(definition));
        JsonNode condition = definition.condition();
        String conditionHash = PraxisCanonicalJson.sha256(
                condition == null ? objectMapper.nullNode() : condition);
        ExposurePolicy exposure = exposurePolicy(definition.governance());
        ConditionInspection inspection = inspectCondition(condition, exposure);
        List<DomainRuleExplanationEvidenceProjection.SafeTimelineEvent> safeEvents = safeEvents(timeline);
        List<DomainRuleExplanationEvidenceProjection.MaterializationEvidence> safeMaterializations =
                safeMaterializations(materializations);

        List<String> sourceRefs = new ArrayList<>();
        sourceRefs.add(definitionRef(definition, definitionHash));
        sourceRefs.add(conditionRef(definition, conditionHash));
        safeEvents.stream()
                .filter(event -> event.occurredAt() != null)
                .map(event -> "domain-rule-event:%s:%s:%s".formatted(
                        definition.id(), safeRefPart(event.eventType()), event.occurredAt()))
                .forEach(sourceRefs::add);
        safeMaterializations.stream()
                .filter(materialization -> materialization.id() != null)
                .map(materialization -> "domain-rule-materialization:%s#%s".formatted(
                        materialization.id(), safeRefPart(materialization.sourceHash())))
                .forEach(sourceRefs::add);

        List<String> omittedFields = new ArrayList<>(ALWAYS_OMITTED_FIELDS);
        if (!"full".equals(exposure.mode())) {
            omittedFields.add("conditionAst");
        }
        if (!exposure.exposeFactPaths()) {
            omittedFields.add("conditionFactPaths");
        }

        return new DomainRuleExplanationEvidenceProjection(
                DomainRuleExplanationEvidenceProjection.SCHEMA_VERSION,
                new DomainRuleExplanationEvidenceProjection.DecisionRef(
                        definition.id(),
                        definition.ruleKey(),
                        definition.version(),
                        definitionHash,
                        conditionHash),
                new DomainRuleExplanationEvidenceProjection.ScopeAttestation(true, true),
                new DomainRuleExplanationEvidenceProjection.SemanticContext(
                        definition.ruleType(),
                        definition.status(),
                        definition.contextKey(),
                        definition.resourceKey(),
                        definition.serviceKey(),
                        definition.semanticOwner()),
                new DomainRuleExplanationEvidenceProjection.ConditionEvidence(
                        exposure.mode(),
                        exposure.exposeAst() && condition != null ? condition : null,
                        inspection.operators(),
                        inspection.factPaths(),
                        inspection.valid(),
                        inspection.validationIssues()),
                new DomainRuleExplanationEvidenceProjection.LifecycleEvidence(
                        definition.status(), safeEvents),
                safeMaterializations,
                sourceRefs,
                new DomainRuleExplanationEvidenceProjection.RedactionEvidence(
                        REDACTION_POLICY_REF,
                        exposure.mode(),
                        omittedFields,
                        false,
                        false,
                        false,
                        false,
                        false),
                new DomainRuleExplanationEvidenceProjection.VersionAttestation(
                        expectedVersion,
                        definition.version(),
                        Objects.equals(expectedVersion, definition.version())));
    }

    private void requireExactSelection(
            DomainRuleDefinitionResponse definition,
            String expectedRuleKey,
            Integer expectedVersion) {
        if (definition == null) {
            throw new ConfigurationIngestionException("Rule definition is required for explanation");
        }
        String expectedKey = normalize(expectedRuleKey);
        if (expectedKey == null || !expectedKey.equals(normalize(definition.ruleKey()))) {
            throw new ConfigurationIngestionException("Selected rule key does not match the persisted definition");
        }
        if (expectedVersion == null || !Objects.equals(expectedVersion, definition.version())) {
            throw new ConfigurationIngestionException("Selected rule version does not match the persisted definition");
        }
    }

    private ExposurePolicy exposurePolicy(JsonNode governance) {
        JsonNode aiUsage = governance == null ? null : governance.path("aiUsage");
        if (aiUsage == null || !aiUsage.isObject()) {
            return ExposurePolicy.summaryOnly();
        }
        String visibility = lower(text(aiUsage, "visibility"));
        String reasoningUse = lower(text(aiUsage, "reasoningUse"));
        if ("deny".equals(visibility) || "deny".equals(reasoningUse)) {
            return ExposurePolicy.denied();
        }
        return switch (visibility) {
            case "allow" -> Set.of("allow", "review_required").contains(reasoningUse)
                    ? ExposurePolicy.full()
                    : ExposurePolicy.summaryOnly();
            case "mask" -> ExposurePolicy.masked();
            case "summarize_only" -> ExposurePolicy.summaryOnly();
            default -> ExposurePolicy.summaryOnly();
        };
    }

    private ConditionInspection inspectCondition(JsonNode condition, ExposurePolicy exposure) {
        if (condition == null || condition.isNull()) {
            return new ConditionInspection(true, List.of(), List.of(), List.of());
        }
        var validation = jsonLogicEngine.validateResult(condition, VALIDATION_OPTIONS);
        List<DomainRuleExplanationEvidenceProjection.ValidationIssue> validationIssues = validation.issues().stream()
                .map(issue -> new DomainRuleExplanationEvidenceProjection.ValidationIssue(
                        issue.code().name(), issue.path(), issue.operator()))
                .toList();
        if ("denied".equals(exposure.mode())) {
            return new ConditionInspection(validation.valid(), List.of(), List.of(), List.of());
        }
        if ("masked".equals(exposure.mode())) {
            List<DomainRuleExplanationEvidenceProjection.ValidationIssue> redactedIssues = validation.issues().stream()
                    .map(issue -> new DomainRuleExplanationEvidenceProjection.ValidationIssue(
                            issue.code().name(), null, null))
                    .toList();
            return new ConditionInspection(validation.valid(), List.of(), List.of(), redactedIssues);
        }
        LinkedHashSet<String> operators = new LinkedHashSet<>();
        LinkedHashSet<String> factPaths = new LinkedHashSet<>();
        collectConditionShape(condition, operators, factPaths);
        List<String> visibleFactPaths = exposure.exposeFactPaths()
                ? factPaths.stream().sorted().toList()
                : List.of();
        return new ConditionInspection(
                validation.valid(),
                operators.stream().sorted().toList(),
                visibleFactPaths,
                validationIssues);
    }

    private void collectConditionShape(
            JsonNode node,
            Set<String> operators,
            Set<String> factPaths) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectConditionShape(item, operators, factPaths));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(entry -> {
            if (knownOperators.contains(entry.getKey())) {
                operators.add(entry.getKey());
            }
            if ("var".equals(entry.getKey())) {
                String factPath = variablePath(entry.getValue());
                if (factPath != null) {
                    factPaths.add(factPath);
                }
            }
            collectConditionShape(entry.getValue(), operators, factPaths);
        });
    }

    private String variablePath(JsonNode value) {
        JsonNode candidate = value;
        if (value != null && value.isArray() && !value.isEmpty()) {
            candidate = value.get(0);
        }
        return candidate != null && candidate.isTextual() ? normalize(candidate.asText()) : null;
    }

    private List<DomainRuleExplanationEvidenceProjection.SafeTimelineEvent> safeEvents(
            DomainRuleTimelineResponse timeline) {
        if (timeline == null || timeline.events() == null) {
            return List.of();
        }
        return timeline.events().stream()
                .filter(Objects::nonNull)
                .filter(event -> "safe".equals(lower(event.visibility())))
                .sorted(Comparator
                        .comparing(
                                DomainRuleTimelineEventResponse::occurredAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(
                                DomainRuleTimelineEventResponse::eventType,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(event -> new DomainRuleExplanationEvidenceProjection.SafeTimelineEvent(
                        event.eventType(),
                        event.occurredAt(),
                        event.summary(),
                        event.status(),
                        event.targetLayer(),
                        event.targetArtifactType(),
                        event.targetArtifactKey(),
                        event.materializationId(),
                        event.materializationKey(),
                        event.sourceHash()))
                .toList();
    }

    private List<DomainRuleExplanationEvidenceProjection.MaterializationEvidence> safeMaterializations(
            List<DomainRuleMaterializationResponse> materializations) {
        if (materializations == null) {
            return List.of();
        }
        return materializations.stream()
                .filter(Objects::nonNull)
                .map(materialization -> new DomainRuleExplanationEvidenceProjection.MaterializationEvidence(
                        materialization.id(),
                        materialization.materializationKey(),
                        materialization.targetLayer(),
                        materialization.targetArtifactType(),
                        materialization.targetArtifactKey(),
                        materialization.status(),
                        materialization.sourceHash(),
                        materialization.appliedAt()))
                .toList();
    }

    private DomainRuleDefinition fingerprintSource(DomainRuleDefinitionResponse response) {
        return DomainRuleDefinition.builder()
                .id(response.id())
                .ruleKey(response.ruleKey())
                .version(response.version())
                .definition(json(response.definition(), "{}"))
                .parameters(json(response.parameters(), "{}"))
                .condition(response.condition() == null ? null : response.condition().toString())
                .governance(json(response.governance(), "{}"))
                .build();
    }

    private String definitionRef(DomainRuleDefinitionResponse definition, String definitionHash) {
        return "domain-rule-definition:%s@v%s#%s".formatted(
                definition.id(), definition.version(), definitionHash);
    }

    private String conditionRef(DomainRuleDefinitionResponse definition, String conditionHash) {
        return "domain-rule-condition:%s@v%s#%s".formatted(
                definition.id(), definition.version(), conditionHash);
    }

    private String json(JsonNode value, String fallback) {
        return value == null || value.isNull() ? fallback : value.toString();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? normalize(value.asText()) : null;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String safeRefPart(String value) {
        String normalized = normalize(value);
        return normalized == null ? "none" : normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record ExposurePolicy(
            String mode,
            boolean exposeAst,
            boolean exposeFactPaths) {

        static ExposurePolicy full() {
            return new ExposurePolicy("full", true, true);
        }

        static ExposurePolicy masked() {
            return new ExposurePolicy("masked", false, false);
        }

        static ExposurePolicy summaryOnly() {
            return new ExposurePolicy("summary_only", false, true);
        }

        static ExposurePolicy denied() {
            return new ExposurePolicy("denied", false, false);
        }
    }

    private record ConditionInspection(
            boolean valid,
            List<String> operators,
            List<String> factPaths,
            List<DomainRuleExplanationEvidenceProjection.ValidationIssue> validationIssues) {
    }
}
