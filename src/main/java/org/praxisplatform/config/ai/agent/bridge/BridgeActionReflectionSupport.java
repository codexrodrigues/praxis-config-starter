package org.praxisplatform.config.ai.agent.bridge;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.ai.agent.spi.AgentActionInputField;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.BulkCapabilityDigest;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.BulkCommandDigest;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.BulkEditableFieldDigest;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.DeterminationSummary;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionRequirement;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.ResourceIntent;
import org.praxisplatform.uischema.annotation.UiOption;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.bulk.BulkCommandEvaluationRequest;
import org.praxisplatform.uischema.bulk.BulkControllerOperationIds;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.bulk.BulkResourceOperation;
import org.praxisplatform.uischema.bulk.BulkResourceOperations;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.controller.base.AbstractBulkGovernedResourceController;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Utilitario interno de introspeccao de endpoints {@code /determinations/*}, {@link WorkflowAction},
 * {@link ResourceIntent} e DTOs de entrada para o bridge em processo.
 */
final class BridgeActionReflectionSupport {

    private static final ParameterNameDiscoverer PARAM_NAMES = new DefaultParameterNameDiscoverer();

    private BridgeActionReflectionSupport() {
    }

    record DeterminationEndpoint(
            String determinationId,
            String title,
            String description,
            String relativePath,
            String fullPath,
            Method method,
            int requestBodyIndex,
            Class<?> requestDtoType,
            Class<?> responseDtoType,
            List<AgentActionInputField> inputFields
    ) {
        DeterminationSummary toSummary() {
            return new DeterminationSummary(
                    determinationId,
                    title,
                    description,
                    fullPath,
                    inputFields.stream().map(AgentActionInputField::name).toList());
        }
    }

    record MutatingActionEndpoint(
            String actionId,
            Set<String> aliases,
            String title,
            String description,
            ActionScope scope,
            String operationKind,
            String httpMethod,
            String relativePath,
            String riskLevel,
            boolean confirmationRequired,
            boolean requiresResourceVersion,
            List<String> allowedStates,
            List<String> requiredAuthorities,
            int order,
            Method method,
            int pathVariableIndex,
            Class<?> pathVariableType,
            int ifMatchHeaderIndex,
            int idempotencyKeyHeaderIndex,
            int requestBodyIndex,
            Class<?> requestDtoType,
            Class<?> responseDtoType,
            List<AgentActionInputField> inputFields
    ) {
        boolean matchesActionId(String candidate) {
            if (!StringUtils.hasText(candidate)) {
                return false;
            }
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (actionId.equalsIgnoreCase(normalized)) {
                return true;
            }
            return aliases != null && aliases.contains(normalized);
        }
    }

    record BulkCommandEndpoint(
            String actionId,
            String operationId,
            String evaluationOperationId,
            String title,
            String description,
            String atomicity,
            int maxTargets,
            String evaluationPath,
            String confirmPath,
            List<String> requiredAuthorities,
            Class<?> parameterDtoType,
            List<AgentActionInputField> inputFields
    ) {
        boolean matchesActionId(String candidate) {
            return StringUtils.hasText(candidate) && actionId.equalsIgnoreCase(candidate.trim());
        }

        BulkCommandDigest toDigest() {
            return new BulkCommandDigest(
                    actionId,
                    operationId,
                    title,
                    description,
                    atomicity,
                    maxTargets,
                    evaluationPath,
                    confirmPath,
                    inputFields);
        }
    }

    record BulkResourceSupport(
            boolean bulkSupported,
            boolean uniformUpdateSupported,
            boolean perItemUpdateSupported,
            String uniformEvaluationOperationId,
            String uniformConfirmationOperationId,
            String uniformEvaluationPath,
            String uniformConfirmPath,
            int uniformMaxTargets,
            String perItemEvaluationOperationId,
            String perItemConfirmationOperationId,
            String perItemEvaluationPath,
            String perItemConfirmPath,
            String proposalOperationId,
            String proposalResultsOperationId,
            String executionOperationId,
            String executionResultsOperationId,
            String cancelOperationId,
            String proposalPath,
            String proposalResultsPath,
            String executionPath,
            String executionResultsPath,
            String cancelPath,
            Set<String> protectedUpdateFields,
            List<BulkEditableFieldDigest> editableFields,
            List<BulkCommandEndpoint> domainCommands,
            BulkCapabilityDigest digest
    ) {
        BulkCommandEndpoint resolveCommand(String actionId) {
            if (!StringUtils.hasText(actionId) || domainCommands == null) {
                return null;
            }
            for (BulkCommandEndpoint cmd : domainCommands) {
                if (cmd.matchesActionId(actionId)) {
                    return cmd;
                }
            }
            return null;
        }

        boolean isEditableField(String fieldName, BulkMode mode) {
            if (!StringUtils.hasText(fieldName) || editableFields == null) {
                return false;
            }
            String modeName = mode != null ? mode.name() : "";
            for (BulkEditableFieldDigest f : editableFields) {
                if (f.name().equals(fieldName) && (modeName.isEmpty() || f.modes().contains(modeName))) {
                    return true;
                }
            }
            return false;
        }

        boolean isClearAllowed(String fieldName, BulkMode mode) {
            if (!StringUtils.hasText(fieldName) || editableFields == null) {
                return false;
            }
            String modeName = mode != null ? mode.name() : "";
            for (BulkEditableFieldDigest f : editableFields) {
                if (f.name().equals(fieldName)
                        && (modeName.isEmpty() || f.modes().contains(modeName))
                        && f.allowClear()) {
                    return true;
                }
            }
            return false;
        }
    }

    static List<DeterminationEndpoint> discoverDeterminations(Class<?> targetClass, String basePath) {
        if (targetClass == null || Object.class.equals(targetClass)) {
            return List.of();
        }
        Map<String, DeterminationEndpoint> byId = new LinkedHashMap<>();
        for (Method method : ReflectionUtils.getAllDeclaredMethods(targetClass)) {
            if (method.isBridge() || method.isSynthetic() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            if (mapping.method().length > 0 && !Arrays.asList(mapping.method()).contains(RequestMethod.POST)) {
                continue;
            }
            String rawPath = firstPath(mapping);
            if (!StringUtils.hasText(rawPath)) {
                continue;
            }
            String detId = extractDeterminationId(rawPath);
            if (!StringUtils.hasText(detId)) {
                continue;
            }
            Operation op = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
            String title = op != null && StringUtils.hasText(op.summary()) ? op.summary().trim() : detId;
            String description = op != null && StringUtils.hasText(op.description()) ? op.description().trim() : "";

            Parameter[] params = method.getParameters();
            int bodyIndex = findRequestBodyIndex(params);
            Class<?> requestDtoType = bodyIndex >= 0 ? params[bodyIndex].getType() : null;
            Class<?> responseDtoType = unwrapReturnDtoType(method, targetClass);
            List<AgentActionInputField> inputFields = introspectInputFields(requestDtoType);
            String normalizedRel = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
            String fullPath = combinePath(basePath, normalizedRel);
            byId.putIfAbsent(detId.toLowerCase(Locale.ROOT), new DeterminationEndpoint(
                    detId,
                    title,
                    description,
                    normalizedRel,
                    fullPath,
                    method,
                    bodyIndex,
                    requestDtoType,
                    responseDtoType,
                    inputFields));
        }
        return List.copyOf(byId.values());
    }

    static List<MutatingActionEndpoint> discoverMutatingActions(
            Class<?> targetClass,
            String basePath,
            Class<?> updateDtoType,
            boolean includeEditAction) {
        if (targetClass == null || Object.class.equals(targetClass)) {
            return List.of();
        }
        Map<String, MutatingActionEndpoint> byId = new LinkedHashMap<>();
        for (Method method : ReflectionUtils.getAllDeclaredMethods(targetClass)) {
            if (method.isBridge() || method.isSynthetic() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            WorkflowAction wf = AnnotatedElementUtils.findMergedAnnotation(method, WorkflowAction.class);
            BulkOperation bulkOp = AnnotatedElementUtils.findMergedAnnotation(method, BulkOperation.class);
            ResourceIntent intent = AnnotatedElementUtils.findMergedAnnotation(method, ResourceIntent.class);
            if ((wf == null || bulkOp != null) && intent == null) {
                continue;
            }
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            String rawPath = mapping != null ? firstPath(mapping) : "";
            String normalizedRel = StringUtils.hasText(rawPath)
                    ? (rawPath.startsWith("/") ? rawPath : "/" + rawPath)
                    : "/{id}";
            String httpMethod = resolveHttpMethod(mapping, intent != null ? "PATCH" : "POST");
            Parameter[] params = method.getParameters();
            int pathVarIdx = findParameterWithAnnotation(params, PathVariable.class);
            Class<?> pathVarType = pathVarIdx >= 0 ? params[pathVarIdx].getType() : null;
            int ifMatchIdx = findHeaderIndex(params, "if-match");
            int idempotencyIdx = findHeaderIndex(params, "idempotency-key");
            int bodyIdx = findRequestBodyIndex(params);
            Class<?> requestDtoType = bodyIdx >= 0 ? params[bodyIdx].getType() : null;
            Class<?> responseDtoType = unwrapReturnDtoType(method, targetClass);
            List<AgentActionInputField> inputFields = introspectInputFields(requestDtoType);

            if (wf != null && StringUtils.hasText(wf.id())) {
                String actionId = wf.id().trim();
                List<String> allowedStates = wf.allowedStates() == null
                        ? List.of()
                        : Arrays.stream(wf.allowedStates())
                                .filter(StringUtils::hasText)
                                .map(String::trim)
                                .toList();
                List<String> reqAuths = wf.requiredAuthorities() == null
                        ? List.of()
                        : Arrays.stream(wf.requiredAuthorities())
                                .filter(StringUtils::hasText)
                                .map(String::trim)
                                .toList();
                String title = StringUtils.hasText(wf.title()) ? wf.title().trim() : actionId;
                byId.putIfAbsent(actionId.toLowerCase(Locale.ROOT), new MutatingActionEndpoint(
                        actionId,
                        Set.of(actionId.toLowerCase(Locale.ROOT)),
                        title,
                        wf.description() != null ? wf.description().trim() : "",
                        wf.scope(),
                        "workflow-action",
                        httpMethod,
                        normalizedRel,
                        wf.riskLevel() != null ? wf.riskLevel().name() : "MEDIUM",
                        wf.confirmationRequired(),
                        wf.resourceVersion() != ActionRequirement.NONE || ifMatchIdx >= 0,
                        allowedStates,
                        reqAuths,
                        wf.order(),
                        method,
                        pathVarIdx,
                        pathVarType,
                        ifMatchIdx,
                        idempotencyIdx,
                        bodyIdx,
                        requestDtoType,
                        responseDtoType,
                        inputFields));
            } else if (intent != null && StringUtils.hasText(intent.id())) {
                String intentId = intent.id().trim();
                UiSurface surface = AnnotatedElementUtils.findMergedAnnotation(method, UiSurface.class);
                List<String> reqAuths = surface != null && surface.requiredAuthorities() != null
                        ? Arrays.stream(surface.requiredAuthorities())
                                .filter(StringUtils::hasText)
                                .map(String::trim)
                                .toList()
                        : List.of();
                String title = StringUtils.hasText(intent.title()) ? intent.title().trim() : intentId;
                byId.putIfAbsent(intentId.toLowerCase(Locale.ROOT), new MutatingActionEndpoint(
                        intentId,
                        Set.of(intentId.toLowerCase(Locale.ROOT)),
                        title,
                        intent.description() != null ? intent.description().trim() : "",
                        ActionScope.ITEM,
                        "partial-patch",
                        httpMethod,
                        normalizedRel,
                        "MEDIUM",
                        true,
                        ifMatchIdx >= 0,
                        List.of(),
                        reqAuths,
                        surface != null ? surface.order() : 500,
                        method,
                        pathVarIdx,
                        pathVarType,
                        ifMatchIdx,
                        idempotencyIdx,
                        bodyIdx,
                        requestDtoType,
                        responseDtoType,
                        inputFields));
            }
        }
        if (includeEditAction && updateDtoType != null && !Object.class.equals(updateDtoType)) {
            byId.putIfAbsent("edit", new MutatingActionEndpoint(
                    "edit",
                    Set.of("edit", "update"),
                    "Editar registro",
                    "Atualiza os campos editáveis do registro respeitando regras de domínio e If-Match.",
                    ActionScope.ITEM,
                    "edit",
                    "PUT",
                    "/{id}",
                    "MEDIUM",
                    true,
                    true,
                    List.of(),
                    List.of(),
                    900,
                    null,
                    -1,
                    null,
                    -1,
                    -1,
                    -1,
                    updateDtoType,
                    null,
                    introspectInputFields(updateDtoType)));
        }
        return List.copyOf(byId.values());
    }

    static BulkResourceSupport discoverBulkSupport(
            Class<?> targetClass,
            String basePath,
            Class<?> updateDtoType,
            String resourceKey) {
        if (targetClass == null
                || Object.class.equals(targetClass)
                || !AbstractBulkGovernedResourceController.class.isAssignableFrom(targetClass)) {
            return null;
        }
        BulkResourceOperations opsDecl =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, BulkResourceOperations.class);
        if (opsDecl == null) {
            return null;
        }

        Set<String> protectedFields = new LinkedHashSet<>();
        if (opsDecl.protectedUpdateFields() != null) {
            for (String p : opsDecl.protectedUpdateFields()) {
                if (StringUtils.hasText(p)) {
                    protectedFields.add(p.trim());
                }
            }
        }
        List<BulkEditableFieldDigest> editableFields = introspectBulkEditableFields(updateDtoType, protectedFields);

        Method[] allMethods = ReflectionUtils.getAllDeclaredMethods(targetClass);
        Map<String, Method> methodByOperationId = new LinkedHashMap<>();
        Map<BulkResourceOperation.Role, Method> methodByRole = new LinkedHashMap<>();
        for (Method m : allMethods) {
            if (m.isBridge() || m.isSynthetic() || m.getDeclaringClass() == Object.class) {
                continue;
            }
            Operation op = AnnotatedElementUtils.findMergedAnnotation(m, Operation.class);
            if (op != null && StringUtils.hasText(op.operationId())) {
                methodByOperationId.putIfAbsent(op.operationId().trim(), m);
            }
            BulkResourceOperation roleAnn = AnnotatedElementUtils.findMergedAnnotation(m, BulkResourceOperation.class);
            if (roleAnn != null && roleAnn.value() != null) {
                methodByRole.putIfAbsent(roleAnn.value(), m);
            }
        }

        String proposalOpId = BulkControllerOperationIds.lifecycleOperationId(
                targetClass, opsDecl, BulkResourceOperation.Role.PROPOSAL);
        String proposalResultsOpId = BulkControllerOperationIds.lifecycleOperationId(
                targetClass, opsDecl, BulkResourceOperation.Role.PROPOSAL_RESULTS);
        String executionOpId = BulkControllerOperationIds.lifecycleOperationId(
                targetClass, opsDecl, BulkResourceOperation.Role.EXECUTION);
        String executionResultsOpId = BulkControllerOperationIds.lifecycleOperationId(
                targetClass, opsDecl, BulkResourceOperation.Role.EXECUTION_RESULTS);
        String cancelOpId = BulkControllerOperationIds.lifecycleOperationId(
                targetClass, opsDecl, BulkResourceOperation.Role.CANCEL);

        String proposalPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.PROPOSAL), "/bulk/proposals/{proposalId}");
        String proposalResultsPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.PROPOSAL_RESULTS), "/bulk/proposals/{proposalId}/results");
        String executionPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.EXECUTION), "/bulk/executions/{executionId}");
        String executionResultsPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.EXECUTION_RESULTS), "/bulk/executions/{executionId}/results");
        String cancelPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.CANCEL), "/bulk/executions/{executionId}/cancel");

        String uniformConfirmOpId = BulkControllerOperationIds.updateConfirmationId(targetClass, false);
        String uniformEvalOpId = methodByRole.containsKey(BulkResourceOperation.Role.UNIFORM_CONFIRMATION)
                ? extractEvaluationOperationId(methodByRole.get(BulkResourceOperation.Role.UNIFORM_CONFIRMATION), uniformConfirmOpId + ".evaluate")
                : uniformConfirmOpId + ".evaluate";
        String perItemConfirmOpId = BulkControllerOperationIds.updateConfirmationId(targetClass, true);
        String perItemEvalOpId = methodByRole.containsKey(BulkResourceOperation.Role.ITEM_CONFIRMATION)
                ? extractEvaluationOperationId(methodByRole.get(BulkResourceOperation.Role.ITEM_CONFIRMATION), perItemConfirmOpId + ".evaluate")
                : perItemConfirmOpId + ".evaluate";

        String uniformEvalPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.UNIFORM_EVALUATION), "/bulk/update/evaluation");
        String uniformConfirmPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.UNIFORM_CONFIRMATION), "/bulk/update");
        String perItemEvalPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.ITEM_EVALUATION), "/bulk/update-items/evaluation");
        String perItemConfirmPath = resolveMethodFullPath(basePath, methodByRole.get(BulkResourceOperation.Role.ITEM_CONFIRMATION), "/bulk/update-items");

        boolean hasUpdateSource = StringUtils.hasText(opsDecl.updateSourceOperationId());
        boolean uniformUpdateSupported = hasUpdateSource
                && editableFields.stream().anyMatch(f -> f.modes().contains(BulkMode.UNIFORM_UPDATE.name()));
        boolean perItemUpdateSupported = hasUpdateSource
                && editableFields.stream().anyMatch(f -> f.modes().contains(BulkMode.PER_ITEM_UPDATE.name()));

        Map<String, BulkCommandEndpoint> commandsById = new LinkedHashMap<>();
        for (Method method : allMethods) {
            if (method.isBridge() || method.isSynthetic() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            BulkOperation bulkOp = AnnotatedElementUtils.findMergedAnnotation(method, BulkOperation.class);
            WorkflowAction wf = AnnotatedElementUtils.findMergedAnnotation(method, WorkflowAction.class);
            if (bulkOp == null || bulkOp.mode() != BulkMode.DOMAIN_COMMAND || wf == null || !StringUtils.hasText(wf.id())) {
                continue;
            }
            String actionId = wf.id().trim();
            if (commandsById.containsKey(actionId.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Operation confirmOp = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
            String confirmOpId = confirmOp != null && StringUtils.hasText(confirmOp.operationId())
                    ? confirmOp.operationId().trim()
                    : method.getName();
            String evalOpId = StringUtils.hasText(bulkOp.evaluationOperationId())
                    ? bulkOp.evaluationOperationId().trim()
                    : confirmOpId + ".evaluation";

            Method evalMethod = methodByOperationId.get(evalOpId);
            String confirmPath = resolveMethodFullPath(basePath, method, "/actions/" + actionId);
            String evalPath = evalMethod != null
                    ? resolveMethodFullPath(basePath, evalMethod, "/bulk/" + actionId + "/evaluation")
                    : combinePath(basePath, "/bulk/" + actionId + "/evaluation");

            Class<?> paramDtoType = null;
            if (evalMethod != null) {
                int evalBodyIdx = findRequestBodyIndex(evalMethod.getParameters());
                if (evalBodyIdx >= 0) {
                    ResolvableType bodyType = ResolvableType.forMethodParameter(evalMethod, evalBodyIdx, targetClass);
                    Class<?> resolvedParam = bodyType.as(BulkCommandEvaluationRequest.class).getGeneric(0).resolve(Object.class);
                    if (resolvedParam != null && !Object.class.equals(resolvedParam)) {
                        paramDtoType = resolvedParam;
                    }
                }
            }
            List<AgentActionInputField> inputFields = introspectInputFields(paramDtoType);
            String atomicity = bulkOp.atomicity() != null
                    ? bulkOp.atomicity().name()
                    : (wf.atomicity() != null ? wf.atomicity().name() : ActionCollectionAtomicity.PER_ITEM.name());
            int maxTargets = wf.maxSelection() > 0
                    ? wf.maxSelection()
                    : (ActionCollectionAtomicity.ATOMIC.name().equalsIgnoreCase(atomicity) ? 50 : 200);
            List<String> reqAuths = wf.requiredAuthorities() == null
                    ? List.of()
                    : Arrays.stream(wf.requiredAuthorities())
                            .filter(StringUtils::hasText)
                            .map(String::trim)
                            .toList();
            String title = StringUtils.hasText(wf.title()) ? wf.title().trim() : actionId;
            String description = wf.description() != null ? wf.description().trim() : "";
            commandsById.put(
                    actionId.toLowerCase(Locale.ROOT),
                    new BulkCommandEndpoint(
                            actionId,
                            confirmOpId,
                            evalOpId,
                            title,
                            description,
                            atomicity,
                            maxTargets,
                            evalPath,
                            confirmPath,
                            reqAuths,
                            paramDtoType,
                            inputFields));
        }

        List<BulkCommandEndpoint> commands = List.copyOf(commandsById.values());
        boolean bulkSupported = uniformUpdateSupported || perItemUpdateSupported || !commands.isEmpty();
        if (!bulkSupported) {
            return null;
        }
        BulkCapabilityDigest digest = new BulkCapabilityDigest(
                uniformUpdateSupported,
                perItemUpdateSupported,
                uniformUpdateSupported ? 200 : 0,
                uniformUpdateSupported ? List.of("EXPLICIT", "QUERY") : List.of("EXPLICIT"),
                editableFields,
                commands.stream().map(BulkCommandEndpoint::toDigest).toList());
        return new BulkResourceSupport(
                true,
                uniformUpdateSupported,
                perItemUpdateSupported,
                uniformEvalOpId,
                uniformConfirmOpId,
                uniformEvalPath,
                uniformConfirmPath,
                200,
                perItemEvalOpId,
                perItemConfirmOpId,
                perItemEvalPath,
                perItemConfirmPath,
                proposalOpId,
                proposalResultsOpId,
                executionOpId,
                executionResultsOpId,
                cancelOpId,
                proposalPath,
                proposalResultsPath,
                executionPath,
                executionResultsPath,
                cancelPath,
                Set.copyOf(protectedFields),
                editableFields,
                commands,
                digest);
    }

    private static String extractEvaluationOperationId(Method confirmMethod, String fallback) {
        if (confirmMethod == null) {
            return fallback;
        }
        BulkOperation bulkOp = AnnotatedElementUtils.findMergedAnnotation(confirmMethod, BulkOperation.class);
        if (bulkOp != null && StringUtils.hasText(bulkOp.evaluationOperationId())) {
            return bulkOp.evaluationOperationId().trim();
        }
        return fallback;
    }

    private static String resolveMethodFullPath(String basePath, Method method, String fallbackRelPath) {
        if (method != null) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                String raw = firstPath(mapping);
                if (StringUtils.hasText(raw)) {
                    return combinePath(basePath, raw);
                }
            }
        }
        return combinePath(basePath, fallbackRelPath);
    }

    static List<BulkEditableFieldDigest> introspectBulkEditableFields(
            Class<?> dtoType,
            Set<String> protectedFields) {
        if (dtoType == null || Object.class.equals(dtoType) || dtoType.isPrimitive()) {
            return List.of();
        }
        List<BulkEditableFieldDigest> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (dtoType.isRecord()) {
            for (RecordComponent component : dtoType.getRecordComponents()) {
                BulkEditable bulkEd = component.getAnnotation(BulkEditable.class);
                if (bulkEd == null) {
                    continue;
                }
                String name = resolveWireFieldName(
                        component.getName(),
                        component.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class));
                if ((protectedFields != null && protectedFields.contains(name)) || !seen.add(name)) {
                    continue;
                }
                AgentActionInputField inputMeta = toInputField(
                        name,
                        component.getType(),
                        component.getAnnotation(UISchema.class),
                        component.getAnnotation(Schema.class),
                        false,
                        false,
                        false);
                List<String> modes = Arrays.stream(bulkEd.modes()).map(Enum::name).toList();
                result.add(new BulkEditableFieldDigest(
                        name,
                        inputMeta.label(),
                        inputMeta.controlType(),
                        modes,
                        bulkEd.allowClear()));
            }
        }
        Class<?> current = dtoType;
        while (current != null && !Object.class.equals(current)) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || java.lang.reflect.Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                BulkEditable bulkEd = AnnotatedElementUtils.findMergedAnnotation(field, BulkEditable.class);
                if (bulkEd == null) {
                    continue;
                }
                String name = resolveWireFieldName(
                        field.getName(),
                        AnnotatedElementUtils.findMergedAnnotation(field, com.fasterxml.jackson.annotation.JsonProperty.class));
                if ((protectedFields != null && protectedFields.contains(name)) || !seen.add(name)) {
                    continue;
                }
                AgentActionInputField inputMeta = toInputField(
                        name,
                        field.getType(),
                        AnnotatedElementUtils.findMergedAnnotation(field, UISchema.class),
                        AnnotatedElementUtils.findMergedAnnotation(field, Schema.class),
                        false,
                        false,
                        false);
                List<String> modes = Arrays.stream(bulkEd.modes()).map(Enum::name).toList();
                result.add(new BulkEditableFieldDigest(
                        name,
                        inputMeta.label(),
                        inputMeta.controlType(),
                        modes,
                        bulkEd.allowClear()));
            }
            current = current.getSuperclass();
        }
        return List.copyOf(result);
    }

    private static String resolveWireFieldName(
            String javaName,
            com.fasterxml.jackson.annotation.JsonProperty jsonProp) {
        if (jsonProp != null && StringUtils.hasText(jsonProp.value())) {
            return jsonProp.value().trim();
        }
        return javaName;
    }

    static List<AgentActionInputField> introspectInputFields(Class<?> dtoType) {
        if (dtoType == null || Object.class.equals(dtoType) || dtoType.isPrimitive()) {
            return List.of();
        }
        List<AgentActionInputField> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (dtoType.isRecord()) {
            for (RecordComponent component : dtoType.getRecordComponents()) {
                seen.add(component.getName());
                fields.add(toInputField(
                        component.getName(),
                        component.getType(),
                        component.getAnnotation(UISchema.class),
                        component.getAnnotation(Schema.class),
                        component.getAnnotation(NotNull.class) != null,
                        component.getAnnotation(NotBlank.class) != null,
                        component.getAnnotation(NotEmpty.class) != null));
            }
        }
        Class<?> current = dtoType;
        while (current != null && !Object.class.equals(current)) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || java.lang.reflect.Modifier.isTransient(field.getModifiers())
                        || !seen.add(field.getName())) {
                    continue;
                }
                fields.add(toInputField(
                        field.getName(),
                        field.getType(),
                        AnnotatedElementUtils.findMergedAnnotation(field, UISchema.class),
                        AnnotatedElementUtils.findMergedAnnotation(field, Schema.class),
                        AnnotatedElementUtils.findMergedAnnotation(field, NotNull.class) != null,
                        AnnotatedElementUtils.findMergedAnnotation(field, NotBlank.class) != null,
                        AnnotatedElementUtils.findMergedAnnotation(field, NotEmpty.class) != null));
            }
            current = current.getSuperclass();
        }
        return List.copyOf(fields);
    }

    static List<String> computeMissingRequiredFields(
            List<AgentActionInputField> inputFields,
            Map<String, Object> mergedInputs) {
        if (inputFields == null || inputFields.isEmpty()) {
            return List.of();
        }
        Map<String, Object> safeInputs = mergedInputs == null ? Map.of() : mergedInputs;
        List<String> missing = new ArrayList<>();
        for (AgentActionInputField field : inputFields) {
            if (!field.required()) {
                continue;
            }
            Object val = safeInputs.get(field.name());
            if (val == null || (val instanceof String str && !StringUtils.hasText(str))) {
                missing.add(field.name());
            }
        }
        return List.copyOf(missing);
    }

    static Map<String, String> validateDto(Validator validator, Object dto) {
        if (validator == null || dto == null) {
            return Map.of();
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        if (violations == null || violations.isEmpty()) {
            return Map.of();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<Object> v : violations) {
            String path = v.getPropertyPath() != null && StringUtils.hasText(v.getPropertyPath().toString())
                    ? v.getPropertyPath().toString()
                    : "_global";
            errors.putIfAbsent(path, v.getMessage());
        }
        return Map.copyOf(errors);
    }

    static String extractLifecycleState(Map<String, Object> record, ResourceStateSnapshot snapshot) {
        if (snapshot != null && StringUtils.hasText(snapshot.state())) {
            return snapshot.state().trim();
        }
        if (record == null || record.isEmpty()) {
            return null;
        }
        for (String candidate : List.of("situacao", "status", "estado", "state")) {
            Object val = record.get(candidate);
            if (val != null && StringUtils.hasText(val.toString())) {
                return val.toString().trim();
            }
        }
        return null;
    }

    static String combinePath(String basePath, String relativePath) {
        String base = basePath == null ? "" : basePath.trim().replaceAll("/+$", "");
        String rel = relativePath == null ? "" : relativePath.trim();
        if (!StringUtils.hasText(rel) || "/".equals(rel)) {
            return StringUtils.hasText(base) ? base : "/";
        }
        if (!rel.startsWith("/")) {
            rel = "/" + rel;
        }
        return base + rel;
    }

    private static AgentActionInputField toInputField(
            String name,
            Class<?> javaType,
            UISchema ui,
            Schema schema,
            boolean notNull,
            boolean notBlank,
            boolean notEmpty) {
        String label = ui != null && StringUtils.hasText(ui.label()) ? ui.label().trim() : name;
        String description = ui != null && StringUtils.hasText(ui.helpText())
                ? ui.helpText().trim()
                : (schema != null && StringUtils.hasText(schema.description()) ? schema.description().trim() : "");
        FieldControlType controlType = ui != null ? ui.controlType() : FieldControlType.AUTO;
        boolean schemaRequired = schema != null && schema.requiredMode() == Schema.RequiredMode.REQUIRED;
        boolean uiRequired = ui != null && ui.required();
        boolean required = notNull || notBlank || notEmpty || schemaRequired || uiRequired;
        List<Map<String, String>> options = extractOptions(ui, schema, javaType);
        return new AgentActionInputField(
                name,
                label,
                description,
                controlType != null ? controlType.getValue() : FieldControlType.AUTO.getValue(),
                javaType != null ? javaType.getSimpleName() : "Object",
                required,
                options);
    }

    private static List<Map<String, String>> extractOptions(UISchema ui, Schema schema, Class<?> javaType) {
        List<Map<String, String>> options = new ArrayList<>();
        if (ui != null && ui.optionItems() != null) {
            for (UiOption opt : ui.optionItems()) {
                if (opt != null && StringUtils.hasText(opt.value())) {
                    options.add(Map.of(
                            "value", opt.value().trim(),
                            "label", StringUtils.hasText(opt.label()) ? opt.label().trim() : opt.value().trim()));
                }
            }
        }
        if (options.isEmpty() && javaType != null && javaType.isEnum()) {
            for (Object constant : javaType.getEnumConstants()) {
                options.add(Map.of("value", constant.toString(), "label", constant.toString()));
            }
        }
        if (options.isEmpty() && schema != null && schema.allowableValues() != null) {
            for (String val : schema.allowableValues()) {
                if (StringUtils.hasText(val)) {
                    options.add(Map.of("value", val.trim(), "label", val.trim()));
                }
            }
        }
        return List.copyOf(options);
    }

    private static Class<?> unwrapReturnDtoType(Method method, Class<?> targetClass) {
        ResolvableType ret = ResolvableType.forMethodReturnType(method, targetClass);
        for (int depth = 0; depth < 3; depth++) {
            Class<?> raw = ret.resolve();
            if (raw == null) {
                return Object.class;
            }
            if (ResponseEntity.class.isAssignableFrom(raw) || RestApiResponse.class.isAssignableFrom(raw)) {
                ResolvableType nested = ret.getGeneric(0);
                if (nested == ResolvableType.NONE) {
                    return Object.class;
                }
                ret = nested;
            } else {
                return raw;
            }
        }
        return ret.resolve(Object.class);
    }

    private static String extractDeterminationId(String rawPath) {
        String clean = rawPath.trim().replaceAll("^/+|/+$", "");
        int idx = clean.indexOf("determinations/");
        if (idx < 0) {
            return null;
        }
        String suffix = clean.substring(idx + "determinations/".length()).trim();
        return suffix.isEmpty() ? null : suffix;
    }

    private static String firstPath(RequestMapping mapping) {
        if (mapping.path() != null && mapping.path().length > 0 && StringUtils.hasText(mapping.path()[0])) {
            return mapping.path()[0].trim();
        }
        if (mapping.value() != null && mapping.value().length > 0 && StringUtils.hasText(mapping.value()[0])) {
            return mapping.value()[0].trim();
        }
        return "";
    }

    private static String resolveHttpMethod(RequestMapping mapping, String fallback) {
        if (mapping != null && mapping.method() != null && mapping.method().length > 0) {
            return mapping.method()[0].name();
        }
        return fallback;
    }

    private static int findRequestBodyIndex(Parameter[] params) {
        for (int i = 0; i < params.length; i++) {
            if (AnnotatedElementUtils.findMergedAnnotation(params[i], RequestBody.class) != null) {
                return i;
            }
        }
        return -1;
    }

    private static int findParameterWithAnnotation(
            Parameter[] params,
            Class<? extends java.lang.annotation.Annotation> annotationType) {
        for (int i = 0; i < params.length; i++) {
            if (AnnotatedElementUtils.findMergedAnnotation(params[i], annotationType) != null) {
                return i;
            }
        }
        return -1;
    }

    private static int findHeaderIndex(Parameter[] params, String expectedHeaderName) {
        String[] discoveredNames = null;
        for (int i = 0; i < params.length; i++) {
            RequestHeader header = AnnotatedElementUtils.findMergedAnnotation(params[i], RequestHeader.class);
            if (header == null) {
                continue;
            }
            String candidate = StringUtils.hasText(header.name()) ? header.name() : header.value();
            if (!StringUtils.hasText(candidate)) {
                if (discoveredNames == null && params[i].getDeclaringExecutable() instanceof Method m) {
                    discoveredNames = PARAM_NAMES.getParameterNames(m);
                }
                if (discoveredNames != null && i < discoveredNames.length) {
                    candidate = discoveredNames[i];
                }
            }
            if (StringUtils.hasText(candidate) && candidate.trim().equalsIgnoreCase(expectedHeaderName)) {
                return i;
            }
        }
        return -1;
    }
}
