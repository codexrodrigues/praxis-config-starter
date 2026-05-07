package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;

public class AgenticAuthoringPlanService {

    private final AiProviderManagementService providerManagementService;
    private final AgenticAuthoringArtifactProperties properties;
    private final AgenticAuthoringMinimalFormPlanValidator validator;
    private final AgenticAuthoringIntentResolutionContext intentResolutionContext;
    private final AgenticAuthoringConversationTurnOrchestrator conversationTurnOrchestrator;
    private final ObjectMapper objectMapper;

    public AgenticAuthoringPlanService(
            AiProviderManagementService providerManagementService,
            AgenticAuthoringArtifactProperties properties) {
        this(providerManagementService, properties, new ObjectMapper());
    }

    public AgenticAuthoringPlanService(
            AiProviderManagementService providerManagementService,
            AgenticAuthoringArtifactProperties properties,
            ObjectMapper objectMapper) {
        this.providerManagementService = Objects.requireNonNull(providerManagementService, "providerManagementService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.validator = new AgenticAuthoringMinimalFormPlanValidator();
        this.intentResolutionContext = new AgenticAuthoringIntentResolutionContext(objectMapper);
        this.conversationTurnOrchestrator = new AgenticAuthoringConversationTurnOrchestrator();
    }

    public AgenticAuthoringPlanResult generateMinimalFormPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) throws IOException {
        if (request == null || request.userPrompt() == null || request.userPrompt().isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank.");
        }
        AgenticAuthoringPlanRequest effectiveRequest = enrichRequest(request);
        effectiveRequest = withEffectivePrompt(effectiveRequest);
        JsonNode plan = referenceMinimalFormPlan(effectiveRequest);
        if (plan == null) {
            plan = providerManagementService.generateJson(
                    minimalFormPlanPrompt(effectiveRequest),
                    AiJsonSchema.ofSchema(readMinimalFormPlanSchema()),
                    AiCallConfig.builder()
                            .provider(effectiveRequest.provider())
                            .model(effectiveRequest.model())
                            .apiKey(effectiveRequest.apiKey())
                            .temperature(0.0d)
                            .maxTokens(2048)
                            .build(),
                    tenantId,
                    userId,
                    environment
            );
        }
        plan = completeDeterministicEditPlan(plan, effectiveRequest);
        List<String> failures = validator.validate(plan, effectiveRequest.intentResolution());
        return new AgenticAuthoringPlanResult(
                failures.isEmpty(),
                failures,
                warnings(effectiveRequest.intentResolution()),
                plan
        );
    }

    private JsonNode referenceMinimalFormPlan(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringIntentResolutionResult intentResolution = request.intentResolution();
        if (intentResolution == null
                || intentResolution.selectedCandidate() == null
                || !"create".equals(intentResolution.operationKind())
                || !"form".equals(intentResolution.artifactKind())
                || !"create_minimal_form".equals(intentResolution.changeKind())
                || !"/api/human-resources/funcionarios".equals(intentResolution.selectedCandidate().resourcePath())) {
            return null;
        }
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", "create-minimal-form");
        plan.put("targetApp", intentResolution.targetApp());
        plan.put("targetComponentId", intentResolution.targetComponentId());
        plan.put("apiUseCaseResolutionRef", "intent-resolution:/api/human-resources/funcionarios");
        plan.put("fieldSelectionPlanRef", intentResolution.selectedCandidate().schemaUrl());
        plan.put("submitActionRef", "POST /api/human-resources/funcionarios");
        ArrayNode fields = plan.putArray("fields");
        addField(fields, "nomeCompleto", "Nome completo", "text", true);
        addField(fields, "cpf", "CPF", "text", true);
        addField(fields, "email", "Email", "email", false);
        addField(fields, "telefone", "Telefone", "text", false);
        addField(fields, "salario", "Salario", "number", false);
        addField(fields, "cargoId", "Cargo", "select", false);
        addField(fields, "departamentoId", "Departamento", "select", false);
        ObjectNode clarification = plan.putObject("clarificationNeed");
        clarification.put("needed", false);
        clarification.put("code", "none");
        plan.putArray("sourceRefs").add("intent-resolution").add(intentResolution.selectedCandidate().schemaUrl());
        return plan;
    }

    private void addField(ArrayNode fields, String name, String label, String controlType, boolean required) {
        ObjectNode field = fields.addObject();
        field.put("name", name);
        field.put("label", label);
        field.put("controlType", controlType);
        field.put("required", required);
    }

