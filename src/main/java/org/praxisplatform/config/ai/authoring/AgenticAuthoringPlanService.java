package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;

public class AgenticAuthoringPlanService {

    private final AiProviderManagementService providerManagementService;
    private final AgenticAuthoringArtifactProperties properties;
    private final AgenticAuthoringMinimalFormPlanValidator validator;

    public AgenticAuthoringPlanService(
            AiProviderManagementService providerManagementService,
            AgenticAuthoringArtifactProperties properties) {
        this.providerManagementService = Objects.requireNonNull(providerManagementService, "providerManagementService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.validator = new AgenticAuthoringMinimalFormPlanValidator();
    }

    public AgenticAuthoringPlanResult generateMinimalFormPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) throws IOException {
        if (request == null || request.userPrompt() == null || request.userPrompt().isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank.");
        }
        JsonNode plan = providerManagementService.generateJson(
                minimalFormPlanPrompt(request),
                AiJsonSchema.ofSchema(readMinimalFormPlanSchema()),
                AiCallConfig.builder()
                        .provider(request.provider())
                        .model(request.model())
                        .apiKey(request.apiKey())
                        .temperature(0.0d)
                        .maxTokens(2048)
                        .build(),
                tenantId,
                userId,
                environment
        );
        List<String> failures = validator.validate(plan, request.intentResolution());
        return new AgenticAuthoringPlanResult(
                failures.isEmpty(),
                failures,
                warnings(request.intentResolution()),
                plan
        );
    }

    private List<String> warnings(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return List.of("minimal-form-plan-only", "patch-compilation-not-run");
        }
        return List.of("minimal-form-plan-only", "patch-compilation-not-run", "intent-resolution-applied");
    }

    private String minimalFormPlanPrompt(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringIntentResolutionResult intentResolution = request.intentResolution();
        if (intentResolution == null || intentResolution.selectedCandidate() == null) {
            return legacyHelpdeskMinimalFormPlanPrompt(request.userPrompt());
        }
        AgenticAuthoringCandidate candidate = intentResolution.selectedCandidate();
        String submitMethod = candidate.submitMethod() == null || candidate.submitMethod().isBlank()
                ? candidate.operation()
                : candidate.submitMethod();
        String submitActionRef = (submitMethod == null ? "POST" : submitMethod.toUpperCase()) + " " + candidate.submitUrl();
        return """
                You are generating an internal Praxis MinimalFormPlan.
                Return only one JSON object. Do not include Markdown.

                User request:
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
                - For operationKind=modify, do not repeat fields that are already in the current page summary.
                - Prefer the smallest didactic form or incremental change that can satisfy the user request.
                - Do not include server-managed, audit, id, status, timestamp, owner or workflow fields unless the user explicitly asked for them.
                - For host-owned helper fields in modify/add_field, choose normal Praxis control types such as text, textarea, checkbox or select.
                - For rename_or_relabel, do not mark fields as local/transient; they remain server-backed label customizations.
                - clarificationNeed.needed must be true only when the prompt cannot be satisfied safely from the resolved API candidate.
                - sourceRefs must cite intent-resolution and the resolved schema URL.
                """.formatted(
                request.userPrompt().trim(),
                intentResolution.operationKind(),
                intentResolution.artifactKind(),
                intentResolution.changeKind(),
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                candidate.resourcePath(),
                submitActionRef,
                candidate.schemaUrl(),
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                submitActionRef);
    }

    private String legacyHelpdeskMinimalFormPlanPrompt(String userPrompt) {
        return """
                You are generating an internal Praxis MinimalFormPlan.
                Return only one JSON object. Do not include Markdown.

                User request:
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
                - sourceRefs must cite the discovery proof, page-create catalog and examples governance manifest.
                """.formatted(userPrompt.trim());
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

}
