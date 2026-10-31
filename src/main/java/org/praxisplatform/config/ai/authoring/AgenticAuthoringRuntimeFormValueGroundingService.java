package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;
import org.springframework.util.StringUtils;

/**
 * Grounds and validates runtime form value proposals against the canonical creation ({@code POST})
 * request schema in {@code /schemas/filtered} (or a governed schema artifact for standalone manual
 * forms), enforcing {@code x-ui.fieldAccess}, {@code @DomainGovernance} / {@code AiUsagePolicy},
 * {@code ReactiveDetermination} output target protection, masked/redacted value rejection, and
 * creation-schema {@code optionSource} / {@code RESOURCE_ENTITY} lookup verification.
 *
 * <p>Never infers write eligibility from a filter schema ({@code /filter}) or from browser-supplied
 * {@code allowedFields} alone.</p>
 */
public final class AgenticAuthoringRuntimeFormValueGroundingService {

    public static final String PROPOSAL_SCHEMA_VERSION = "praxis-runtime-form-value-proposal.v1";

    @FunctionalInterface
    public interface CreationOptionSourceLookupVerifier {
        LookupVerificationResult verify(
                String resourcePath,
                String fieldPath,
                JsonNode optionSourceNode,
                JsonNode proposedValue,
                AiPrincipalContext principalContext,
                String requestBaseUrl);
    }

    public record LookupVerificationResult(
            boolean valid,
            String optionLabel,
            String reasonCode,
            String reasonMessage) {

        public static LookupVerificationResult ok(String optionLabel) {
            return new LookupVerificationResult(true, optionLabel, null, null);
        }

        public static LookupVerificationResult rejected(String reasonCode, String reasonMessage) {
            return new LookupVerificationResult(false, null, reasonCode, reasonMessage);
        }
    }

    public record GroundingResult(
            boolean valid,
            String blockReason,
            String blockMessage,
            ObjectNode proposal,
            Set<String> writableFields,
            Map<String, String> nonOverwritableReasons) {
    }

    private final ObjectMapper objectMapper;
    private final SchemaRetrievalService schemaRetrievalService;
    private final CreationOptionSourceLookupVerifier lookupVerifier;

    public AgenticAuthoringRuntimeFormValueGroundingService(
            ObjectMapper objectMapper,
            SchemaRetrievalService schemaRetrievalService) {
        this(objectMapper, schemaRetrievalService, null);
    }