    private JsonNode completeDeterministicEditPlan(JsonNode plan, AgenticAuthoringPlanRequest request) {
        if (plan == null || !plan.isObject()) {
            return plan;
        }
        if (isRenameOrRelabelIntent(request.intentResolution())) {
            return completeRenameOrRelabelPlan(plan, request);
        }
        if (!isRemoveFieldIntent(request.intentResolution())) {
            return plan;
        }
        JsonNode fields = plan.path("fields");
        if (fields.isArray() && !fields.isEmpty()) {
            return plan;
        }
        JsonNode formSummary = targetFormSummary(request.intentResolution());
        JsonNode localFieldNames = formSummary.path("localFieldNames");
        if (!localFieldNames.isArray() || localFieldNames.isEmpty()) {
            return plan;
        }
        String normalizedPrompt = normalizeForMatch(request.userPrompt());
        ObjectNode completed = ((ObjectNode) plan).deepCopy();
        ArrayNode completedFields = completed.putArray("fields");
        for (JsonNode localFieldName : localFieldNames) {
            String name = localFieldName.asText("");
            if (name.isBlank() || !normalizedPrompt.contains(normalizeForMatch(name))) {
                continue;
            }
            ObjectNode field = completedFields.addObject();
            field.put("name", name);
            JsonNode fieldSummary = findFieldSummary(formSummary.path("fieldMetadata"), name);
            String label = text(fieldSummary, "label");
            String controlType = text(fieldSummary, "controlType");
            field.put("label", label.isBlank() ? name : label);
            field.put("controlType", controlType.isBlank() ? "text" : controlType);
            if (fieldSummary.has("required")) {
                field.put("required", fieldSummary.path("required").asBoolean(false));
            } else {
                field.put("required", false);
            }
        }
        return completed;
    }

    private JsonNode completeRenameOrRelabelPlan(JsonNode plan, AgenticAuthoringPlanRequest request) {
        String fieldName = relabelFieldName(request.userPrompt());
        String label = relabelFieldLabel(request.userPrompt());
        if (fieldName.isBlank() || label.isBlank()) {
            return plan;
        }

        ObjectNode completed = ((ObjectNode) plan).deepCopy();
        ArrayNode fields = completed.withArray("fields");
        ObjectNode existing = null;
        for (JsonNode field : fields) {
            if (field.isObject() && fieldName.equals(text(field, "name"))) {
                existing = (ObjectNode) field;
                break;
            }
        }
        if (existing == null) {
            existing = fields.addObject();
            existing.put("name", fieldName);
        }
        existing.put("label", label);
        if (!existing.has("controlType")) {
            existing.put("controlType", "text");
        }
        if (!existing.has("required")) {
            existing.put("required", false);
        }
        return completed;
    }

    private String relabelFieldName(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        String normalized = prompt.toLowerCase();
        int fieldMarker = normalized.indexOf("campo ");
        int labelMarker = normalized.indexOf(" para ", fieldMarker < 0 ? 0 : fieldMarker);
        if (fieldMarker < 0 || labelMarker <= fieldMarker) {
            return "";
        }
        String candidate = normalized.substring(fieldMarker + "campo ".length(), labelMarker)
                .replaceAll("[^a-z0-9_]+", " ")
                .trim();
        if (candidate.isBlank()) {
            return "";
        }
        return candidate.split("\\s+")[0];
    }

    private String relabelFieldLabel(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        String lower = prompt.toLowerCase();
        int labelMarker = lower.lastIndexOf(" para ");
        if (labelMarker < 0) {
            return "";
        }
        return prompt.substring(labelMarker + " para ".length()).trim();
    }

