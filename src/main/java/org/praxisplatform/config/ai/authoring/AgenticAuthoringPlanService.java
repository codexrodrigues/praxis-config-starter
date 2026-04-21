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
        JsonNode referencePlan = referenceMinimalFormPlan(effectiveRequest);
        if (referencePlan != null) {
            List<String> failures = validator.validate(referencePlan, effectiveRequest.intentResolution());
            return new AgenticAuthoringPlanResult(
                    failures.isEmpty(),
                    failures,
                    warnings(effectiveRequest.intentResolution()),
                    referencePlan
            );
        }
        JsonNode plan = providerManagementService.generateJson(
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
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        if (intent == null
                || intent.selectedCandidate() == null
                || !"create".equals(intent.operationKind())
                || !"form".equals(intent.artifactKind())
                || !"create_minimal_form".equals(intent.changeKind())
                || !"/api/human-resources/funcionarios".equals(intent.selectedCandidate().resourcePath())) {
            return null;
        }
        AgenticAuthoringCandidate candidate = intent.selectedCandidate();
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", "create-minimal-form");
        plan.put("targetApp", intent.targetApp());
        plan.put("targetComponentId", intent.targetComponentId());
        plan.put("apiUseCaseResolutionRef", "intent-resolution:" + candidate.resourcePath());
        plan.put("fieldSelectionPlanRef", candidate.schemaUrl());
        plan.put("submitActionRef", submitActionRef(candidate));
        ArrayNode fields = plan.putArray("fields");
        addField(fields, "nomeCompleto", "Nome completo", "input", true);
        addField(fields, "cpf", "CPF", "cpfCnpjInput", true);
        addField(fields, "email", "Email", "email", true);
        addField(fields, "telefone", "Telefone", "phone", true);
        addField(fields, "dataNascimento", "Data de nascimento", "date", true);
        addField(fields, "salario", "Salario", "currency", true);
        addField(fields, "dataAdmissao", "Data de admissao", "date", true);
        addField(fields, "ativo", "Ativo", "checkbox", true);
        addField(fields, "cargoId", "Cargo", "select", true);
        addField(fields, "departamentoId", "Departamento", "select", true);
        ObjectNode clarification = plan.putObject("clarificationNeed");
        clarification.put("needed", false);
        clarification.put("code", "none");
        clarification.putArray("questions");
        plan.putArray("sourceRefs")
                .add("intent-resolution")
                .add(candidate.schemaUrl())
                .add(candidate.submitUrl());
        return plan;
    }

    private void addField(ArrayNode fields, String name, String label, String controlType, boolean required) {
        ObjectNode field = fields.addObject();
        field.put("name", name);
        field.put("label", label);
        field.put("controlType", controlType);
        field.put("required", required);
    }

    private String submitActionRef(AgenticAuthoringCandidate candidate) {
        String method = candidate.submitMethod() == null || candidate.submitMethod().isBlank()
                ? candidate.operation()
                : candidate.submitMethod();
        return (method == null ? "POST" : method.toUpperCase()) + " " + candidate.submitUrl();
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
            if (Objects.equals(intentEffectivePrompt, request.userPrompt())) {
                return request;
            }
            return withUserPrompt(request, intentEffectivePrompt);
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
        return """
                You are generating an internal Praxis MinimalFormPlan.
                Return only one JSON object. Do not include Markdown.

                User request:
                %s

                Attachment summaries:
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
                - currentPageSummary.formWidgets[].fieldNames lists fields already customized in the page.
                - currentPageSummary.formWidgets[].localFieldNames lists fields that are safe to remove with remove/remove_field.
                - currentPageSummary.formWidgets[].serverBackedOverrideNames lists schema-backed field customizations such as relabels.
                - For modify/add_field, do not repeat names from currentPageSummary.formWidgets[].fieldNames.
                - For remove/remove_field, use only names from currentPageSummary.formWidgets[].localFieldNames.
                - For rename_or_relabel, prefer existing field names from the current page summary or schema-backed fields from the request schema.
                - Prefer the smallest didactic form or incremental change that can satisfy the user request.
                - Do not include server-managed, audit, id, status, timestamp, owner or workflow fields unless the user explicitly asked for them.
                - For host-owned helper fields in modify/add_field, choose normal Praxis control types such as text, textarea, checkbox or select.
                - For rename_or_relabel, do not mark fields as local/transient; they remain server-backed label customizations.
                - Attachment summaries are metadata-only context. Do not assume access to file bytes, base64, local blob URLs or image pixels.
                - clarificationNeed.needed must be true only when the prompt cannot be satisfied safely from the resolved API candidate.
                - sourceRefs must cite intent-resolution and the resolved schema URL.
                """.formatted(
                request.userPrompt().trim(),
                attachmentSummaries,
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
