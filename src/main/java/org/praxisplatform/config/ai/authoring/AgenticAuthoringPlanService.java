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
                minimalFormPlanPrompt(request.userPrompt()),
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
        List<String> failures = validator.validate(plan);
        return new AgenticAuthoringPlanResult(
                failures.isEmpty(),
                failures,
                List.of("minimal-form-plan-only", "patch-compilation-not-run"),
                plan
        );
    }

    private String minimalFormPlanPrompt(String userPrompt) {
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