    private boolean isRemoveFieldIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        return intentResolution != null
                && "remove".equals(intentResolution.operationKind())
                && "form".equals(intentResolution.artifactKind())
                && "remove_field".equals(intentResolution.changeKind());
    }

    private boolean isRenameOrRelabelIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        return intentResolution != null
                && "modify".equals(intentResolution.operationKind())
                && "form".equals(intentResolution.artifactKind())
                && "rename_or_relabel".equals(intentResolution.changeKind());
    }

    private JsonNode targetFormSummary(AgenticAuthoringIntentResolutionResult intentResolution) {
        JsonNode currentPageSummary = intentResolution == null ? null : intentResolution.currentPageSummary();
        if (currentPageSummary == null) {
            return objectMapper.createObjectNode();
        }
        JsonNode structuralSummary = structuralTargetFormSummary(currentPageSummary, intentResolution.target());
        if (structuralSummary.isObject() && structuralSummary.path("fieldNames").isArray()) {
            return structuralSummary;
        }
        JsonNode formWidgets = currentPageSummary.path("formWidgets");
        if (!formWidgets.isArray() || formWidgets.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        AgenticAuthoringTarget target = intentResolution.target();
        if (target != null && target.widgetKey() != null && !target.widgetKey().isBlank()) {
            for (JsonNode formWidget : formWidgets) {
                if (target.widgetKey().equals(text(formWidget, "widgetKey"))) {
                    return formWidget;
                }
            }
        }
        return formWidgets.get(0);
    }

    private JsonNode structuralTargetFormSummary(JsonNode currentPageSummary, AgenticAuthoringTarget target) {
        JsonNode inspection = currentPageSummary.path("structuralInspection");
        JsonNode widgets = inspection.path("widgets");
        JsonNode fields = inspection.path("fields");
        if (!widgets.isArray() || widgets.isEmpty() || !fields.isArray()) {
            return objectMapper.createObjectNode();
        }
        String targetWidgetKey = target == null ? "" : valueOrEmpty(target.widgetKey());
        String selectedWidgetKey = targetWidgetKey;
        if (selectedWidgetKey.isBlank()) {
            for (JsonNode widget : widgets) {
                if ("form".equals(text(widget, "artifactKind"))) {
                    selectedWidgetKey = text(widget, "widgetKey");
                    break;
                }
            }
        }
        if (selectedWidgetKey.isBlank()) {
            return objectMapper.createObjectNode();
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("widgetKey", selectedWidgetKey);
        ArrayNode fieldNames = summary.putArray("fieldNames");
        ArrayNode localFieldNames = summary.putArray("localFieldNames");
        ArrayNode serverBackedOverrideNames = summary.putArray("serverBackedOverrideNames");
        ArrayNode fieldMetadata = summary.putArray("fieldMetadata");
        for (JsonNode field : fields) {
            if (!selectedWidgetKey.equals(text(field, "widgetKey"))) {
                continue;
            }
            String name = text(field, "name");
            if (name.isBlank()) {
                continue;
            }
            fieldNames.add(name);
            if ("transient".equals(text(field, "binding"))) {
                localFieldNames.add(name);
            } else {
                serverBackedOverrideNames.add(name);
            }
            ObjectNode metadata = fieldMetadata.addObject();
            metadata.put("name", name);
            copyTextIfPresent(field, metadata, "label");
            copyTextIfPresent(field, metadata, "controlType");
            copyTextIfPresent(field, metadata, "source");
            if (field.has("required")) {
                metadata.put("required", field.path("required").asBoolean(false));
            }
        }
        summary.put("fieldCount", fieldNames.size());
        summary.put("localFieldCount", localFieldNames.size());
        return summary;
    }

    private JsonNode findFieldSummary(JsonNode fields, String fieldName) {
        if (!fields.isArray()) {
            return objectMapper.createObjectNode();
        }
        for (JsonNode field : fields) {
            if (fieldName.equals(text(field, "name"))) {
                return field;
            }
        }
        return objectMapper.createObjectNode();
    }

    private void copyTextIfPresent(JsonNode source, ObjectNode target, String fieldName) {
        String value = text(source, fieldName);
        if (!value.isBlank()) {
            target.put(fieldName, value);
        }
    }

    private AgenticAuthoringPlanRequest enrichRequest(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringIntentResolutionResult enrichedIntent =
                intentResolutionContext.enrich(request.intentResolution(), request.currentPage());
        if (enrichedIntent == request.intentResolution()) {
            return request;
        }
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                enrichedIntent,
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints());
    }

    private AgenticAuthoringPlanRequest withEffectivePrompt(AgenticAuthoringPlanRequest request) {
        String intentEffectivePrompt = request.intentResolution() == null
                ? ""
                : valueOrEmpty(request.intentResolution().effectivePrompt());
        if (!intentEffectivePrompt.isBlank()) {
            String contextualEffectivePrompt = intentEffectivePrompt;
            if (isBareConfirmationPrompt(intentEffectivePrompt)) {
                AgenticAuthoringConversationTurn turn = conversationTurnOrchestrator.resolve(
                        intentEffectivePrompt,
                        request.conversationMessages(),
                        request.pendingClarification());
                contextualEffectivePrompt = turn.effectivePrompt();
                if (!Objects.equals(contextualEffectivePrompt, intentEffectivePrompt)) {
                    return withUserPrompt(request, contextualEffectivePrompt);
                }
            }
            if (Objects.equals(contextualEffectivePrompt, request.userPrompt())) {
                return request;
            }
            return withUserPrompt(request, contextualEffectivePrompt);
        }
        AgenticAuthoringConversationTurn turn = conversationTurnOrchestrator.resolve(
                request.userPrompt(),
                request.conversationMessages(),
                request.pendingClarification());
        String effectivePrompt = turn.effectivePrompt();
        if (Objects.equals(effectivePrompt, request.userPrompt())) {
            return request;
        }
        return withUserPrompt(request, effectivePrompt);
    }

    private AgenticAuthoringPlanRequest withUserPrompt(AgenticAuthoringPlanRequest request, String userPrompt) {
        return new AgenticAuthoringPlanRequest(
                userPrompt,
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                request.intentResolution(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints());
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBareConfirmationPrompt(String prompt) {
        String normalized = valueOrEmpty(prompt).toLowerCase(java.util.Locale.ROOT).trim();
        if (normalized.isBlank()) {
            return false;
        }
        boolean materializationRequest = normalized.matches(".*\\b(preview|previa|prévia|pre visualizacao|pré visualização|materialize|materializar)\\b.*");
        boolean generationVerb = normalized.matches(".*\\b(gere|gerar|generate)\\b.*");
        boolean newInstruction = normalized.matches(".*\\b(crie|criar|adicione|adicionar|altere|alterar|remova|remover|monte|montar|create|add|change|remove|build)\\b.*")
                || (generationVerb && !materializationRequest);
        if (newInstruction) {
            return false;
        }
        return normalized.matches(".*\\b(sim|confirmo|confirmado|confirmed|ok|siga|seguir|pode seguir|materialize|materializar|faça isso|faca isso)\\b.*");
    }

    private List<String> warnings(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return List.of("minimal-form-plan-only", "patch-compilation-not-run");
        }
        List<String> warnings = new ArrayList<>(List.of(
                "minimal-form-plan-only",
                "patch-compilation-not-run",
                "intent-resolution-applied"));
        if (intentResolution.warnings() != null) {
            warnings.addAll(intentResolution.warnings());
        }
        return List.copyOf(warnings);
    }

    private String minimalFormPlanPrompt(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringIntentResolutionResult intentResolution = request.intentResolution();
        if (intentResolution == null || intentResolution.selectedCandidate() == null) {
            return legacyHelpdeskMinimalFormPlanPrompt(request);
        }
        AgenticAuthoringCandidate candidate = intentResolution.selectedCandidate();
        String submitMethod = candidate.submitMethod() == null || candidate.submitMethod().isBlank()
                ? candidate.operation()
                : candidate.submitMethod();
        String submitActionRef = (submitMethod == null ? "POST" : submitMethod.toUpperCase()) + " " + candidate.submitUrl();
        String currentPageSummary = intentResolution.currentPageSummary() == null
                ? "{}"
                : intentResolution.currentPageSummary().toString();
        String attachmentSummaries = attachmentSummariesJson(request);
        String repairContext = repairContextJson(request);
        String projectKnowledge = projectKnowledgeJson(request);
        return """
                You are generating an internal Praxis MinimalFormPlan.
                Return only one JSON object. Do not include Markdown.

                User request:
                %s

                Attachment summaries:
                %s

                Repair context:
                %s

                Governed project knowledge:
                %s

                Resolved intent:
                - operationKind: %s
                - artifactKind: %s
                - changeKind: %s
                - targetApp: %s
                - targetComponentId: %s
                - resourcePath: %s
                - submitActionRef: %s
                - requestSchemaUrl: %s

                Current page summary:
                %s

                Hard constraints:
                - version must be "1.0.0".
                - profileId must be "create-minimal-form".
                - targetApp must be "%s".
                - targetComponentId must be "%s".
                - submitActionRef must be "%s".
                - apiUseCaseResolutionRef must reference the resolved resource path.
                - fieldSelectionPlanRef must reference the resolved request schema URL.
                - For operationKind=create, fields must include only fields that are truly necessary for the user request and the create request schema.
                - For operationKind=modify and changeKind=add_field, fields must include only the newly requested host-owned fields to add to the existing form.
                - For modify/add_field, do not include schema-required fields just to make a submit payload valid; the current form already owns submit validity.
                - For modify/add_field, if the user names a field explicitly, use a camelCase name derived from that requested field.
                - For operationKind=modify and changeKind=rename_or_relabel, fields must include only existing field names with the new desired labels.
                - For operationKind=remove and changeKind=remove_field, fields must include only existing host-owned local/transient field names explicitly requested for removal.
                - For remove/remove_field, do not include server-backed schema fields; removing schema-owned fields requires a future schema-aware hide flow.
                - currentPageSummary.structuralInspection.fields is the primary source for existing page fields.
                - currentPageSummary.structuralInspection.fields[].binding="transient" marks fields that are safe to remove with remove/remove_field.
                - currentPageSummary.formWidgets[] is compatibility-only when structuralInspection is absent.
                - For modify/add_field, do not repeat existing field names from structuralInspection.fields or formWidgets[].fieldNames.
                - For remove/remove_field, use only transient/local fields from structuralInspection.fields or formWidgets[].localFieldNames.
                - For rename_or_relabel, prefer existing field names from the current page summary or schema-backed fields from the request schema.
                - Prefer the smallest didactic form or incremental change that can satisfy the user request.
                - Do not include server-managed, audit, id, status, timestamp, owner or workflow fields unless the user explicitly asked for them.
                - For host-owned helper fields in modify/add_field, choose normal Praxis control types such as text, textarea, checkbox or select.
                - For rename_or_relabel, do not mark fields as local/transient; they remain server-backed label customizations.
                - Attachment summaries are metadata-only context. Do not assume access to file bytes, base64, local blob URLs or image pixels.
                - Repair context is metadata-only. When present, use it only to avoid repeating the previous invalid plan shape.
                - Governed project knowledge is safe semantic context from the platform. Use it to prefer project-specific wording, layout and constraints when relevant, but never treat it as executable rules or expose source internals.
                - clarificationNeed.needed must be true only when the prompt cannot be satisfied safely from the resolved API candidate.
                - sourceRefs must cite intent-resolution, the resolved schema URL, and projectKnowledge entries when they materially influence the plan.
                """.formatted(
                request.userPrompt().trim(),
                attachmentSummaries,
                repairContext,
                projectKnowledge,
                intentResolution.operationKind(),
                intentResolution.artifactKind(),
                intentResolution.changeKind(),
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                candidate.resourcePath(),
                submitActionRef,
                candidate.schemaUrl(),
                currentPageSummary,
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                submitActionRef);
    }

    private String projectKnowledgeJson(AgenticAuthoringPlanRequest request) {
        JsonNode projectKnowledge = request.contextHints() == null ? null : request.contextHints().path("projectKnowledge");
        if (projectKnowledge == null || projectKnowledge.isMissingNode() || projectKnowledge.isNull() || !projectKnowledge.isObject()) {
            return "{}";
        }
        ObjectNode safeProjectKnowledge = objectMapper.createObjectNode();
        copyTextIfPresent(projectKnowledge, safeProjectKnowledge, "schemaVersion");
        copyTextIfPresent(projectKnowledge, safeProjectKnowledge, "source");
        safeProjectKnowledge.put("influenceCount", projectKnowledge.path("influenceCount").asInt(0));
        safeProjectKnowledge.put("usageRule", "Use these safe projections as governed project context; do not expose source internals.");
        ArrayNode safeEntries = safeProjectKnowledge.putArray("entries");
        JsonNode entries = projectKnowledge.path("entries");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                if (!entry.isObject()) {
                    continue;
                }
                ObjectNode safeEntry = safeEntries.addObject();
                copyTextIfPresent(entry, safeEntry, "knowledgeId");
                copyTextIfPresent(entry, safeEntry, "conceptKey");
                copyTextIfPresent(entry, safeEntry, "kind");
                copyTextIfPresent(entry, safeEntry, "visibility");
                copyTextIfPresent(entry, safeEntry, "sourceSummary");
                copyTextIfPresent(entry, safeEntry, "influence");
                copyTextIfPresent(entry, safeEntry, "summary");
                copyObjectIfPresent(entry, safeEntry, "scope");
                copyObjectIfPresent(entry, safeEntry, "status");
                copyStringArrayIfPresent(entry, safeEntry, "evidence");
            }
        }
        return safeProjectKnowledge.toString();
    }

    private String repairContextJson(AgenticAuthoringPlanRequest request) {
        JsonNode repair = request.contextHints() == null ? null : request.contextHints().path("repair");
        if (repair == null || repair.isMissingNode() || repair.isNull() || !repair.isObject()) {
            return "{}";
        }
        ObjectNode safeRepair = objectMapper.createObjectNode();
        copyTextIfPresent(repair, safeRepair, "phase");
        copyTextIfPresent(repair, safeRepair, "classification");
        safeRepair.put("attempt", repair.path("attempt").asInt(0));
        safeRepair.put("maxAttempts", repair.path("maxAttempts").asInt(0));
        safeRepair.put("failureCodeCount", repair.path("failureCodeCount").asInt(0));
        safeRepair.put("warningCount", repair.path("warningCount").asInt(0));
        return safeRepair.toString();
    }

    private void copyObjectIfPresent(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.path(fieldName);
        if (value != null && value.isObject()) {
            target.set(fieldName, value.deepCopy());
        }
    }

    private void copyStringArrayIfPresent(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.path(fieldName);
        if (value == null || !value.isArray()) {
            return;
        }
        ArrayNode safeArray = target.putArray(fieldName);
        for (JsonNode item : value) {
            if (item.isTextual() && !item.asText().isBlank()) {
                safeArray.add(item.asText());
            }
        }
    }

    private String attachmentSummariesJson(AgenticAuthoringPlanRequest request) {
        List<AgenticAuthoringAttachmentSummary> attachmentSummaries = attachmentSummaries(request);
        if (attachmentSummaries.isEmpty()) {
            return "[]";
        }
        return objectMapper.valueToTree(attachmentSummaries).toString();
    }

    private List<AgenticAuthoringAttachmentSummary> attachmentSummaries(AgenticAuthoringPlanRequest request) {
        if (request.attachmentSummaries() != null && !request.attachmentSummaries().isEmpty()) {
            return request.attachmentSummaries();
        }
        JsonNode diagnostics = request.pendingClarification() == null
                ? null
                : request.pendingClarification().diagnostics();
        JsonNode summaries = diagnostics == null ? null : diagnostics.path("attachmentSummaries");
        if (summaries == null || !summaries.isArray() || summaries.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(
                    summaries,
                    new TypeReference<List<AgenticAuthoringAttachmentSummary>>() {
                    });
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private String legacyHelpdeskMinimalFormPlanPrompt(AgenticAuthoringPlanRequest request) {
        String attachmentSummaries = attachmentSummariesJson(request);
        return """
                You are generating an internal Praxis MinimalFormPlan.
                Return only one JSON object. Do not include Markdown.

                User request:
                %s

                Attachment summaries:
                %s

                Hard constraints:
                - version must be "1.0.0".
                - profileId must be "create-minimal-form".
                - targetApp must be "praxis-helpdesk-ui".
                - targetComponentId must be "praxis-dynamic-page-builder".
                - apiUseCaseResolutionRef must be "proofs/helpdesk-create-ticket-discovery.md#api-use-case".
                - fieldSelectionPlanRef must be "proofs/helpdesk-create-ticket-discovery.md#field-selection".
                - submitActionRef must be "POST /api/helpdesk/chamados".
                - fields may include only titulo and descricao.
                - titulo is required because the create schema requires it.
                - descricao is allowed when the prompt describes the problem.
                - Do not include organizacaoId, solicitanteId, statusAtualId, itemCatalogoId, prioridadeId,
                  grupoResponsavelId, responsavelId or dataLimite.
                - clarificationNeed.needed must be false and clarificationNeed.code must be "none" when the request
                  can be satisfied with titulo and descricao.
                - Attachment summaries are metadata-only context. Do not assume access to file bytes, base64 content, blob URLs, or image pixels.
                - sourceRefs must cite the discovery proof, page-create catalog and examples governance manifest.
                """.formatted(request.userPrompt().trim(), attachmentSummaries);
    }

    private String readMinimalFormPlanSchema() throws IOException {
        Path contractsDir = properties.getContractsDir();
        if (contractsDir == null) {
            throw new IllegalStateException("praxis.ai.authoring.contracts-dir must be configured before generating MinimalFormPlan.");
        }
        Path root = contractsDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("praxis.ai.authoring.contracts-dir does not exist or is not a directory: " + root);
        }
        String fileName = properties.getMinimalFormPlanSchema();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalStateException("praxis.ai.authoring.minimal-form-plan-schema must not be blank.");
        }
        Path schema = root.resolve(fileName).toAbsolutePath().normalize();
        if (!schema.startsWith(root)) {
            throw new IllegalStateException("praxis.ai.authoring.minimal-form-plan-schema must resolve inside contracts-dir.");
        }
        if (!Files.isRegularFile(schema)) {
            throw new IllegalStateException("MinimalFormPlan schema not found: " + schema);
        }
        return Files.readString(schema);
    }

    private String normalizeForMatch(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

}