    public AgenticAuthoringRuntimeFormValueGroundingService(
            ObjectMapper objectMapper,
            SchemaRetrievalService schemaRetrievalService,
            CreationOptionSourceLookupVerifier lookupVerifier) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.schemaRetrievalService = schemaRetrievalService;
        this.lookupVerifier = lookupVerifier;
    }

    public GroundingResult groundProposal(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode candidateAssignmentsNode,
            AiPrincipalContext principalContext,
            String requestBaseUrl) {
        JsonNode hints = request == null ? null : request.contextHints();
        JsonNode assistanceHint = hints == null ? null : hints.path("runtimeValueAssistance");
        JsonNode targetIdentityNode = assistanceHint != null && assistanceHint.path("targetIdentity").isObject()
                ? assistanceHint.path("targetIdentity")
                : buildDefaultTargetIdentity(request);

        String mode = normalizeMode(targetIdentityNode.path("mode").asText("create"));
        String retentionPolicy = normalizeRetentionPolicy(
                assistanceHint == null ? "" : assistanceHint.path("retentionPolicy").asText("standard"));
        String applyMode = normalizeApplyMode(
                assistanceHint == null ? "" : assistanceHint.path("applyMode").asText("empty-only"));

        if ("view".equals(mode)) {
            return blockedResult(
                    targetIdentityNode,
                    applyMode,
                    retentionPolicy,
                    "view-mode-read-only",
                    "O formulário está em modo somente leitura e não aceita preenchimento.");
        }
        if (assistanceHint != null && assistanceHint.path("autoSaveEnabled").asBoolean(false)) {
            return blockedResult(
                    targetIdentityNode,
                    applyMode,
                    retentionPolicy,
                    "auto-save-enabled",
                    "A aplicação automática está bloqueada porque o salvamento automático (enableAutoSave) está ativo neste formulário.");
        }

        ResolvedCreationSchema resolvedSchema = resolveCreationSchema(
                targetIdentityNode,
                assistanceHint,
                principalContext,
                requestBaseUrl);
        if (!resolvedSchema.valid()) {
            return blockedResult(
                    targetIdentityNode,
                    applyMode,
                    retentionPolicy,
                    resolvedSchema.errorCode(),
                    resolvedSchema.errorMessage());
        }

        JsonNode schema = resolvedSchema.schema();
        JsonNode properties = resolveSchemaProperties(schema);
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            return blockedResult(
                    targetIdentityNode,
                    applyMode,
                    retentionPolicy,
                    "creation-schema-properties-empty",
                    "O schema de criação do formulário não declarou propriedades graváveis.");
        }

        Set<String> reactiveTargets = extractReactiveDeterminationTargets(schema, assistanceHint);
        Map<String, String> runtimeClientNonOverwritable = extractClientNonOverwritableReasons(assistanceHint);
        Set<String> writableFields = new LinkedHashSet<>();
        Map<String, String> nonOverwritableReasons = new LinkedHashMap<>();

        Iterator<Map.Entry<String, JsonNode>> fieldsIterator = properties.fields();
        while (fieldsIterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = fieldsIterator.next();
            String fieldPath = entry.getKey();
            JsonNode prop = entry.getValue();
            String nonOverwritableReason = classifyNonOverwritableReason(fieldPath, prop, reactiveTargets);
            if (nonOverwritableReason == null) {
                nonOverwritableReason = runtimeClientNonOverwritable.get(fieldPath);
            }
            if (nonOverwritableReason != null) {
                nonOverwritableReasons.put(fieldPath, nonOverwritableReason);
            } else {
                writableFields.add(fieldPath);
            }
        }
        for (Map.Entry<String, String> clientEntry : runtimeClientNonOverwritable.entrySet()) {
            nonOverwritableReasons.putIfAbsent(clientEntry.getKey(), clientEntry.getValue());
            writableFields.remove(clientEntry.getKey());
        }

        ArrayNode assignments = objectMapper.createArrayNode();
        ArrayNode rejectedFields = objectMapper.createArrayNode();
        ArrayNode warnings = objectMapper.createArrayNode();
        Set<String> verifiedLookupFields = new LinkedHashSet<>();

        List<CandidateAssignment> candidateList = extractCandidateAssignments(
                candidateAssignmentsNode,
                properties,
                writableFields,
                assistanceHint);
        for (CandidateAssignment candidate : candidateList) {
            String fieldPath = candidate.fieldPath();
            JsonNode proposedValue = candidate.proposedValue();
            JsonNode prop = properties.path(fieldPath);
            String label = extractFieldLabel(fieldPath, prop, candidate.label());

            if (prop.isMissingNode() || !prop.isObject()) {
                rejectedFields.add(buildAssignmentNode(
                        fieldPath,
                        label,
                        proposedValue,
                        candidate.provenance(),
                        candidate.syntheticData(),
                        null,
                        null,
                        "rejected",
                        "not-in-creation-schema",
                        "O campo não pertence ao contrato de criação (POST) autorizado do formulário."));
                continue;
            }

            String nonOverwritableReason = nonOverwritableReasons.get(fieldPath);
            if (nonOverwritableReason != null) {
                rejectedFields.add(buildAssignmentNode(
                        fieldPath,
                        label,
                        proposedValue,
                        candidate.provenance(),
                        candidate.syntheticData(),
                        null,
                        null,
                        "rejected",
                        nonOverwritableReason,
                        reasonMessageFor(nonOverwritableReason)));
                continue;
            }

            if (AgenticAuthoringIngressRetentionPreflight.isMaskedOrRedactedValue(proposedValue)) {
                rejectedFields.add(buildAssignmentNode(
                        fieldPath,
                        label,
                        proposedValue,
                        candidate.provenance(),
                        candidate.syntheticData(),
                        null,
                        null,
                        "rejected",
                        "masked-or-redacted-value",
                        "Valores mascarados ou redigidos não podem ser aplicados aos controles como dados íntegros."));
                continue;
            }

            JsonNode xUi = prop.path("x-ui");
            JsonNode optionSource = xUi.path("optionSource");
            String optionSourceKey = optionSource.path("key").asText(
                    optionSource.path("name").asText(null));
            String resolvedOptionLabel = candidate.optionLabel();
            String effectiveProvenance = candidate.provenance();
            boolean effectiveSyntheticData = candidate.syntheticData();

            if (hasStaticEnumOrOptions(prop, optionSource)) {
                LookupVerificationResult staticCheck = verifyStaticOption(prop, optionSource, proposedValue);
                if (!staticCheck.valid()) {
                    rejectedFields.add(buildAssignmentNode(
                            fieldPath,
                            label,
                            proposedValue,
                            effectiveProvenance,
                            effectiveSyntheticData,
                            null,
                            optionSourceKey,
                            "rejected",
                            staticCheck.reasonCode(),
                            staticCheck.reasonMessage()));
                    continue;
                }
                if (StringUtils.hasText(staticCheck.optionLabel())) {
                    resolvedOptionLabel = staticCheck.optionLabel();
                }
            } else if (isEntityOrRemoteOptionSource(optionSource)) {
                LookupVerificationResult lookupCheck = verifyCreationOptionSourceLookup(
                        resolvedSchema.resourcePath(),
                        fieldPath,
                        optionSource,
                        assistanceHint,
                        proposedValue,
                        principalContext,
                        requestBaseUrl);
                if (!lookupCheck.valid()) {
                    rejectedFields.add(buildAssignmentNode(
                            fieldPath,
                            label,
                            proposedValue,
                            effectiveProvenance,
                            effectiveSyntheticData,
                            null,
                            optionSourceKey,
                            "rejected",
                            lookupCheck.reasonCode(),
                            lookupCheck.reasonMessage()));
                    continue;
                }
                verifiedLookupFields.add(fieldPath);
                if (StringUtils.hasText(lookupCheck.optionLabel())) {
                    resolvedOptionLabel = lookupCheck.optionLabel();
                }
                effectiveProvenance = "authorized-option-source";
                effectiveSyntheticData = false;
            }

            assignments.add(buildAssignmentNode(
                    fieldPath,
                    label,
                    proposedValue,
                    effectiveProvenance,
                    effectiveSyntheticData,
                    resolvedOptionLabel,
                    optionSourceKey,
                    "proposed",
                    null,
                    null));
        }

        if (!rejectedFields.isEmpty()) {
            warnings.add("Alguns campos foram rejeitados pela governança do contrato de criação e não serão aplicados.");
        }

        ObjectNode proposal = objectMapper.createObjectNode();
        proposal.put("schemaVersion", PROPOSAL_SCHEMA_VERSION);
        proposal.put("proposalId", "rfvp-" + UUID.randomUUID());
        proposal.set("targetIdentity", normalizeTargetIdentity(targetIdentityNode, resolvedSchema));
        proposal.put("applyMode", applyMode);
        proposal.put("retentionPolicy", retentionPolicy);
        boolean hasSyntheticAssignments = false;
        for (JsonNode assignment : assignments) {
            if (assignment.path("syntheticData").asBoolean(false)
                    || "synthetic-example".equals(assignment.path("provenance").asText(""))) {
                hasSyntheticAssignments = true;
                break;
            }
        }
        String customNotice = assistanceHint == null ? "" : assistanceHint.path("syntheticExampleNotice").asText("");
        if (StringUtils.hasText(customNotice)) {
            proposal.put("syntheticExampleNotice", customNotice.trim());
        } else if (hasSyntheticAssignments || assignments.isEmpty()) {
            proposal.put(
                    "syntheticExampleNotice",
                    "Valores sintéticos de exemplo gerados para revisão. Confirme cada campo antes de aplicar.");
        }

        ObjectNode grounding = proposal.putObject("grounding");
        grounding.put("operation", resolvedSchema.operation());
        grounding.put("schemaPath", resolvedSchema.schemaPath());
        if (StringUtils.hasText(resolvedSchema.schemaHash())) {
            grounding.put("schemaHash", resolvedSchema.schemaHash());
        }
        grounding.put("verifiedAt", Instant.now().toString());
        ArrayNode verifiedLookupsNode = grounding.putArray("verifiedLookupFields");
        verifiedLookupFields.forEach(verifiedLookupsNode::add);

        proposal.set("assignments", assignments);
        proposal.set("rejectedFields", rejectedFields);
        proposal.set("warnings", warnings);

        boolean canApply = !assignments.isEmpty();
        return new GroundingResult(
                canApply,
                canApply ? null : "no-writable-fields",
                canApply
                        ? null
                        : "Nenhum campo proposto passou na validação do contrato de criação (POST) do formulário.",
                proposal,
                Set.copyOf(writableFields),
                Map.copyOf(nonOverwritableReasons));
    }

    private ResolvedCreationSchema resolveCreationSchema(
            JsonNode targetIdentityNode,
            JsonNode assistanceHint,
            AiPrincipalContext principalContext,
            String requestBaseUrl) {
        String resourcePath = targetIdentityNode.path("resourcePath").asText("");
        if (!StringUtils.hasText(resourcePath) && assistanceHint != null) {
            resourcePath = assistanceHint.path("resourcePath").asText("");
        }

        if (StringUtils.hasText(resourcePath)) {
            String trimmedPath = resourcePath.trim();
            if (trimmedPath.endsWith("/filter") || trimmedPath.contains("/filter?")) {
                return ResolvedCreationSchema.failure(
                        "filter-schema-forbidden-for-creation-grounding",
                        "É proibido inferir elegibilidade de escrita a partir do schema de filtro (/filter). Use o contrato de criação (POST).");
            }
            if (schemaRetrievalService == null) {
                return ResolvedCreationSchema.failure(
                        "creation-schema-service-unavailable",
                        "O serviço de resolução de schema (/schemas/filtered) não está disponível.");
            }
            String normalizedPath = normalizeResourcePath(trimmedPath);
            SchemaFetchResult fetchResult = schemaRetrievalService.fetchSchemaResult(
                    AiSchemaContext.builder()
                            .path(normalizedPath)
                            .operation("post")
                            .schemaType("request")
                            .build(),
                    requestBaseUrl,
                    principalContext == null ? null : principalContext.tenantId(),
                    principalContext == null ? null : principalContext.userId(),
                    principalContext == null ? null : principalContext.environment());
            if (!fetchResult.isSuccess() || fetchResult.getSchema() == null) {
                return ResolvedCreationSchema.failure(
                        "creation-schema-unavailable",
                        "Não foi possível resolver o schema da operação de criação (POST) em /schemas/filtered.");
            }
            JsonNode schema = fetchResult.getSchema();
            String schemaHash = schema.path("x-schema-hash").asText(
                    targetIdentityNode.path("schemaHash").asText(""));
            return ResolvedCreationSchema.success(
                    schema,
                    normalizedPath,
                    normalizedPath,
                    "post",
                    schemaHash);
        }

        JsonNode governedSchema = assistanceHint == null ? null : assistanceHint.path("governedSchema");
        if (governedSchema != null && governedSchema.isObject() && !governedSchema.isEmpty()) {
            String schemaHash = governedSchema.path("x-schema-hash").asText(
                    targetIdentityNode.path("schemaHash").asText("governed-manual-schema"));
            return ResolvedCreationSchema.success(
                    governedSchema,
                    "",
                    "governed-artifact",
                    "governed-artifact",
                    schemaHash);
        }

        return ResolvedCreationSchema.failure(
                "creation-schema-required",
                "Preenchimento assistido exige contrato de criação (POST em /schemas/filtered) ou schema governado explícito; allowedFields do browser não é aceito isoladamente.");
    }

    private String classifyNonOverwritableReason(
            String fieldPath,
            JsonNode prop,
            Set<String> reactiveTargets) {
        if (prop == null || !prop.isObject()) {
            return "read-only";
        }
        JsonNode xUi = prop.path("x-ui");
        if (reactiveTargets.contains(fieldPath)
                || xUi.path("reactiveDeterminationTarget").asBoolean(false)) {
            return "reactive-determination-target";
        }
        if (prop.path("readOnly").asBoolean(false)
                || xUi.path("readOnly").asBoolean(false)
                || (xUi.path("editable").isBoolean() && !xUi.path("editable").asBoolean())) {
            return "read-only";
        }
        if (xUi.path("disabled").asBoolean(false)) {
            return "disabled";
        }
        if (xUi.path("hidden").asBoolean(false)
                || xUi.path("formHidden").asBoolean(false)) {
            return "hidden";
        }
        JsonNode fieldAccess = xUi.path("fieldAccess");
        if (fieldAccess.isObject()) {
            if (fieldAccess.path("readOnly").asBoolean(false)
                    || (fieldAccess.path("canWrite").isBoolean() && !fieldAccess.path("canWrite").asBoolean())
                    || (fieldAccess.path("canCreate").isBoolean() && !fieldAccess.path("canCreate").asBoolean())
                    || (fieldAccess.path("editable").isBoolean() && !fieldAccess.path("editable").asBoolean())) {
                return "restricted-by-field-access";
            }
            String accessMode = fieldAccess.path("accessMode").asText("").trim().toUpperCase(Locale.ROOT);
            if ("READ_ONLY".equals(accessMode)
                    || "MASKED".equals(accessMode)
                    || "HIDDEN".equals(accessMode)
                    || "DENIED".equals(accessMode)) {
                return "restricted-by-field-access";
            }
        }
        JsonNode governance = prop.path("x-domain-governance");
        if (governance.isObject()) {
            String visibility = governance.path("aiUsage").path("visibility").asText("").trim().toLowerCase(Locale.ROOT);
            if ("deny".equals(visibility)
                    || "hide".equals(visibility)
                    || "redact".equals(visibility)
                    || "never".equals(visibility)) {
                return "restricted-by-ai-policy";
            }
            String aiUsagePolicy = governance.path("aiUsagePolicy").asText("").trim().toUpperCase(Locale.ROOT);
            if ("DENIED".equals(aiUsagePolicy)
                    || "READ_ONLY".equals(aiUsagePolicy)
                    || "REDACTED".equals(aiUsagePolicy)
                    || "NEVER".equals(aiUsagePolicy)
                    || "MASKED".equals(aiUsagePolicy)) {
                return "restricted-by-ai-policy";
            }
            if ((governance.path("aiUsage").path("allowWrite").isBoolean()
                            && !governance.path("aiUsage").path("allowWrite").asBoolean())
                    || (governance.path("aiUsage").path("allowGeneration").isBoolean()
                            && !governance.path("aiUsage").path("allowGeneration").asBoolean())) {
                return "restricted-by-ai-policy";
            }
        }
        return null;
    }

    private Map<String, String> extractClientNonOverwritableReasons(JsonNode assistanceHint) {
        Map<String, String> reasons = new LinkedHashMap<>();
        if (assistanceHint == null || !assistanceHint.path("nonOverwritableFields").isArray()) {
            return reasons;
        }
        for (JsonNode entry : assistanceHint.path("nonOverwritableFields")) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            String fieldPath = entry.path("fieldPath").asText("").trim();
            String reason = entry.path("reason").asText("").trim();
            if (StringUtils.hasText(fieldPath) && StringUtils.hasText(reason)) {
                reasons.put(fieldPath, reason);
            }
        }
        return reasons;
    }

    private Set<String> extractReactiveDeterminationTargets(JsonNode schema, JsonNode assistanceHint) {
        Set<String> targets = new LinkedHashSet<>();
        if (assistanceHint != null && assistanceHint.path("reactiveDeterminationTargets").isArray()) {
            for (JsonNode item : assistanceHint.path("reactiveDeterminationTargets")) {
                if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                    targets.add(item.asText().trim());
                }
            }
        }
        JsonNode determinations = schema == null ? null : schema.path("x-ui").path("reactiveDeterminations");
        if (determinations != null && determinations.isArray()) {
            for (JsonNode det : determinations) {
                if (det == null || !det.isObject()) {
                    continue;
                }
                addIfText(targets, det.path("targetField"));
                addIfText(targets, det.path("fieldPath"));
                addIfText(targets, det.path("outputField"));
                JsonNode outputs = det.path("outputs");
                if (outputs.isArray()) {
                    for (JsonNode out : outputs) {
                        if (out.isTextual()) {
                            addIfText(targets, out);
                        } else if (out.isObject()) {
                            addIfText(targets, out.path("fieldPath"));
                            addIfText(targets, out.path("targetField"));
                            addIfText(targets, out.path("field"));
                        }
                    }
                }
            }
        }
        return targets;
    }

    private boolean hasStaticEnumOrOptions(JsonNode prop, JsonNode optionSource) {
        if (prop.path("enum").isArray() && !prop.path("enum").isEmpty()) {
            return true;
        }
        return optionSource != null
                && optionSource.isObject()
                && optionSource.path("options").isArray()
                && !optionSource.path("options").isEmpty();
    }

    private LookupVerificationResult verifyStaticOption(
            JsonNode prop,
            JsonNode optionSource,
            JsonNode proposedValue) {
        if (proposedValue == null || proposedValue.isNull()) {
            return LookupVerificationResult.rejected(
                    "invalid-static-option-value",
                    "O valor proposto para o campo de seleção é nulo ou inválido.");
        }
        String candidateText = proposedValue.asText("").trim();
        JsonNode options = optionSource != null && optionSource.path("options").isArray()
                ? optionSource.path("options")
                : null;
        if (options != null && !options.isEmpty()) {
            for (JsonNode option : options) {
                if (option.isValueNode() && candidateText.equals(option.asText("").trim())) {
                    return LookupVerificationResult.ok(option.asText(""));
                }
                if (option.isObject()) {
                    String id = option.path("id").asText(option.path("value").asText(option.path("code").asText(""))).trim();
                    String label = option.path("label").asText(option.path("description").asText(id)).trim();
                    if (StringUtils.hasText(id) && id.equals(candidateText)) {
                        return LookupVerificationResult.ok(label);
                    }
                }
            }
            return LookupVerificationResult.rejected(
                    "invalid-static-option-value",
                    "O valor proposto não pertence às opções permitidas no contrato de criação.");
        }
        JsonNode enumNode = prop.path("enum");
        if (enumNode.isArray()) {
            for (JsonNode item : enumNode) {
                if (candidateText.equals(item.asText("").trim())) {
                    return LookupVerificationResult.ok(candidateText);
                }
            }
        }
        return LookupVerificationResult.rejected(
                "invalid-static-option-value",
                "O valor proposto não pertence ao conjunto enum permitido no contrato de criação.");
    }

    private boolean isEntityOrRemoteOptionSource(JsonNode optionSource) {
        if (optionSource == null || !optionSource.isObject() || optionSource.isEmpty()) {
            return false;
        }
        String type = optionSource.path("type").asText("").trim().toUpperCase(Locale.ROOT);
        if ("RESOURCE_ENTITY".equals(type) || "REMOTE".equals(type) || "ENTITY_LOOKUP".equals(type)) {
            return true;
        }
        return StringUtils.hasText(optionSource.path("byIdsEndpoint").asText(""))
                || StringUtils.hasText(optionSource.path("filterEndpoint").asText(""))
                || optionSource.path("entityLookup").isObject();
    }

    private LookupVerificationResult verifyCreationOptionSourceLookup(
            String resourcePath,
            String fieldPath,
            JsonNode optionSource,
            JsonNode assistanceHint,
            JsonNode proposedValue,
            AiPrincipalContext principalContext,
            String requestBaseUrl) {
        if (proposedValue == null || proposedValue.isNull() || proposedValue.isContainerNode()) {
            return LookupVerificationResult.rejected(
                    "invalid-option-source-id",
                    "O identificador de lookup proposto deve ser um valor escalar válido na fonte de criação.");
        }
        String byIdsEndpoint = optionSource.path("byIdsEndpoint").asText("");
        String filterEndpoint = optionSource.path("filterEndpoint").asText("");
        if (!StringUtils.hasText(byIdsEndpoint) && !StringUtils.hasText(filterEndpoint)) {
            return LookupVerificationResult.rejected(
                    "option-source-endpoint-missing-in-creation-schema",
                    "O campo de lookup não possui endpoint autorizado no contrato de criação (POST).");
        }

        if (lookupVerifier != null) {
            return lookupVerifier.verify(
                    resourcePath,
                    fieldPath,
                    optionSource,
                    proposedValue,
                    principalContext,
                    requestBaseUrl);
        }

        JsonNode authorizedCandidates = assistanceHint == null
                ? null
                : assistanceHint.path("authorizedOptionCandidates").path(fieldPath);
        if (authorizedCandidates != null && authorizedCandidates.isArray()) {
            String proposedIdText = proposedValue.asText("").trim();
            Set<String> allowedStatuses = extractStatusSet(
                    optionSource.path("entityLookup").path("selectionPolicy").path("allowedStatuses"));
            Set<String> blockedStatuses = extractStatusSet(
                    optionSource.path("entityLookup").path("selectionPolicy").path("blockedStatuses"));

            for (JsonNode candidate : authorizedCandidates) {
                if (candidate == null || !candidate.isObject()) {
                    continue;
                }
                String idText = candidate.path("id").asText(candidate.path("value").asText("")).trim();
                if (!proposedIdText.equals(idText)) {
                    continue;
                }
                String status = candidate.path("status").asText("").trim().toUpperCase(Locale.ROOT);
                if (StringUtils.hasText(status)) {
                    if (blockedStatuses.contains(status)
                            || (!allowedStatuses.isEmpty() && !allowedStatuses.contains(status))) {
                        return LookupVerificationResult.rejected(
                                "option-source-selection-policy-blocked",
                                "O registro de lookup selecionado está bloqueado pela política de seleção (LookupSelectionPolicy) do contrato de criação.");
                    }
                }
                String label = candidate.path("label").asText(candidate.path("description").asText(idText));
                return LookupVerificationResult.ok(label);
            }
            return LookupVerificationResult.rejected(
                    "invalid-option-source-id",
                    "O identificador proposto não existe na fonte de opções autorizada da operação de criação (POST).");
        }

        return LookupVerificationResult.rejected(
                "unverified-option-source-id",
                "Não foi possível validar o identificador de lookup na fonte autorizada da operação de criação (POST).");
    }

    private Set<String> extractStatusSet(JsonNode arrayNode) {
        Set<String> set = new LinkedHashSet<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                    set.add(item.asText().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return set;
    }

    private List<CandidateAssignment> extractCandidateAssignments(
            JsonNode candidateAssignmentsNode,
            JsonNode properties,
            Set<String> writableFields,
            JsonNode assistanceHint) {
        List<CandidateAssignment> list = new ArrayList<>();
        if (candidateAssignmentsNode != null && candidateAssignmentsNode.isArray()) {
            for (JsonNode item : candidateAssignmentsNode) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String fieldPath = item.path("fieldPath").asText(item.path("field").asText("")).trim();
                if (!StringUtils.hasText(fieldPath)) {
                    continue;
                }
                JsonNode proposedValue = item.has("proposedValue")
                        ? item.get("proposedValue")
                        : item.get("value");
                String label = item.path("label").asText(null);
                String provenance = item.path("provenance").asText("synthetic-example");
                boolean syntheticData = !item.path("syntheticData").isBoolean()
                        || item.path("syntheticData").asBoolean(true);
                String optionLabel = item.path("optionLabel").asText(null);
                list.add(new CandidateAssignment(
                        fieldPath,
                        label,
                        proposedValue,
                        provenance,
                        syntheticData,
                        optionLabel));
            }
            return list;
        }
        if (candidateAssignmentsNode != null && candidateAssignmentsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = candidateAssignmentsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                list.add(new CandidateAssignment(
                        entry.getKey(),
                        null,
                        entry.getValue(),
                        "synthetic-example",
                        true,
                        null));
            }
            return list;
        }
        for (String writableField : writableFields) {
            JsonNode prop = properties.path(writableField);
            JsonNode optionSource = prop.path("x-ui").path("optionSource");
            if (isEntityOrRemoteOptionSource(optionSource)) {
                CandidateAssignment authorizedLookup = synthesizeAuthorizedLookupCandidate(
                        writableField,
                        prop,
                        optionSource,
                        assistanceHint);
                if (authorizedLookup != null) {
                    list.add(authorizedLookup);
                }
                continue;
            }
            JsonNode syntheticValue = synthesizeSafeExampleValue(writableField, prop);
            if (syntheticValue != null && !syntheticValue.isNull()) {
                list.add(new CandidateAssignment(
                        writableField,
                        extractFieldLabel(writableField, prop, null),
                        syntheticValue,
                        "synthetic-example",
                        true,
                        null));
            }
        }
        return list;
    }

    private CandidateAssignment synthesizeAuthorizedLookupCandidate(
            String fieldPath,
            JsonNode prop,
            JsonNode optionSource,
            JsonNode assistanceHint) {
        if (assistanceHint == null || !assistanceHint.isObject()) {
            return null;
        }
        JsonNode authorizedCandidates = assistanceHint.path("authorizedOptionCandidates").path(fieldPath);
        if (authorizedCandidates == null || !authorizedCandidates.isArray() || authorizedCandidates.isEmpty()) {
            return null;
        }
        Set<String> allowedStatuses = extractStatusSet(
                optionSource.path("entityLookup").path("selectionPolicy").path("allowedStatuses"));
        Set<String> blockedStatuses = extractStatusSet(
                optionSource.path("entityLookup").path("selectionPolicy").path("blockedStatuses"));
        for (JsonNode candidate : authorizedCandidates) {
            if (candidate == null || !candidate.isObject()) {
                continue;
            }
            JsonNode idNode = candidate.has("id") ? candidate.get("id") : candidate.get("value");
            if (idNode == null || idNode.isNull() || !StringUtils.hasText(idNode.asText(""))) {
                continue;
            }
            String status = candidate.path("status").asText("").trim().toUpperCase(Locale.ROOT);
            if (StringUtils.hasText(status)) {
                if (blockedStatuses.contains(status)
                        || (!allowedStatuses.isEmpty() && !allowedStatuses.contains(status))) {
                    continue;
                }
            }
            String optionLabel = candidate.path("label").asText(
                    candidate.path("description").asText(idNode.asText("")));
            return new CandidateAssignment(
                    fieldPath,
                    extractFieldLabel(fieldPath, prop, null),
                    idNode,
                    "authorized-option-source",
                    false,
                    optionLabel);
        }
        return null;
    }

    private JsonNode synthesizeSafeExampleValue(String fieldPath, JsonNode prop) {
        if (prop.path("enum").isArray() && !prop.path("enum").isEmpty()) {
            return prop.path("enum").get(0);
        }
        JsonNode options = prop.path("x-ui").path("optionSource").path("options");
        if (options.isArray() && !options.isEmpty()) {
            JsonNode first = options.get(0);
            if (first.isObject()) {
                return first.has("id") ? first.get("id") : first.get("value");
            }
            return first;
        }
        String type = prop.path("type").asText("string").toLowerCase(Locale.ROOT);
        String format = prop.path("format").asText("").toLowerCase(Locale.ROOT);
        if ("boolean".equals(type)) {
            return objectMapper.getNodeFactory().booleanNode(true);
        }
        if ("integer".equals(type) || "number".equals(type)) {
            return objectMapper.getNodeFactory().numberNode(1);
        }
        if ("date".equals(format)) {
            return objectMapper.getNodeFactory().textNode("2026-01-15");
        }
        if ("email".equals(format) || fieldPath.toLowerCase(Locale.ROOT).contains("email")) {
            return objectMapper.getNodeFactory().textNode("exemplo.sintetico@empresa.com.br");
        }
        String label = extractFieldLabel(fieldPath, prop, fieldPath);
        return objectMapper.getNodeFactory().textNode("Exemplo - " + label);
    }

    private ObjectNode buildAssignmentNode(
            String fieldPath,
            String label,
            JsonNode proposedValue,
            String provenance,
            boolean syntheticData,
            String optionLabel,
            String optionSourceKey,
            String status,
            String reasonCode,
            String reasonMessage) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("fieldPath", fieldPath);
        node.put("label", StringUtils.hasText(label) ? label : fieldPath);
        if (proposedValue != null) {
            node.set("proposedValue", proposedValue.deepCopy());
        } else {
            node.putNull("proposedValue");
        }
        node.put("provenance", StringUtils.hasText(provenance) ? provenance : "synthetic-example");
        node.put("syntheticData", syntheticData);
        if (StringUtils.hasText(optionLabel)) {
            node.put("optionLabel", optionLabel);
        }
        if (StringUtils.hasText(optionSourceKey)) {
            node.put("optionSourceKey", optionSourceKey);
        }
        node.put("status", status);
        if (StringUtils.hasText(reasonCode)) {
            node.put("reasonCode", reasonCode);
        }
        if (StringUtils.hasText(reasonMessage)) {
            node.put("reasonMessage", reasonMessage);
        }
        return node;
    }

    private GroundingResult blockedResult(
            JsonNode targetIdentityNode,
            String applyMode,
            String retentionPolicy,
            String blockReason,
            String blockMessage) {
        ObjectNode proposal = objectMapper.createObjectNode();
        proposal.put("schemaVersion", PROPOSAL_SCHEMA_VERSION);
        proposal.put("proposalId", "rfvp-" + UUID.randomUUID());
        proposal.set("targetIdentity", targetIdentityNode.deepCopy());
        proposal.put("applyMode", applyMode);
        proposal.put("retentionPolicy", retentionPolicy);
        proposal.putArray("assignments");
        proposal.putArray("rejectedFields");
        ArrayNode warnings = proposal.putArray("warnings");
        if (StringUtils.hasText(blockMessage)) {
            warnings.add(blockMessage);
        }
        return new GroundingResult(
                false,
                blockReason,
                blockMessage,
                proposal,
                Set.of(),
                Map.of());
    }

    private ObjectNode buildDefaultTargetIdentity(AgenticAuthoringTurnStreamRequest request) {
        ObjectNode node = objectMapper.createObjectNode();
        String componentId = request != null && StringUtils.hasText(request.targetComponentId())
                ? request.targetComponentId()
                : "praxis-dynamic-form";
        node.put("componentId", componentId);
        node.put("instanceId", componentId);
        node.put("mode", "create");
        node.putNull("entityId");
        node.put("valueRevision", 0);
        return node;
    }

    private ObjectNode normalizeTargetIdentity(JsonNode targetIdentityNode, ResolvedCreationSchema resolvedSchema) {
        ObjectNode copy = targetIdentityNode != null && targetIdentityNode.isObject()
                ? (ObjectNode) targetIdentityNode.deepCopy()
                : objectMapper.createObjectNode();
        if (!StringUtils.hasText(copy.path("componentId").asText(""))) {
            copy.put("componentId", "praxis-dynamic-form");
        }
        if (!StringUtils.hasText(copy.path("instanceId").asText(""))) {
            copy.put("instanceId", copy.path("componentId").asText("praxis-dynamic-form"));
        }
        copy.put("mode", normalizeMode(copy.path("mode").asText("create")));
        if (!copy.has("valueRevision") || !copy.path("valueRevision").isNumber()) {
            copy.put("valueRevision", 0);
        }
        if (StringUtils.hasText(resolvedSchema.schemaHash())
                && !StringUtils.hasText(copy.path("schemaHash").asText(""))) {
            copy.put("schemaHash", resolvedSchema.schemaHash());
        }
        return copy;
    }

    private JsonNode resolveSchemaProperties(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return null;
        }
        if (schema.path("properties").isObject()) {
            return schema.path("properties");
        }
        if (schema.path("schema").path("properties").isObject()) {
            return schema.path("schema").path("properties");
        }
        return null;
    }

    private String extractFieldLabel(String fieldPath, JsonNode prop, String candidateLabel) {
        if (StringUtils.hasText(candidateLabel)) {
            return candidateLabel.trim();
        }
        if (prop != null && prop.isObject()) {
            String xUiLabel = prop.path("x-ui").path("label").asText("");
            if (StringUtils.hasText(xUiLabel)) {
                return xUiLabel.trim();
            }
            String title = prop.path("title").asText("");
            if (StringUtils.hasText(title)) {
                return title.trim();
            }
        }
        return fieldPath;
    }

    private String reasonMessageFor(String reason) {
        return switch (reason) {
            case "read-only" -> "Campo somente leitura no contrato de criação (POST) não pode ser sobrescrito.";
            case "disabled" -> "Campo desabilitado no contrato de criação não pode ser sobrescrito.";
            case "hidden" -> "Campo oculto no contrato de criação não pode ser sobrescrito.";
            case "restricted-by-field-access" ->
                    "Campo restrito pela política de acesso (x-ui.fieldAccess) na operação de criação.";
            case "restricted-by-ai-policy" ->
                    "Campo restrito pela governança de IA (@DomainGovernance / AiUsagePolicy) na operação de criação.";
            case "reactive-determination-target" ->
                    "Campo calculado por determinação reativa (ReactiveDetermination) não pode ser sobrescrito diretamente.";
            default -> "Campo não elegível para preenchimento assistido.";
        };
    }

    private void addIfText(Set<String> target, JsonNode node) {
        if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
            target.add(node.asText().trim());
        }
    }

    private String normalizeResourcePath(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        return trimmed.replaceAll("/+$", "");
    }

    private String normalizeMode(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("edit".equals(lower) || "update".equals(lower)) {
            return "edit";
        }
        if ("view".equals(lower) || "readonly".equals(lower) || "read-only".equals(lower)) {
            return "view";
        }
        return "create";
    }

    private String normalizeRetentionPolicy(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return "zero".equals(lower) || "ephemeral".equals(lower) || "none".equals(lower)
                ? "zero"
                : "standard";
    }

    private String normalizeApplyMode(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return "selected-fields".equals(lower) ? "selected-fields" : "empty-only";
    }

    private record CandidateAssignment(
            String fieldPath,
            String label,
            JsonNode proposedValue,
            String provenance,
            boolean syntheticData,
            String optionLabel) {
    }

    private record ResolvedCreationSchema(
            boolean valid,
            JsonNode schema,
            String resourcePath,
            String schemaPath,
            String operation,
            String schemaHash,
            String errorCode,
            String errorMessage) {

        static ResolvedCreationSchema success(
                JsonNode schema,
                String resourcePath,
                String schemaPath,
                String operation,
                String schemaHash) {
            return new ResolvedCreationSchema(
                    true,
                    schema,
                    resourcePath,
                    schemaPath,
                    operation,
                    schemaHash,
                    null,
                    null);
        }

        static ResolvedCreationSchema failure(String errorCode, String errorMessage) {
            return new ResolvedCreationSchema(
                    false,
                    null,
                    "",
                    "",
                    "post",
                    "",
                    errorCode,
                    errorMessage);
        }
    }
}
