package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.prompts.AiPromptTemplates;
import org.praxisplatform.config.dto.AiActionPlan;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.AiActionItem;
import org.praxisplatform.config.dto.AiCapability;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOption;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiPatchDiff;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiRegistryTemplateSearchResult;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.dto.ActionCheck;
import org.praxisplatform.config.dto.IntentAction;
import org.praxisplatform.config.dto.IntentPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiOrchestratorService {

    private static final int DEFAULT_API_SEARCH_LIMIT = 5;
    private static final int DEFAULT_VARIANT_SEARCH_LIMIT = 8;
    private static final double VARIANT_SIMILARITY_THRESHOLD = 0.65d;
    private static final String COMPONENT_ID_TABLE = "praxis-table";
    private static final int MAX_TARGET_CANDIDATES_PER_PATH = 30;
    private static final int BADGE_CARDINALITY_MAX = 6;
    private static final List<String> DEFAULT_BADGE_PALETTE = List.of(
            "primary",
            "accent",
            "warn",
            "success",
            "info"
    );
    private static final String MISSING_VALUES_AND_COLORS = "values_and_colors";
    private static final String MISSING_BADGE_VALUES = "badge.values";
    private static final String GLOBAL_CONFIG_COMPONENT_TYPE = "praxis-global-config-editor";
    private static final String GLOBAL_CONFIG_KEY = "praxis:global-config";
    private static final int CONTEXT_CODE_COMPONENT_DESCRIPTION = 10;
    private static final int CONTEXT_CODE_COMPONENT_SIGNATURE = 20;
    private static final int CONTEXT_CODE_SCHEMA_FIELDS = 30;
    private static final int CONTEXT_CODE_SCHEMA_SAMPLE = 40;
    private static final int CONTEXT_CODE_ENDPOINT_CANDIDATES = 50;
    private static final int CONTEXT_CODE_CURRENT_STATE = 60;
    private static final Set<String> RESOURCE_MISSING_KEYS = Set.of(
            "resourcepath",
            "endpoint",
            "resource",
            "schema",
            "dataset"
    );

    @Value("${praxis.ai.prompt.max-chars.config:12000}")
    private int maxConfigChars;

    @Value("${praxis.ai.prompt.max-chars.schema:12000}")
    private int maxSchemaChars;

    @Value("${praxis.ai.prompt.max-chars.template-config:8000}")
    private int maxTemplateConfigChars;

    @Value("${praxis.ai.prompt.max-chars.template-meta:4000}")
    private int maxTemplateMetaChars;

    @Value("${praxis.ai.prompt.max-chars.capabilities:12000}")
    private int maxCapabilitiesChars;

    @Value("${praxis.ai.prompt.max-chars.capability-notes:3000}")
    private int maxCapabilityNotesChars;

    @Value("${praxis.ai.prompt.max-chars.runtime-metadata:4000}")
    private int maxRuntimeMetadataChars;

    @Value("${praxis.ai.prompt.max-chars.rag-hints:2000}")
    private int maxRagHintsChars;

    @Value("${praxis.ai.prompt.max-chars.concepts:4000}")
    private int maxConceptsChars;

    @Value("${praxis.ai.action-plan.provider:#{null}}")
    private String actionPlanProvider;

    @Value("${praxis.ai.action-plan.model:#{null}}")
    private String actionPlanModel;

    @Value("${praxis.ai.action-plan.temperature:#{null}}")
    private Double actionPlanTemperature;

    @Value("${praxis.ai.action-plan.max-tokens:#{null}}")
    private Integer actionPlanMaxTokens;

    @Value("${praxis.ai.action-plan.retry-temperature:0.1}")
    private double actionPlanRetryTemperature;

    private final AiContextService contextService;
    private final AiProvider aiProvider;
    private final AiInteractionLogger interactionLogger;
    private final ContextRetrievalService retrievalService;
    private final SchemaRetrievalService schemaRetrievalService;
    private final AiRegistryTemplateService templateService;
    private final AiRagContextService ragContextService;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;
    private final AiApiKeyCryptoService apiKeyCryptoService;

    public AiOrchestratorResponse generatePatch(
            AiOrchestratorRequest request,
            String requestBaseUrl,
            String tenantId,
            String userId,
            String environment) {
        AiContextDTO context = contextService.buildContext(
                request.getComponentId(),
                request.getComponentType(),
                request.getAiMode(),
                request.getRequireSchema(),
                request.getCurrentState(),
                request.getResourcePath(),
                request.getSchemaContext());

        if (context.getComponentDefinition() == null) {
            return unknownComponentError(request);
        }

        AiCallConfig frontendConfig = resolveFrontendCallConfig(tenantId, userId, environment);
        EmbeddingService.EmbeddingCallConfig embeddingConfig =
                resolveEmbeddingCallConfig(tenantId, userId, environment);

        AiOrchestratorResponse createFlowResponse = tryHandleCreateFlow(request, context, embeddingConfig);
        if (createFlowResponse != null) {
            return createFlowResponse;
        }

        List<String> warnings = new ArrayList<>();
        TemplateSelection templateSelection = resolveTemplateVariant(request, context, embeddingConfig);
        if (templateSelection.template != null) {
            context.setTemplate(templateSelection.template);
        }
        if (templateSelection.warnings != null && !templateSelection.warnings.isEmpty()) {
            warnings.addAll(templateSelection.warnings);
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "[AiOrchestratorService] Context loaded componentId={} template={} hasDefinition={}",
                    context.getComponentId(),
                    context.getTemplate() != null ? context.getTemplate().getComponentId() : "none",
                    context.getComponentDefinition() != null);
        }

        boolean requireSchema = context.isRequireSchema();
        AiSchemaContext schemaContext = context.getSchemaContext();
        JsonNode resolvedSchema = null;

        if (requireSchema) {
            SchemaResolution schemaResolution = resolveSchema(request, context, requestBaseUrl, embeddingConfig);
            if (schemaResolution.response != null) {
                return schemaResolution.response;
            }
            schemaContext = schemaResolution.schemaContext;
            resolvedSchema = schemaResolution.schema;
        }

        JsonNode currentState = context.getCurrentState();
        String runtimeMetadata = truncateBlock(
                "runtime_metadata",
                safeMetadata(formatRuntimeMetadata(
                        request.getDataProfile(),
                        request.getSchemaFields(),
                        request.getRuntimeState(),
                        request.getContextHints())),
                maxRuntimeMetadataChars);
        List<ColumnDescriptor> columnDescriptors = extractColumnDescriptors(currentState);
        List<String> columnNames = extractColumnNames(columnDescriptors);
        List<String> columnOptions = buildColumnOptions(columnDescriptors);
        List<String> inputKeys = extractObjectKeys(currentState, "inputs");
        List<String> outputKeys = extractObjectKeys(currentState, "outputs");

        List<AiCapability> configCapabilities = extractCapabilities(context.getComponentDefinition());
        List<AiCapability> componentCapabilities = extractComponentCapabilities(context.getComponentDefinition());
        JsonNode componentContext = extractComponentContextPack(context.getComponentDefinition());
        String ragHints = ragContextService.buildRagHints(
                request.getUserPrompt(),
                componentContext,
                context.getTemplate() != null ? context.getTemplate().getTemplateMeta() : null,
                embeddingConfig);
        String ragHintsBlock = truncateBlock(
                "rag_hints",
                safeMetadata(ragHints),
                maxRagHintsChars);
        List<ComponentAction> componentActions = extractComponentActions(componentContext);
        boolean isTable = COMPONENT_ID_TABLE.equals(request.getComponentId());
        Set<String> allowedActionTypes = resolveAllowedActionTypes(componentActions, isTable);
        List<String> columnResolverKeys = isTable
                ? resolveFieldResolverKeys(componentContext, "columns[].field")
                : List.of();
        List<ContextOption> baseFormatOptions = isTable
                ? resolveOptionsForPath(componentContext, configCapabilities, "columns[].format")
                : List.of();
        SelectedFormatSelection selectedFormat = extractSelectedFormatFromHints(request.getContextHints());
        List<ContextOption> formatOptions = isTable
                ? augmentFormatOptionsForPrompt(
                        baseFormatOptions,
                        request.getUserPrompt(),
                        selectedFormat != null ? selectedFormat.targetField : null)
                : List.of();
        ArrayNode targetCandidates = !isTable
                ? buildTargetCandidates(componentContext, currentState)
                : objectMapper.createArrayNode();
        List<String> targetOptions = !isTable
                ? buildTargetOptions(targetCandidates)
                : List.of();
        List<String> configCategories = extractCategoryNames(configCapabilities);
        List<String> componentCategories = extractCategoryNames(componentCapabilities);

        if (request.getSuggestedPatch() != null && !request.getSuggestedPatch().isNull()) {
            return applySuggestedPatch(
                    request.getSuggestedPatch(),
                    currentState,
                    request.getComponentId(),
                    warnings,
                    configCapabilities,
                    componentCapabilities,
                    componentContext);
        }

        AiOrchestratorResponse contextHintPatch = tryApplyContextHintPatch(
                request.getContextHints(),
                currentState,
                request.getComponentId(),
                warnings,
                configCapabilities,
                componentCapabilities,
                componentContext);
        if (contextHintPatch != null) {
            return contextHintPatch;
        }

        AiIntentClassification intent = classifyIntent(
                request.getUserPrompt(),
                columnNames,
                inputKeys,
                outputKeys,
                configCategories,
                componentCategories,
                runtimeMetadata,
                request,
                frontendConfig);

        if (intent == null) {
            return error("Falha ao classificar a intenção do usuário.");
        }
        intent = normalizeIntent(intent, columnNames, configCategories, componentCategories, warnings);
        adjustPageBuilderCreateIntent(intent, request, context);
        AiActionPlan actionPlan = null;
        List<AiActionItem> expectedActions = List.of();
        boolean createOnlyPlan = false;
        List<String> missingContext = intent.getMissingContext();
        boolean shouldProbeTableActions = isTable
                && !componentActions.isEmpty()
                && missingContext != null
                && missingContext.stream()
                .map(this::normalizeMissingKey)
                .anyMatch(key -> key.contains("column"));
        if (shouldProbeTableActions) {
            actionPlan = extractTableActionPlan(
                    request.getUserPrompt(),
                    columnDescriptors,
                    formatOptions,
                    componentActions,
                    ragHintsBlock,
                    context,
                    request,
                    frontendConfig,
                    resolvedSchema,
                    embeddingConfig);
        }
        if (shouldIgnoreColumnClarification(isTable, columnNames, missingContext)) {
            missingContext = removeMissingContext(missingContext, "column");
            intent.setMissingContext(missingContext == null || missingContext.isEmpty() ? null : missingContext);
            if (intent.getMissingContext() == null) {
                intent.setNeedsClarification(false);
            }
        }
        if (shouldIgnoreMissingColumnForCreate(intent, actionPlan, componentActions)) {
            missingContext = removeMissingContext(intent.getMissingContext(), "column");
            intent.setMissingContext(missingContext == null || missingContext.isEmpty() ? null : missingContext);
            if (intent.getMissingContext() == null) {
                intent.setNeedsClarification(false);
            }
        }
        if (shouldIgnoreCategoryClarification(intent, componentActions)) {
            intent.setNeedsClarification(false);
            intent.setMissingContext(null);
            if (intent.getOptions() != null && !intent.getOptions().isEmpty()) {
                intent.setOptions(null);
            }
            warnings.add("Categoria indefinida ignorada devido a actionCatalog disponível.");
        }
        if (Boolean.TRUE.equals(intent.getNeedsClarification())
                || (missingContext != null && !missingContext.isEmpty())) {
            if (canBypassClarification(intent, componentActions)) {
                intent.setNeedsClarification(false);
                intent.setMissingContext(null);
                if (intent.getOptions() != null && !intent.getOptions().isEmpty()) {
                    intent.setOptions(null);
                }
                warnings.add("Clarificacao ignorada para toggle simples; aplicando defaults.");
            } else {
            AiOrchestratorResponse badgeResolution = tryResolveBadgeMissingContext(
                    intent,
                    request,
                    currentState,
                    warnings,
                    configCapabilities,
                    componentCapabilities,
                    componentContext);
            if (badgeResolution != null) {
                return badgeResolution;
            }
            AiOrchestratorResponse resourceClarification = resolveResourceClarification(
                    request, intent, embeddingConfig);
            if (resourceClarification != null) {
                return resourceClarification;
            }
            missingContext = intent.getMissingContext();
            if (Boolean.TRUE.equals(intent.getNeedsClarification())
                    || (missingContext != null && !missingContext.isEmpty())) {
                if (isTable
                        && "format".equalsIgnoreCase(intent.getCategory())
                        && isSensitiveMaskField(intent.getTargetField())) {
                    ClarificationPayload payload = buildFormatClarificationPayload(intent, formatOptions);
                    return clarification(
                            buildClarificationMessage(intent, request),
                            payload.options,
                            payload.payloads);
                }
                return clarification(buildClarificationMessage(intent, request), intent.getOptions());
            }
            }
        }
        if ("ask_about_config".equalsIgnoreCase(intent.getIntent())) {
            String answer = answerQuestion(request.getUserPrompt(), currentState, request, frontendConfig);
            return info(answer);
        }

        if (isTable && !componentActions.isEmpty()) {
            if (actionPlan == null) {
                actionPlan = extractTableActionPlan(
                        request.getUserPrompt(),
                        columnDescriptors,
                        formatOptions,
                        componentActions,
                        ragHintsBlock,
                        context,
                        request,
                        frontendConfig,
                        resolvedSchema,
                        embeddingConfig);
            }
            actionPlan = applyActionPlanDefaults(actionPlan, componentActions);
            actionPlan = applySingleActionTargetFallback(
                    actionPlan,
                    intent,
                    columnDescriptors,
                    columnResolverKeys);
            actionPlan = normalizeActionPlanFormatValues(
                    actionPlan,
                    formatOptions,
                    request != null ? request.getUserPrompt() : null,
                    warnings);
            Set<String> globalActions = identifyGlobalActions(componentActions);
            expectedActions = normalizePlanActions(
                    actionPlan,
                    columnDescriptors,
                    allowedActionTypes,
                    columnResolverKeys,
                    globalActions);
            List<AiActionItem> fallbackActions = deriveFallbackTableActions(
                    request.getUserPrompt(),
                    columnDescriptors,
                    componentActions,
                    allowedActionTypes,
                    columnResolverKeys,
                    formatOptions);
            expectedActions = mergeActions(expectedActions, fallbackActions);
            expectedActions = applyPlanValueFallback(
                    expectedActions,
                    actionPlan,
                    intent,
                    columnDescriptors,
                    allowedActionTypes,
                    columnResolverKeys);
            expectedActions = applySelectedFormatOverride(
                    expectedActions,
                    selectedFormat,
                    intent,
                    columnDescriptors,
                    columnResolverKeys,
                    warnings);

            List<String> ambiguityOptions = collectAmbiguityOptions(actionPlan);
            boolean hasAmbiguity = actionPlan != null
                    && actionPlan.getAmbiguities() != null
                    && !actionPlan.getAmbiguities().isEmpty();
            createOnlyPlan = isCreateOnlyPlan(actionPlan, componentActions);
            if (hasAmbiguity && expectedActions.isEmpty() && !createOnlyPlan) {
                List<String> options = !ambiguityOptions.isEmpty()
                        ? ambiguityOptions
                        : columnOptions;
                return clarification(
                        "Preciso da coluna correta para aplicar o ajuste.", options);
            }
            List<String> unknownFields = findUnknownActionFields(expectedActions, columnNames);
            if (!unknownFields.isEmpty() && !createOnlyPlan) {
                List<String> suggestions = suggestClosestColumns(unknownFields, columnDescriptors);
                String message = buildUnknownColumnsMessage(unknownFields, suggestions);
                List<String> options = suggestions.isEmpty() ? columnOptions : suggestions;
                return clarification(message, options);
            }
            AiActionItem missingFormat = findFormatActionMissingValue(expectedActions);
            if (missingFormat != null) {
                ColumnDescriptor target = findColumnByField(missingFormat.getField(), columnDescriptors);
                String label = target != null
                        ? displayColumnLabel(target, countHeaders(columnDescriptors))
                        : missingFormat.getField();
                List<ContextOption> choices = formatOptions;
                List<AiOption> formatPayloads = null;
                if (isSensitiveMaskField(missingFormat.getField())) {
                    choices = buildMaskOptionsForField(missingFormat.getField());
                    formatPayloads = buildMaskOptionPayloads(missingFormat.getField(), choices);
                }
                List<String> formatChoices = buildOptionLabels(choices);
                if (formatPayloads == null || formatPayloads.isEmpty()) {
                    formatPayloads = buildOptionPayloads(choices);
                }
                String message = formatChoices.isEmpty()
                        ? "Informe o formato desejado para a coluna " + label + "."
                        : "Qual formato deseja aplicar na coluna " + label + "?";
                return clarification(
                        message,
                        formatChoices,
                        formatPayloads);
            }
        } else if (!componentActions.isEmpty()) {
            actionPlan = extractComponentActionPlan(
                    request.getUserPrompt(),
                    componentActions,
                    targetCandidates,
                    ragHintsBlock,
                    context,
                    request,
                    frontendConfig,
                    resolvedSchema,
                    embeddingConfig);
            actionPlan = applyActionPlanDefaults(actionPlan, componentActions);
            ActionPlanClarification clarification = resolveActionPlanClarification(
                    actionPlan,
                    componentActions,
                    targetOptions);
            if (clarification != null) {
                return clarification(clarification.message, clarification.options);
            }
            createOnlyPlan = isCreateOnlyPlan(actionPlan, componentActions);
        }

        String scope = normalizeScope(intent.getScope());
        if ("component".equals(scope) && inputKeys.isEmpty() && outputKeys.isEmpty()) {
            warnings.add("Scope=component mas currentState nao inclui inputs/outputs; usando contexto completo.");
        }
        JsonNode contextConfig = resolveContextForScope(
                scope, request.getComponentId(), currentState, intent);
        List<AiCapability> scopedCapabilities = selectCapabilitiesByScope(
                scope, configCapabilities, componentCapabilities);
        List<AiCapability> filteredCaps = filterCapabilities(
                request.getComponentId(), intent.getCategory(), scope, scopedCapabilities);
        if ((filteredCaps == null || filteredCaps.isEmpty())
                && configCapabilities != null
                && !configCapabilities.isEmpty()) {
            List<AiCapability> fallback = filterCapabilities(
                    request.getComponentId(), intent.getCategory(), "config", configCapabilities);
            if (fallback == null || fallback.isEmpty()) {
                fallback = configCapabilities;
            }
            filteredCaps = fallback;
            warnings.add("Capabilities filtradas vazias; usando fallback de configuracoes.");
            if ("component".equals(scope)) {
                scope = "config";
                intent.setScope(scope);
                contextConfig = resolveContextForScope(
                        "config", request.getComponentId(), currentState, intent);
                warnings.add("Fallback aplicado ao contexto (config) devido a scope=component.");
            }
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "[AiOrchestratorService] Capabilities selected scope={} category={} count={} samplePaths={}",
                    scope,
                    intent.getCategory(),
                    filteredCaps != null ? filteredCaps.size() : 0,
                    summarizeCapabilityPaths(filteredCaps, 12));
        }
        IntentPlan intentPlan = generateIntentPlan(
                request,
                context,
                filteredCaps,
                currentState,
                componentContext,
                frontendConfig);
        intentPlan = applyDeterministicIntentChecks(
                intentPlan,
                actionPlan,
                componentActions,
                currentState,
                isTable,
                columnDescriptors,
                columnResolverKeys);
        List<String> planQuestions = intentPlan != null ? intentPlan.getQuestions() : null;
        if (planQuestions != null && !planQuestions.isEmpty()) {
            if (shouldIgnoreIntentPlanQuestions(intent, intentPlan, componentActions)) {
                intentPlan.setQuestions(List.of());
                warnings.add("Perguntas do intent_plan ignoradas para toggle simples; aplicando defaults.");
            } else {
            AiOrchestratorResponse deterministicFallback = tryResolveComputedFallback(
                    request,
                    currentState,
                    warnings,
                    configCapabilities,
                    componentCapabilities,
                    componentContext);
            if (deterministicFallback != null) {
                return deterministicFallback;
            }
            deterministicFallback = tryResolveRendererRuleFallback(
                    request,
                    currentState,
                    warnings,
                    configCapabilities,
                    componentCapabilities,
                    componentContext);
            if (deterministicFallback != null) {
                return deterministicFallback;
            }
            return clarification(buildQuestionsMessage(planQuestions), null);
            }
        }
        String intentPlanJson = formatIntentPlan(intentPlan);
        String capabilityNotes = formatCapabilityNotes(
                "component".equals(scope) || "mixed".equals(scope)
                        ? extractComponentCapabilityNotes(context.getComponentDefinition())
                        : List.of());
        String actionNotes = isTable
                ? formatActionNotes(expectedActions)
                : formatActionPlanNotes(actionPlan);
        String combinedNotes = joinNotes(capabilityNotes, actionNotes);

        Map<String, JsonNode> aiConcepts = extractAiConcepts(context.getComponentDefinition());
        List<String> relatedConceptIds = resolveRelatedConceptIds(componentActions, actionPlan, expectedActions);
        String conceptsBlock = formatConceptsForPrompt(aiConcepts, relatedConceptIds);
        if (log.isDebugEnabled() && relatedConceptIds != null && !relatedConceptIds.isEmpty()) {
            List<String> missingConcepts = resolveMissingConceptIds(aiConcepts, relatedConceptIds);
            log.debug(
                    "[AiOrchestratorService] Concepts resolved count={} chars={} missing={}",
                    relatedConceptIds.size(),
                    conceptsBlock != null ? conceptsBlock.length() : 0,
                    missingConcepts);
        }

        String prompt = buildExecutionPrompt(
                request.getUserPrompt(),
                context,
                contextConfig,
                filteredCaps,
                combinedNotes,
                conceptsBlock,
                schemaContext,
                resolvedSchema,
                runtimeMetadata,
                ragHintsBlock,
                intentPlanJson,
                null);

        JsonNode result = callAiJson("patch_generation", prompt, null, frontendConfig, request, 1);
        ContextRequest contextRequest = parseContextRequest(result);
        ActionPlanPatchResult actionPlanFallback = null;
        if (contextRequest != null && contextRequest.hasCodes() && createOnlyPlan) {
            actionPlanFallback = renderActionPlanPatch(actionPlan, componentActions);
            if (actionPlanFallback != null
                    && actionPlanFallback.patch != null
                    && actionPlanFallback.missingTokens.isEmpty()) {
                log.info("[AiOrchestratorService] contextRequest ignored; patch derived from action plan");
                ObjectNode fallbackResult = objectMapper.createObjectNode();
                fallbackResult.set("patch", actionPlanFallback.patch);
                result = fallbackResult;
                contextRequest = null;
                warnings.add("ContextRequest ignorado; patch derivado do action plan.");
            }
        }
        if (contextRequest != null && contextRequest.hasCodes()) {
            log.info("[AiOrchestratorService] contextRequest codes={} message={}",
                    contextRequest.codes,
                    contextRequest.message);
            JsonNode resolvedHints = buildContextHintsFromRequest(
                    context,
                    request,
                    resolvedSchema,
                    embeddingConfig,
                    contextRequest.codes);
            if (resolvedHints != null && !resolvedHints.isNull() && resolvedHints.size() > 0) {
                log.info("[AiOrchestratorService] contextRequest resolved hints keys={}",
                        resolvedHints.isObject()
                                ? resolveContextHintKeys(resolvedHints)
                                : List.of("non-object"));
                JsonNode mergedHints = mergeContextHints(request.getContextHints(), resolvedHints);
                String retryRuntimeMetadata = truncateBlock(
                        "runtime_metadata",
                        safeMetadata(formatRuntimeMetadata(
                                request.getDataProfile(),
                                request.getSchemaFields(),
                                request.getRuntimeState(),
                                mergedHints)),
                        maxRuntimeMetadataChars);
                String retryPrompt = buildExecutionPrompt(
                        request.getUserPrompt(),
                        context,
                        contextConfig,
                        filteredCaps,
                        combinedNotes,
                        conceptsBlock,
                        schemaContext,
                        resolvedSchema,
                        retryRuntimeMetadata,
                        ragHintsBlock,
                        intentPlanJson,
                        null);
                JsonNode retryResult = callAiJson("patch_generation", retryPrompt, null, frontendConfig, request, 2);
                ContextRequest retryContextRequest = parseContextRequest(retryResult);
                if (retryContextRequest != null && retryContextRequest.hasCodes()) {
                    log.info("[AiOrchestratorService] contextRequest retry requested codes={} message={}",
                            retryContextRequest.codes,
                            retryContextRequest.message);
                    return clarificationWithContextRequest(
                            retryContextRequest.message,
                            retryContextRequest.codes);
                }
                result = retryResult;
            } else {
                log.info("[AiOrchestratorService] contextRequest could not be resolved");
                return clarificationWithContextRequest(contextRequest.message, contextRequest.codes);
            }
        }
        if (result == null || result.get("patch") == null) {
            if (createOnlyPlan) {
                if (actionPlanFallback == null) {
                    actionPlanFallback = renderActionPlanPatch(actionPlan, componentActions);
                }
                if (actionPlanFallback != null
                        && actionPlanFallback.patch != null
                        && actionPlanFallback.missingTokens.isEmpty()) {
                    warnings.add("Patch gerado a partir do action plan (fallback).");
                    ObjectNode fallbackResult = objectMapper.createObjectNode();
                    fallbackResult.set("patch", actionPlanFallback.patch);
                    result = fallbackResult;
                }
            }
        }
        if (result == null || result.get("patch") == null) {
            return error("Nenhum patch gerado.");
        }

        JsonNode patchNode = result.get("patch");
        String explanation = textOrNull(result.get("explanation"));

        boolean allowUnknownColumns = createOnlyPlan
                || (request != null && looksLikeCreateColumnPrompt(request.getUserPrompt()));
        SemanticPatchCheck semanticCheck = validateSemanticPatch(
                patchNode, currentState, warnings, allowUnknownColumns);
        if (!semanticCheck.valid) {
            if (semanticCheck.needsClarification) {
                return clarification(semanticCheck.message, semanticCheck.options);
            }
            return errorWithWarnings(semanticCheck.message, warnings);
        }
        patchNode = semanticCheck.patch;

        if (actionPlan != null
                && actionPlan.getActions() != null
                && !actionPlan.getActions().isEmpty()
                && componentActions != null
                && !componentActions.isEmpty()) {
            AiActionPlan resolvedPlan = isTable
                    ? resolveTableActionPlanTargets(actionPlan, columnDescriptors, columnResolverKeys)
                    : actionPlan;
            ActionPlanCoverage coverage = applyActionPlanCoverage(
                    resolvedPlan,
                    componentActions,
                    patchNode,
                    currentState);
            if (coverage.patch != null && !coverage.missingActions.isEmpty()) {
                patchNode = mergePatchNodes(patchNode, coverage.patch);
                warnings.add("Patch complementado com acoes do plano: "
                        + String.join(", ", coverage.missingActions));
                explanation = appendExplanation(
                        explanation,
                        "Apliquei acoes do plano: " + String.join(", ", coverage.missingActions) + ".");
            }
        }

        JsonNode normalizedPatch = normalizePatch(
                request.getComponentId(), patchNode);
        SanitizeResult sanitizeResult = sanitizePatch(normalizedPatch, filteredCaps);
        List<String> sanitizeWarnings = filterInternalWarnings(
                sanitizeResult.warnings,
                patchNode,
                request.getComponentId());
        if (sanitizeWarnings != null && !sanitizeWarnings.isEmpty()) {
            warnings.addAll(sanitizeWarnings);
        }

        if (sanitizeResult.sanitized == null || isEmptyObject(sanitizeResult.sanitized)) {
            return errorWithWarnings(
                    "O patch gerado não continha configurações válidas permitidas.",
                    warnings);
        }

        EnumValidationResult enumValidation = validateEnumValues(
                sanitizeResult.sanitized,
                componentContext,
                filteredCaps);
        if (!enumValidation.valid) {
            return invalidEnumValue(enumValidation, warnings);
        }

        List<AiPatchDiff> initialDiff = buildPatchDiff(currentState, sanitizeResult.sanitized);
        CompletenessResult completeness = evaluateCompleteness(
                intentPlan,
                initialDiff,
                currentState,
                componentContext,
                filteredCaps,
                createOnlyPlan);
        logPatchDiagnostics(
                request,
                "initial",
                patchNode,
                normalizedPatch,
                sanitizeResult.sanitized,
                initialDiff,
                completeness);
        if (completeness != null && !completeness.complete) {
            List<IntentAction> missingActions = completeness.missingActions;
            String completenessHints = buildCompletenessHints(intentPlan, initialDiff, missingActions);
            String retryPrompt = buildExecutionPrompt(
                    request.getUserPrompt(),
                    context,
                    contextConfig,
                    filteredCaps,
                    combinedNotes,
                    conceptsBlock,
                    schemaContext,
                    resolvedSchema,
                    runtimeMetadata,
                    ragHintsBlock,
                    intentPlanJson,
                    completenessHints);
            JsonNode retryResult = callAiJson("patch_generation_retry", retryPrompt, null, frontendConfig, request, 2);
            ContextRequest retryContextRequest = parseContextRequest(retryResult);
            if (retryContextRequest != null && retryContextRequest.hasCodes()) {
                return clarificationWithContextRequest(
                        retryContextRequest.message,
                        retryContextRequest.codes);
            }
            JsonNode retryPatch = retryResult != null ? retryResult.get("patch") : null;
            if (retryPatch != null && !retryPatch.isNull()) {
                SemanticPatchCheck retryCheck = validateSemanticPatch(
                        retryPatch, currentState, warnings, allowUnknownColumns);
                if (retryCheck.valid && retryCheck.patch != null) {
                    JsonNode normalizedRetry = normalizePatch(request.getComponentId(), retryCheck.patch);
                    SanitizeResult retrySanitized = sanitizePatch(normalizedRetry, filteredCaps);
                    List<String> retryWarnings = filterInternalWarnings(
                            retrySanitized.warnings,
                            retryPatch,
                            request.getComponentId());
                    if (retryWarnings != null && !retryWarnings.isEmpty()) {
                        warnings.addAll(retryWarnings);
                    }
                    if (retrySanitized.sanitized != null && !isEmptyObject(retrySanitized.sanitized)) {
                        JsonNode mergedPatch = mergePatchNodes(sanitizeResult.sanitized, retrySanitized.sanitized);
                        EnumValidationResult retryEnumValidation = validateEnumValues(
                                mergedPatch,
                                componentContext,
                                filteredCaps);
                        if (retryEnumValidation.valid) {
                            List<AiPatchDiff> mergedDiff = buildPatchDiff(currentState, mergedPatch);
                            CompletenessResult retryCompleteness = evaluateCompleteness(
                                    intentPlan,
                                    mergedDiff,
                                    currentState,
                                    componentContext,
                                    filteredCaps,
                                    createOnlyPlan);
                            logPatchDiagnostics(
                                    request,
                                    "retry",
                                    retryPatch,
                                    normalizedRetry,
                                    mergedPatch,
                                    mergedDiff,
                                    retryCompleteness);
                            if (retryCompleteness != null && retryCompleteness.complete) {
                                warnings.add("Patch complementado apos retry de completeness.");
                                return AiOrchestratorResponse.builder()
                                        .type("patch")
                                        .patch(mergedPatch)
                                        .diff(mergedDiff)
                                        .explanation(explanation)
                                        .warnings(warnings.isEmpty() ? null : warnings)
                                        .build();
                            }
                            completeness = retryCompleteness;
                            sanitizeResult = new SanitizeResult(mergedPatch, warnings);
                            initialDiff = mergedDiff;
                        }
                    }
                }
            }
            List<String> questions = completeness != null ? completeness.questions : null;
            if (createOnlyPlan) {
                warnings.add("Completeness ignorada para plano de criacao; patch aplicado com melhor esforco.");
                return AiOrchestratorResponse.builder()
                        .type("patch")
                        .patch(sanitizeResult.sanitized)
                        .diff(initialDiff)
                        .explanation(explanation)
                        .warnings(warnings.isEmpty() ? null : warnings)
                        .build();
            }
            if (questions != null && !questions.isEmpty()) {
                AiOrchestratorResponse deterministicFallback = tryResolveComputedFallback(
                        request,
                        currentState,
                        warnings,
                        configCapabilities,
                        componentCapabilities,
                        componentContext);
                if (deterministicFallback != null) {
                    return deterministicFallback;
                }
                deterministicFallback = tryResolveRendererRuleFallback(
                        request,
                        currentState,
                        warnings,
                        configCapabilities,
                        componentCapabilities,
                        componentContext);
                if (deterministicFallback != null) {
                    return deterministicFallback;
                }
            }
            if (questions != null && !questions.isEmpty()) {
                return clarification(buildQuestionsMessage(questions), null);
            }
            return clarification("Preciso de mais detalhes para completar o ajuste.", null);
        }

        return AiOrchestratorResponse.builder()
                .type("patch")
                .patch(sanitizeResult.sanitized)
                .diff(initialDiff)
                .explanation(explanation)
                .warnings(warnings.isEmpty() ? null : warnings)
                .build();
    }

    private void logPatchDiagnostics(
            AiOrchestratorRequest request,
            String stage,
            JsonNode rawPatch,
            JsonNode normalizedPatch,
            JsonNode sanitizedPatch,
            List<AiPatchDiff> diff,
            CompletenessResult completeness) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("stage", stage);
        if (rawPatch != null) {
            payload.set("patchRaw", rawPatch);
        } else {
            payload.putNull("patchRaw");
        }
        if (normalizedPatch != null) {
            payload.set("patchNormalized", normalizedPatch);
        } else {
            payload.putNull("patchNormalized");
        }
        if (sanitizedPatch != null) {
            payload.set("patchSanitized", sanitizedPatch);
        } else {
            payload.putNull("patchSanitized");
        }
        if (diff != null) {
            payload.set("diff", objectMapper.valueToTree(diff));
        } else {
            payload.putNull("diff");
        }
        if (completeness != null) {
            ObjectNode completenessNode = payload.putObject("completeness");
            completenessNode.put("complete", completeness.complete);
            completenessNode.set("missingActions", objectMapper.valueToTree(completeness.missingActions));
            completenessNode.set("questions", objectMapper.valueToTree(completeness.questions));
        } else {
            payload.putNull("completeness");
        }
        interactionLogger.logDiagnostic(request, "patch_diagnostics", payload);
    }

    private AiIntentClassification classifyIntent(
            String userPrompt,
            List<String> columns,
            List<String> inputs,
            List<String> outputs,
            List<String> configCategories,
            List<String> componentCategories,
            String runtimeMetadata,
            AiOrchestratorRequest request,
            AiCallConfig callConfig) {
        String prompt = AiPromptTemplates.buildPrompt(
                AiPromptTemplates.PROMPT_INTENT_CLASSIFIER,
                Map.of(
                        "USER_INPUT", safe(userPrompt),
                        "COLUMNS_LIST", objectMapper.valueToTree(columns).toString(),
                        "INPUTS_LIST", objectMapper.valueToTree(inputs).toString(),
                        "OUTPUTS_LIST", objectMapper.valueToTree(outputs).toString(),
                        "RUNTIME_METADATA", safeMetadata(runtimeMetadata),
                        "CONFIG_CATEGORIES", objectMapper.valueToTree(configCategories).toString(),
                        "COMPONENT_CATEGORIES", objectMapper.valueToTree(componentCategories).toString()));
        AiJsonSchema schema = buildIntentSchema();
        JsonNode json = callAiJson("intent_classification", prompt, schema, callConfig, request, 1);
        if (json != null) {
            log.info("[AiOrchestratorService] Intent raw json (nodeType={}): {}", json.getNodeType(), json);
        }
        if (json == null) return null;
        return objectMapper.convertValue(json, AiIntentClassification.class);
    }

    private AiJsonSchema buildIntentSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode properties = schema.putObject("properties");

        ObjectNode intent = properties.putObject("intent");
        intent.put("type", "string");
        intent.putArray("enum")
                .add("update_column_rules")
                .add("toggle_feature")
                .add("global_style")
                .add("ask_about_config")
                .add("unknown");

        ObjectNode targetField = properties.putObject("targetField");
        targetField.put("type", "string");
        targetField.put("nullable", true);

        properties.putObject("category").put("type", "string");

        ObjectNode scope = properties.putObject("scope");
        scope.put("type", "string");
        scope.putArray("enum").add("config").add("component").add("mixed");

        properties.putObject("needsClarification").put("type", "boolean");

        ObjectNode missingContext = properties.putObject("missingContext");
        missingContext.put("type", "array");
        missingContext.putObject("items").put("type", "string");

        ObjectNode options = properties.putObject("options");
        options.put("type", "array");
        options.putObject("items").put("type", "string");

        schema.putArray("required")
                .add("intent")
                .add("targetField")
                .add("category")
                .add("scope")
                .add("needsClarification")
                .add("missingContext")
                .add("options");
        return AiJsonSchema.of(schema.toString(), AiIntentClassification.class);
    }

    private AiJsonSchema buildIntentPlanSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ArrayNode required = schema.putArray("required");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("intent").put("type", "string");
        required.add("intent");

        ObjectNode actions = properties.putObject("actions");
        actions.put("type", "array");
        required.add("actions");
        ObjectNode actionItem = actions.putObject("items");
        actionItem.put("type", "object");
        actionItem.put("additionalProperties", false);
        ObjectNode actionProps = actionItem.putObject("properties");
        ArrayNode actionRequired = actionItem.putArray("required");
        actionProps.putObject("id").put("type", "string");
        actionRequired.add("id");
        ObjectNode checks = actionProps.putObject("checks");
        checks.put("type", "array");
        actionRequired.add("checks");
        ObjectNode checkItem = checks.putObject("items");
        checkItem.put("type", "object");
        checkItem.put("additionalProperties", false);
        ObjectNode checkProps = checkItem.putObject("properties");
        ArrayNode checkRequired = checkItem.putArray("required");
        checkProps.putObject("type").put("type", "string");
        checkRequired.add("type");
        checkProps.putObject("path").put("type", "string");
        checkRequired.add("path");
        ObjectNode value = checkProps.putObject("value");
        checkRequired.add("value");
        ArrayNode valueAnyOf = value.putArray("anyOf");
        valueAnyOf.addObject().put("type", "string");
        valueAnyOf.addObject().put("type", "number");
        valueAnyOf.addObject().put("type", "boolean");
        ObjectNode arrayType = valueAnyOf.addObject();
        arrayType.put("type", "array");
        ArrayNode arrayItemsAnyOf = arrayType.putObject("items").putArray("anyOf");
        arrayItemsAnyOf.addObject().put("type", "string");
        arrayItemsAnyOf.addObject().put("type", "number");
        arrayItemsAnyOf.addObject().put("type", "boolean");
        arrayItemsAnyOf.addObject().put("type", "null");
        valueAnyOf.addObject().put("type", "null");

        ObjectNode questions = properties.putObject("questions");
        questions.put("type", "array");
        questions.putObject("items").put("type", "string");
        required.add("questions");

        return AiJsonSchema.of(schema.toString(), IntentPlan.class);
    }

    private AiActionPlan extractTableActionPlan(
            String userPrompt,
            List<ColumnDescriptor> columns,
            List<ContextOption> formatOptions,
            List<ComponentAction> actionCatalog,
            String ragHints,
            AiContextDTO context,
            AiOrchestratorRequest request,
            AiCallConfig callConfig,
            JsonNode resolvedSchema,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        ArrayNode columnsNode = objectMapper.createArrayNode();
        if (columns != null) {
            for (ColumnDescriptor col : columns) {
                if (col == null || col.field == null || col.field.isBlank()) continue;
                ObjectNode node = objectMapper.createObjectNode();
                node.put("field", col.field);
                if (col.header != null && !col.header.isBlank()) {
                    node.put("header", col.header);
                }
                columnsNode.add(node);
            }
        }
        ArrayNode actionsNode = buildActionCatalogNode(actionCatalog);
        String formatChoices = objectMapper.valueToTree(buildOptionLabels(formatOptions)).toString();
        String prompt = AiPromptTemplates.buildPrompt(
                AiPromptTemplates.PROMPT_TABLE_ACTION_PLAN,
                Map.of(
                        "USER_INPUT", safe(userPrompt),
                        "COLUMNS_LIST", columnsNode.toString(),
                        "ACTION_CATALOG", actionsNode.toString(),
                        "RAG_HINTS", safe(ragHints),
                        "FORMAT_OPTIONS", formatChoices,
                        "CONTEXT_HINTS", formatContextHints(request != null ? request.getContextHints() : null)));
        AiJsonSchema planSchema = buildTableActionPlanSchema(actionCatalog);
        JsonNode json = generateActionPlanJson("table_action_plan", prompt, planSchema, request, callConfig);
        ContextRequest contextRequest = parseContextRequest(json);
        if (contextRequest != null && contextRequest.hasCodes()) {
            JsonNode resolvedHints = buildContextHintsFromRequest(
                    context,
                    request,
                    resolvedSchema,
                    embeddingConfig,
                    contextRequest.codes);
            JsonNode merged = mergeContextHints(
                    request != null ? request.getContextHints() : null,
                    resolvedHints);
            String retryPrompt = AiPromptTemplates.buildPrompt(
                    AiPromptTemplates.PROMPT_TABLE_ACTION_PLAN,
                    Map.of(
                            "USER_INPUT", safe(userPrompt),
                            "COLUMNS_LIST", columnsNode.toString(),
                            "ACTION_CATALOG", actionsNode.toString(),
                            "RAG_HINTS", safe(ragHints),
                            "FORMAT_OPTIONS", formatChoices,
                            "CONTEXT_HINTS", formatContextHints(merged)));
            json = generateActionPlanJson("table_action_plan", retryPrompt, planSchema, request, callConfig);
        }
        if (json == null) {
            return null;
        }
        if (parseContextRequest(json) != null) {
            return null;
        }
        return objectMapper.convertValue(json, AiActionPlan.class);
    }

    private AiActionPlan extractComponentActionPlan(
            String userPrompt,
            List<ComponentAction> actionCatalog,
            ArrayNode targetCandidates,
            String ragHints,
            AiContextDTO context,
            AiOrchestratorRequest request,
            AiCallConfig callConfig,
            JsonNode resolvedSchema,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        ArrayNode actionsNode = buildActionCatalogNode(actionCatalog);
        String candidates = targetCandidates != null ? targetCandidates.toString() : "[]";
        String prompt = AiPromptTemplates.buildPrompt(
                AiPromptTemplates.PROMPT_COMPONENT_ACTION_PLAN,
                Map.of(
                        "USER_INPUT", safe(userPrompt),
                        "ACTION_CATALOG", actionsNode.toString(),
                        "TARGET_CANDIDATES", candidates,
                        "RAG_HINTS", safe(ragHints),
                        "CONTEXT_HINTS", formatContextHints(request != null ? request.getContextHints() : null)));
        AiJsonSchema planSchema = buildComponentActionPlanSchema(actionCatalog);
        JsonNode json = generateActionPlanJson("component_action_plan", prompt, planSchema, request, callConfig);
        ContextRequest contextRequest = parseContextRequest(json);
        if (contextRequest != null && contextRequest.hasCodes()) {
            JsonNode resolvedHints = buildContextHintsFromRequest(
                    context,
                    request,
                    resolvedSchema,
                    embeddingConfig,
                    contextRequest.codes);
            JsonNode merged = mergeContextHints(
                    request != null ? request.getContextHints() : null,
                    resolvedHints);
            String retryPrompt = AiPromptTemplates.buildPrompt(
                    AiPromptTemplates.PROMPT_COMPONENT_ACTION_PLAN,
                    Map.of(
                            "USER_INPUT", safe(userPrompt),
                            "ACTION_CATALOG", actionsNode.toString(),
                            "TARGET_CANDIDATES", candidates,
                            "RAG_HINTS", safe(ragHints),
                            "CONTEXT_HINTS", formatContextHints(merged)));
            json = generateActionPlanJson("component_action_plan", retryPrompt, planSchema, request, callConfig);
        }
        if (json == null) {
            return null;
        }
        if (parseContextRequest(json) != null) {
            return null;
        }
        return objectMapper.convertValue(json, AiActionPlan.class);
    }

    private JsonNode generateActionPlanJson(
            String callType,
            String prompt,
            AiJsonSchema schema,
            AiOrchestratorRequest request,
            AiCallConfig callConfig) {
        AiCallConfig baseConfig = buildActionPlanConfig(callConfig);
        JsonNode json = callAiJson(callType, prompt, schema, baseConfig, request, 1);
        if (json != null) {
            return json;
        }
        AiCallConfig retryConfig = buildActionPlanRetryConfig(baseConfig);
        if (retryConfig == null) {
            return null;
        }
        log.info("[AiOrchestratorService] Action plan parse failed; retrying with temperature {}.",
                retryConfig.getTemperature());
        return callAiJson(callType, prompt, schema, retryConfig, request, 2);
    }

    private JsonNode callAiJson(
            String callType,
            String prompt,
            AiJsonSchema schema,
            AiCallConfig config,
            AiOrchestratorRequest request,
            Integer attempt) {
        long start = System.nanoTime();
        try {
            JsonNode json = aiProvider.generateJson(prompt, schema, config);
            interactionLogger.logLlmInteraction(
                    request,
                    callType,
                    resolveProviderName(config),
                    attempt,
                    prompt,
                    json,
                    null,
                    elapsedMs(start),
                    null);
            return json;
        } catch (Exception ex) {
            interactionLogger.logLlmInteraction(
                    request,
                    callType,
                    resolveProviderName(config),
                    attempt,
                    prompt,
                    null,
                    null,
                    elapsedMs(start),
                    ex);
            throw ex;
        }
    }

    private String callAiText(
            String callType,
            String prompt,
            AiCallConfig config,
            AiOrchestratorRequest request,
            Integer attempt) {
        long start = System.nanoTime();
        try {
            String text = aiProvider.generateText(prompt, config);
            interactionLogger.logLlmInteraction(
                    request,
                    callType,
                    resolveProviderName(config),
                    attempt,
                    prompt,
                    null,
                    text,
                    elapsedMs(start),
                    null);
            return text;
        } catch (Exception ex) {
            interactionLogger.logLlmInteraction(
                    request,
                    callType,
                    resolveProviderName(config),
                    attempt,
                    prompt,
                    null,
                    null,
                    elapsedMs(start),
                    ex);
            throw ex;
        }
    }

    private String resolveProviderName(AiCallConfig config) {
        String provider = config != null ? trimToNull(config.getProvider()) : null;
        if (provider != null) {
            return provider.toLowerCase(Locale.ROOT);
        }
        return aiProvider.getProviderName();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private AiCallConfig buildActionPlanConfig(AiCallConfig baseConfig) {
        AiCallConfig.AiCallConfigBuilder builder = baseConfig != null
                ? baseConfig.toBuilder()
                : AiCallConfig.builder();
        boolean hasConfig = baseConfig != null;
        String provider = trimToNull(actionPlanProvider);
        if (provider != null) {
            builder.provider(provider);
            hasConfig = true;
        }
        String model = trimToNull(actionPlanModel);
        if (model != null) {
            builder.model(model);
            hasConfig = true;
        }
        if (actionPlanTemperature != null) {
            builder.temperature(actionPlanTemperature);
            hasConfig = true;
        }
        if (actionPlanMaxTokens != null && actionPlanMaxTokens > 0) {
            builder.maxTokens(actionPlanMaxTokens);
            hasConfig = true;
        }
        return hasConfig ? builder.build() : null;
    }

    private AiCallConfig buildActionPlanRetryConfig(AiCallConfig baseConfig) {
        double retryTemp = actionPlanRetryTemperature;
        if (retryTemp < 0) {
            retryTemp = 0;
        }
        Double baseTemp = baseConfig != null ? baseConfig.getTemperature() : null;
        double effectiveTemp = baseTemp != null ? Math.min(baseTemp, retryTemp) : retryTemp;
        AiCallConfig.AiCallConfigBuilder builder = baseConfig != null
                ? baseConfig.toBuilder()
                : AiCallConfig.builder();
        builder.temperature(effectiveTemp);
        return builder.build();
    }

    private AiCallConfig resolveFrontendCallConfig(String tenantId, String userId, String environment) {
        String resolvedTenant = trimToNull(tenantId);
        if (resolvedTenant == null || userConfigService == null) {
            return null;
        }
        String resolvedEnv = trimToNull(environment);
        for (String componentId : buildGlobalConfigIds(resolvedTenant)) {
            Optional<UserConfigService.ResolvedConfig> resolved =
                    userConfigService.getResolved(
                            resolvedTenant,
                            userId,
                            GLOBAL_CONFIG_COMPONENT_TYPE,
                            componentId,
                            resolvedEnv);
            if (resolved.isEmpty()) {
                continue;
            }
            JsonNode payload = parsePayload(resolved.get().config().getPayload());
            AiCallConfig config = parseAiCallConfig(payload);
            if (config != null) {
                return config;
            }
        }
        return null;
    }

    private EmbeddingService.EmbeddingCallConfig resolveEmbeddingCallConfig(
            String tenantId,
            String userId,
            String environment) {
        String resolvedTenant = trimToNull(tenantId);
        if (resolvedTenant == null || userConfigService == null) {
            return null;
        }
        String resolvedEnv = trimToNull(environment);
        for (String componentId : buildGlobalConfigIds(resolvedTenant)) {
            Optional<UserConfigService.ResolvedConfig> resolved =
                    userConfigService.getResolved(
                            resolvedTenant,
                            userId,
                            GLOBAL_CONFIG_COMPONENT_TYPE,
                            componentId,
                            resolvedEnv);
            if (resolved.isEmpty()) {
                continue;
            }
            JsonNode payload = parsePayload(resolved.get().config().getPayload());
            EmbeddingService.EmbeddingCallConfig config = parseEmbeddingCallConfig(payload);
            if (config != null) {
                return config;
            }
        }
        return null;
    }

    private List<String> buildGlobalConfigIds(String tenantId) {
        List<String> ids = new ArrayList<>();
        if (tenantId != null && !tenantId.isBlank()) {
            ids.add(GLOBAL_CONFIG_KEY + ":" + tenantId.trim());
        }
        ids.add(GLOBAL_CONFIG_KEY);
        return ids;
    }

    private JsonNode parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private AiCallConfig parseAiCallConfig(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        JsonNode aiNode = payload.get("ai");
        if (aiNode == null || !aiNode.isObject()) {
            return null;
        }
        AiCallConfig.AiCallConfigBuilder builder = AiCallConfig.builder();
        boolean hasValue = false;

        String provider = trimToNull(textOrNull(aiNode.get("provider")));
        if (provider != null) {
            builder.provider(provider);
            hasValue = true;
        }
        String model = trimToNull(textOrNull(aiNode.get("model")));
        if (model != null) {
            builder.model(model);
            hasValue = true;
        }
        Double temperature = parseDouble(aiNode.get("temperature"));
        if (temperature != null) {
            builder.temperature(temperature);
            hasValue = true;
        }
        Integer maxTokens = parseInteger(aiNode.get("maxTokens"));
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
            hasValue = true;
        }
        String apiKey = trimToNull(textOrNull(aiNode.get("apiKey")));
        if (apiKey == null) {
            String encrypted = trimToNull(textOrNull(aiNode.get("apiKeyEncrypted")));
            String decrypted = apiKeyCryptoService.decrypt(encrypted);
            apiKey = trimToNull(decrypted);
        }
        if (apiKey != null) {
            builder.apiKey(apiKey);
            hasValue = true;
        }

        return hasValue ? builder.build() : null;
    }

    private EmbeddingService.EmbeddingCallConfig parseEmbeddingCallConfig(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        JsonNode aiNode = payload.get("ai");
        if (aiNode == null || !aiNode.isObject()) {
            return null;
        }
        JsonNode embeddingNode = aiNode.get("embedding");
        boolean useSameAsLlm = embeddingNode != null
                && embeddingNode.has("useSameAsLlm")
                && embeddingNode.get("useSameAsLlm").asBoolean(false);

        String provider = embeddingNode != null ? trimToNull(textOrNull(embeddingNode.get("provider"))) : null;
        String apiKey = embeddingNode != null ? resolveApiKeyFromNode(embeddingNode) : null;
        String model = embeddingNode != null ? trimToNull(textOrNull(embeddingNode.get("model"))) : null;
        Integer dimensions = embeddingNode != null ? parseInteger(embeddingNode.get("dimensions")) : null;

        if (useSameAsLlm) {
            if (provider == null) {
                provider = trimToNull(textOrNull(aiNode.get("provider")));
            }
            if (apiKey == null) {
                apiKey = resolveApiKeyFromNode(aiNode);
            }
        }

        if (provider == null && apiKey == null && model == null && dimensions == null) {
            return null;
        }
        return new EmbeddingService.EmbeddingCallConfig(provider, apiKey, model, dimensions);
    }

    private String resolveApiKeyFromNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String apiKey = trimToNull(textOrNull(node.get("apiKey")));
        if (apiKey != null) {
            return apiKey;
        }
        String encrypted = trimToNull(textOrNull(node.get("apiKeyEncrypted")));
        if (encrypted == null) {
            return null;
        }
        String decrypted = apiKeyCryptoService.decrypt(encrypted);
        return trimToNull(decrypted);
    }

    private Double parseDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private Integer parseInteger(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong() || node.canConvertToInt()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AiJsonSchema buildTableActionPlanSchema(List<ComponentAction> actionCatalog) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ArrayNode required = schema.putArray("required");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode actions = properties.putObject("actions");
        actions.put("type", "array");
        required.add("actions");
        ObjectNode actionItem = actions.putObject("items");
        actionItem.put("type", "object");
        actionItem.put("additionalProperties", false);
        ObjectNode actionProps = actionItem.putObject("properties");
        ArrayNode actionRequired = actionItem.putArray("required");

        ObjectNode type = actionProps.putObject("type");
        type.put("type", "string");
        type.putArray("enum").addAll(buildActionEnumFromCatalog(actionCatalog));
        actionRequired.add("type");

        ObjectNode target = actionProps.putObject("target");
        target.put("type", "string");
        actionRequired.add("target");

        ObjectNode value = actionProps.putObject("value");
        value.put("type", "string");
        value.put("nullable", true);
        actionRequired.add("value");

        ObjectNode params = actionProps.putObject("params");
        params.put("type", "string");
        params.put("nullable", true);
        actionRequired.add("params");

        ObjectNode ambiguities = properties.putObject("ambiguities");
        ambiguities.put("type", "array");
        required.add("ambiguities");
        ObjectNode ambiguityItem = ambiguities.putObject("items");
        ambiguityItem.put("type", "object");
        ambiguityItem.put("additionalProperties", false);
        ObjectNode ambiguityProps = ambiguityItem.putObject("properties");
        ArrayNode ambiguityRequired = ambiguityItem.putArray("required");

        ambiguityProps.putObject("alias").put("type", "string");
        ambiguityRequired.add("alias");
        ObjectNode candidates = ambiguityProps.putObject("candidates");
        candidates.put("type", "array");
        candidates.putObject("items").put("type", "string");
        ambiguityRequired.add("candidates");
        ambiguityProps.putObject("reason").put("type", "string");
        ambiguityRequired.add("reason");

        ObjectNode contextRequest = properties.putObject("contextRequest");
        contextRequest.put("type", "array");
        contextRequest.putObject("items").put("type", "integer");
        required.add("contextRequest");
        properties.putObject("message").put("type", "string");
        required.add("message");

        return AiJsonSchema.of(schema.toString(), AiActionPlan.class);
    }

    private AiJsonSchema buildComponentActionPlanSchema(List<ComponentAction> actionCatalog) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ArrayNode required = schema.putArray("required");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode actions = properties.putObject("actions");
        actions.put("type", "array");
        required.add("actions");
        ObjectNode actionItem = actions.putObject("items");
        actionItem.put("type", "object");
        actionItem.put("additionalProperties", false);
        ObjectNode actionProps = actionItem.putObject("properties");
        ArrayNode actionRequired = actionItem.putArray("required");

        ObjectNode type = actionProps.putObject("type");
        type.put("type", "string");
        type.putArray("enum").addAll(buildActionEnumFromCatalog(actionCatalog));
        actionRequired.add("type");

        ObjectNode target = actionProps.putObject("target");
        target.put("type", "string");
        actionRequired.add("target");

        ObjectNode value = actionProps.putObject("value");
        value.put("type", "string");
        value.put("nullable", true);
        actionRequired.add("value");

        ObjectNode params = actionProps.putObject("params");
        params.put("type", "string");
        params.put("nullable", true);
        actionRequired.add("params");

        ObjectNode ambiguities = properties.putObject("ambiguities");
        ambiguities.put("type", "array");
        required.add("ambiguities");
        ObjectNode ambiguityItem = ambiguities.putObject("items");
        ambiguityItem.put("type", "object");
        ambiguityItem.put("additionalProperties", false);
        ObjectNode ambiguityProps = ambiguityItem.putObject("properties");
        ArrayNode ambiguityRequired = ambiguityItem.putArray("required");

        ambiguityProps.putObject("alias").put("type", "string");
        ambiguityRequired.add("alias");
        ObjectNode candidates = ambiguityProps.putObject("candidates");
        candidates.put("type", "array");
        candidates.putObject("items").put("type", "string");
        ambiguityRequired.add("candidates");
        ambiguityProps.putObject("reason").put("type", "string");
        ambiguityRequired.add("reason");

        ObjectNode contextRequest = properties.putObject("contextRequest");
        contextRequest.put("type", "array");
        contextRequest.putObject("items").put("type", "integer");
        required.add("contextRequest");
        properties.putObject("message").put("type", "string");
        required.add("message");

        return AiJsonSchema.of(schema.toString(), AiActionPlan.class);
    }

    private void appendActionParamsSchema(ObjectNode actionProps, Set<String> paramKeys) {
        if (paramKeys == null || paramKeys.isEmpty()) {
            return;
        }
        ObjectNode params = actionProps.putObject("params");
        params.put("type", "object");
        params.put("nullable", true);
        params.put("additionalProperties", false);
        ObjectNode properties = params.putObject("properties");
        for (String key : paramKeys) {
            if (key == null || key.isBlank()) continue;
            ObjectNode prop = properties.putObject(key);
            ArrayNode anyOf = prop.putArray("anyOf");
            anyOf.addObject().put("type", "string");
            anyOf.addObject().put("type", "number");
            anyOf.addObject().put("type", "boolean");
            ObjectNode obj = anyOf.addObject();
            obj.put("type", "object");
            obj.put("additionalProperties", true);
            ObjectNode arr = anyOf.addObject();
            arr.put("type", "array");
            arr.putObject("items");
            anyOf.addObject().put("type", "null");
        }
    }

    private Set<String> extractParamKeysFromCatalog(List<ComponentAction> actionCatalog) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (actionCatalog == null || actionCatalog.isEmpty()) {
            return keys;
        }
        for (ComponentAction action : actionCatalog) {
            if (action == null || action.patchTemplate == null || action.patchTemplate.isNull()) {
                continue;
            }
            collectParamKeys(action.patchTemplate, keys);
        }
        return keys;
    }

    private void collectParamKeys(JsonNode node, Set<String> keys) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (text == null || text.isBlank()) {
                return;
            }
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\{\\s*params\\.([^}\\s]+)\\s*}}");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String raw = matcher.group(1);
                if (raw == null || raw.isBlank()) continue;
                String key = raw.split("\\.")[0];
                if (!key.isBlank()) {
                    keys.add(key);
                }
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectParamKeys(item, keys);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectParamKeys(entry.getValue(), keys));
        }
    }


    private ArrayNode buildActionEnumFromCatalog(List<ComponentAction> actionCatalog) {
        ArrayNode enumNode = objectMapper.createArrayNode();
        if (actionCatalog == null) {
            return enumNode;
        }
        for (ComponentAction action : actionCatalog) {
            if (action == null || action.id == null || action.id.isBlank()) {
                continue;
            }
            enumNode.add(action.id.trim());
        }
        return enumNode;
    }

    private ArrayNode buildActionCatalogNode(List<ComponentAction> actionCatalog) {
        ArrayNode out = objectMapper.createArrayNode();
        if (actionCatalog == null) {
            return out;
        }
        for (ComponentAction action : actionCatalog) {
            if (action == null || action.id == null || action.id.isBlank()) {
                continue;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", action.id);
            if (action.keywords != null && !action.keywords.isEmpty()) {
                ArrayNode keywordsNode = node.putArray("keywords");
                for (String keyword : action.keywords) {
                    if (keyword != null && !keyword.isBlank()) {
                        keywordsNode.add(keyword);
                    }
                }
            }
            if (action.patchTemplate != null && !action.patchTemplate.isNull()) {
                node.set("patchTemplate", action.patchTemplate);
            }
            out.add(node);
        }
        return out;
    }

    private ArrayNode buildTargetCandidates(JsonNode componentContext, JsonNode currentState) {
        ArrayNode out = objectMapper.createArrayNode();
        if (componentContext == null || currentState == null) {
            return out;
        }
        JsonNode resolvers = componentContext.get("fieldResolvers");
        if (resolvers == null || !resolvers.isObject()) {
            return out;
        }
        resolvers.fields().forEachRemaining(entry -> {
            String path = entry.getKey();
            if (path == null || path.isBlank()) {
                return;
            }
            List<String> keys = parseResolverKeys(entry.getValue(), false);
            ArrayNode items = collectResolverItems(currentState, path, keys);
            if (items.size() == 0) {
                return;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("path", path);
            node.set("items", items);
            out.add(node);
        });
        return out;
    }

    private List<String> buildTargetOptions(ArrayNode targetCandidates) {
        if (targetCandidates == null || !targetCandidates.isArray()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (JsonNode candidateNode : targetCandidates) {
            if (candidateNode == null || !candidateNode.isObject()) continue;
            JsonNode itemsNode = candidateNode.get("items");
            if (itemsNode == null || !itemsNode.isArray()) continue;
            for (JsonNode item : itemsNode) {
                String label = extractCandidateLabel(item);
                if (label != null && !label.isBlank()) {
                    out.add(label);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private String extractCandidateLabel(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        if (item.isTextual() || item.isNumber()) {
            return item.asText();
        }
        if (!item.isObject()) {
            return null;
        }
        String[] preferred = new String[] { "field", "id", "name", "label", "value" };
        for (String key : preferred) {
            JsonNode value = item.get(key);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        var fields = item.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private ArrayNode collectResolverItems(JsonNode currentState, String path, List<String> keys) {
        ArrayNode out = objectMapper.createArrayNode();
        if (currentState == null || path == null || path.isBlank()) {
            return out;
        }
        String arrayPath = resolverArrayPath(path);
        List<JsonNode> nodes = collectNodesByPath(currentState, arrayPath);
        if (nodes.isEmpty()) {
            return out;
        }
        int limit = MAX_TARGET_CANDIDATES_PER_PATH;
        for (JsonNode node : nodes) {
            if (out.size() >= limit) {
                break;
            }
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isObject()) {
                ObjectNode item = objectMapper.createObjectNode();
                if (keys == null || keys.isEmpty()) {
                    node.fields().forEachRemaining(entry -> {
                        JsonNode value = entry.getValue();
                        if (value != null && value.isValueNode()) {
                            item.set(entry.getKey(), value);
                        }
                    });
                } else {
                    for (String key : keys) {
                        if (key == null || key.isBlank()) continue;
                        JsonNode value = node.get(key);
                        if (value != null && value.isValueNode()) {
                            item.set(key, value);
                        }
                    }
                }
                if (item.size() > 0) {
                    out.add(item);
                }
            } else if (node.isValueNode()) {
                out.add(node);
            }
        }
        return out;
    }

    private String resolverArrayPath(String path) {
        int idx = path.lastIndexOf("[]");
        if (idx < 0) {
            return path;
        }
        return path.substring(0, idx + 2);
    }

    private List<JsonNode> collectNodesByPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return List.of();
        }
        List<PathToken> tokens = parsePathTokens(path);
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<JsonNode> nodes = new ArrayList<>();
        nodes.add(root);
        for (PathToken token : tokens) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : nodes) {
                if (node == null || node.isNull()) {
                    continue;
                }
                JsonNode child = node.get(token.name);
                if (child == null || child.isNull()) {
                    continue;
                }
                if (token.isArray) {
                    if (child.isArray()) {
                        for (JsonNode item : child) {
                            if (item != null && !item.isNull()) {
                                next.add(item);
                            }
                        }
                    }
                } else {
                    next.add(child);
                }
            }
            nodes = next;
            if (nodes.isEmpty()) {
                break;
            }
        }
        return nodes;
    }

    private List<PathToken> parsePathTokens(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        String normalized = path.trim();
        String[] parts = normalized.split("\\.");
        List<PathToken> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            boolean isArray = part.endsWith("[]");
            String name = isArray ? part.substring(0, part.length() - 2) : part;
            if (name.isBlank()) {
                continue;
            }
            tokens.add(new PathToken(name, isArray));
        }
        return tokens;
    }

    private List<AiActionItem> normalizePlanActions(
            AiActionPlan actionPlan,
            List<ColumnDescriptor> columns,
            Set<String> allowedActionTypes,
            List<String> columnResolverKeys,
            Set<String> globalActionTypes) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return List.of();
        }
        Set<String> allowed = allowedActionTypes == null ? Set.of() : allowedActionTypes;
        if (allowed.isEmpty()) {
            return List.of();
        }
        List<AiActionItem> out = new ArrayList<>();
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null) continue;
            String type = action.getType() != null ? action.getType().trim().toUpperCase() : null;
            String target = action.getTarget() != null ? action.getTarget().trim() : null;
            String value = textOrNull(action.getValue());

            boolean isGlobal = type != null && globalActionTypes != null
                    && globalActionTypes.contains(normalizeActionKey(type));

            if (type == null || type.isBlank()) {
                continue;
            }
            if ((target == null || target.isBlank()) && !isGlobal) {
                continue;
            }
            if (!allowed.contains(type)) {
                continue;
            }
            String resolvedField = null;
            if (isGlobal) {
                target = null;
            } else if (target != null && !target.isBlank()) {
                resolvedField = resolveActionField(target, columns, columnResolverKeys);
            }
            out.add(AiActionItem.builder()
                    .type(type)
                    .field(resolvedField != null ? resolvedField : target)
                    .value(value)
                    .build());
        }
        return out;
    }

    private List<String> collectAmbiguityOptions(AiActionPlan actionPlan) {
        if (actionPlan == null || actionPlan.getAmbiguities() == null
                || actionPlan.getAmbiguities().isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> options = new java.util.LinkedHashSet<>();
        for (AiActionPlan.Ambiguity ambiguity : actionPlan.getAmbiguities()) {
            if (ambiguity == null) continue;
            List<String> candidates = ambiguity.getCandidates();
            if (candidates != null && !candidates.isEmpty()) {
                for (String candidate : candidates) {
                    if (candidate != null && !candidate.isBlank()) {
                        options.add(candidate);
                    }
                }
            } else if (ambiguity.getAlias() != null && !ambiguity.getAlias().isBlank()) {
                options.add(ambiguity.getAlias());
            }
        }
        return new ArrayList<>(options);
    }

    private ActionPlanClarification resolveActionPlanClarification(
            AiActionPlan actionPlan,
            List<ComponentAction> actionCatalog,
            List<String> targetOptions) {
        if (actionPlan == null) {
            return null;
        }
        List<String> ambiguityOptions = collectAmbiguityOptions(actionPlan);
        boolean hasAmbiguity = actionPlan.getAmbiguities() != null
                && !actionPlan.getAmbiguities().isEmpty();
        boolean hasActions = actionPlan.getActions() != null && !actionPlan.getActions().isEmpty();
        if (hasAmbiguity && !hasActions) {
            List<String> options = !ambiguityOptions.isEmpty()
                    ? ambiguityOptions
                    : targetOptions;
            return new ActionPlanClarification(
                    "Preciso do alvo correto para aplicar o ajuste.",
                    options);
        }
        Map<String, ComponentAction> actionById = indexActionCatalog(actionCatalog);
        if (actionPlan.getActions() != null) {
            for (AiActionPlan.Action action : actionPlan.getActions()) {
                if (action == null) continue;
                ComponentAction def = actionById.get(normalizeActionKey(action.getType()));
                RenderedActionPatch rendered = renderActionPatch(def, action);
                if (rendered == null || rendered.missingTokens.isEmpty()) {
                    continue;
                }
                String message = buildMissingActionMessage(action.getType(), rendered.missingTokens);
                List<String> options = List.of();
                System.out.println("DEBUG: Clarification check for " + action.getType());
                System.out.println("DEBUG: Missing tokens: " + rendered.missingTokens);
                if (def.params != null) {
                    System.out.println("DEBUG: Action params count: " + def.params.size());
                    def.params.forEach(p -> System.out.println("DEBUG: Param " + p.name + " opts: " + p.options));
                } else {
                    System.out.println("DEBUG: Action params is null");
                }

                if (rendered.missingTokens.contains("target")) {
                    options = targetOptions;
                } else if (!rendered.missingTokens.isEmpty()) {
                    String firstMissing = rendered.missingTokens.get(0);
                    if (firstMissing.startsWith("params.") && def.params != null) {
                        String paramName = firstMissing.substring(7);
                        for (ActionParam p : def.params) {
                            if (p.name.equalsIgnoreCase(paramName) && p.options != null) {
                                options = p.options;
                                break;
                            }
                        }
                    }
                }
                return new ActionPlanClarification(message, options);
            }
        }
        return null;
    }

    private ActionPlanCoverage applyActionPlanCoverage(
            AiActionPlan actionPlan,
            List<ComponentAction> actionCatalog,
            JsonNode patch,
            JsonNode currentState) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return new ActionPlanCoverage(null, List.of());
        }
        Map<String, ComponentAction> actionById = indexActionCatalog(actionCatalog);
        JsonNode merged = null;
        List<String> missingActions = new ArrayList<>();
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null) continue;
            ComponentAction def = actionById.get(normalizeActionKey(action.getType()));
            RenderedActionPatch rendered = renderActionPatch(def, action);
            if (rendered == null || rendered.patch == null || !rendered.missingTokens.isEmpty()) {
                continue;
            }
            if (isActionPatchSatisfied(rendered.patch, patch, currentState)) {
                continue;
            }
            merged = mergePatchNodes(merged, rendered.patch);
            if (action.getType() != null && !action.getType().isBlank()) {
                missingActions.add(action.getType());
            }
        }
        return new ActionPlanCoverage(merged, missingActions);
    }

    private ActionPlanPatchResult renderActionPlanPatch(
            AiActionPlan actionPlan,
            List<ComponentAction> actionCatalog) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return null;
        }
        Map<String, ComponentAction> actionById = indexActionCatalog(actionCatalog);
        JsonNode merged = null;
        List<String> missingActions = new ArrayList<>();
        List<String> missingTokens = new ArrayList<>();
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null || action.getType() == null || action.getType().isBlank()) {
                missingActions.add("<unknown>");
                continue;
            }
            ComponentAction def = actionById.get(normalizeActionKey(action.getType()));
            RenderedActionPatch rendered = renderActionPatch(def, action);
            if (rendered == null || rendered.patch == null) {
                missingActions.add(action.getType());
                continue;
            }
            if (!rendered.missingTokens.isEmpty()) {
                missingTokens.add(action.getType() + ":" + String.join(",", rendered.missingTokens));
                continue;
            }
            merged = mergePatchNodes(merged, rendered.patch);
        }
        return new ActionPlanPatchResult(merged, missingActions, missingTokens);
    }

    private AiActionPlan applyActionPlanDefaults(
            AiActionPlan actionPlan,
            List<ComponentAction> actionCatalog) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return actionPlan;
        }
        Map<String, ComponentAction> actionById = indexActionCatalog(actionCatalog);
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null || action.getType() == null) {
                continue;
            }
            if (action.getParams() != null && action.getParams().isTextual()) {
                JsonNode parsed = tryParseJson(action.getParams().asText());
                if (parsed != null) {
                    action.setParams(parsed);
                }
            }
            String key = normalizeActionKey(action.getType());
            ComponentAction def = actionById.get(key);
            if (def == null) {
                continue;
            }
            if (action.getValue() == null || action.getValue().isNull() || isBlankTextNode(action.getValue())) {
                JsonNode defaultValue = def.defaultValue;
                if (defaultValue != null && !defaultValue.isNull()) {
                    action.setValue(defaultValue.deepCopy());
                } else if (isBooleanAction(def)) {
                    Boolean inferred = inferBooleanDefault(key);
                    if (inferred != null) {
                        action.setValue(objectMapper.getNodeFactory().booleanNode(inferred));
                    }
                }
            }
            if ("set_renderer_badge".equals(key)) {
                ObjectNode params = ensureParamsObject(action.getParams());
                setDefaultParam(params, "color", "primary");
                setDefaultParam(params, "variant", "filled");
                action.setParams(params);
            } else if ("set_renderer_chip".equals(key)) {
                ObjectNode params = ensureParamsObject(action.getParams());
                setDefaultParam(params, "color", "primary");
                setDefaultParam(params, "variant", "filled");
                action.setParams(params);
            } else if ("set_renderer_icon".equals(key)) {
                ObjectNode params = ensureParamsObject(action.getParams());
                setDefaultParam(params, "color", "primary");
                action.setParams(params);
                if (action.getValue() == null || action.getValue().isNull()) {
                    action.setValue(objectMapper.getNodeFactory().textNode("check"));
                }
            } else if ("add_conditional_icon".equals(key)) {
                ObjectNode params = ensureParamsObject(action.getParams());
                setDefaultParam(params, "color", "warn");
                action.setParams(params);
                if (action.getValue() == null || action.getValue().isNull()) {
                    action.setValue(objectMapper.getNodeFactory().textNode("error"));
                }
            }
        }
        return actionPlan;
    }

    private JsonNode tryParseJson(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isBlankTextNode(JsonNode node) {
        return node != null && node.isTextual() && node.asText().isBlank();
    }

    private boolean isBooleanAction(ComponentAction action) {
        if (action == null || action.valueType == null) {
            return false;
        }
        return "BOOLEAN".equalsIgnoreCase(action.valueType);
    }

    private Boolean inferBooleanDefault(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) {
            return null;
        }
        String key = actionKey.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("enable_")
                || key.startsWith("show_")
                || key.startsWith("activate_")
                || key.startsWith("turn_on")
                || key.startsWith("allow_")) {
            return Boolean.TRUE;
        }
        if (key.startsWith("disable_")
                || key.startsWith("hide_")
                || key.startsWith("deactivate_")
                || key.startsWith("turn_off")
                || key.startsWith("disallow_")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private ObjectNode ensureParamsObject(JsonNode params) {
        if (params instanceof ObjectNode obj) {
            return obj;
        }
        ObjectNode out = objectMapper.createObjectNode();
        if (params != null && params.isObject()) {
            params.fields().forEachRemaining(entry -> out.set(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private void setDefaultParam(ObjectNode params, String key, String value) {
        if (params == null || key == null || value == null) {
            return;
        }
        if (!params.has(key) || params.get(key).isNull() || params.get(key).asText().isBlank()) {
            params.put(key, value);
        }
    }

    private Map<String, ComponentAction> indexActionCatalog(List<ComponentAction> actionCatalog) {
        if (actionCatalog == null || actionCatalog.isEmpty()) {
            return Map.of();
        }
        Map<String, ComponentAction> out = new LinkedHashMap<>();
        for (ComponentAction action : actionCatalog) {
            if (action == null || action.id == null || action.id.isBlank()) continue;
            out.put(normalizeActionKey(action.id), action);
        }
        return out;
    }

    private String normalizeActionKey(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private RenderedActionPatch renderActionPatch(ComponentAction actionDef, AiActionPlan.Action action) {
        if (actionDef == null || actionDef.patchTemplate == null || actionDef.patchTemplate.isNull()) {
            return null;
        }
        java.util.LinkedHashSet<String> missingTokens = new java.util.LinkedHashSet<>();
        JsonNode patch = renderTemplateNode(actionDef.patchTemplate, action, missingTokens);
        normalizeRendererTextConflicts(patch);
        return new RenderedActionPatch(
                action != null ? action.getType() : null,
                patch,
                new ArrayList<>(missingTokens));
    }

    private void normalizeRendererTextConflicts(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                normalizeRendererTextConflicts(item);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) node;
        JsonNode badgeNode = obj.get("badge");
        if (badgeNode != null && badgeNode.isObject()) {
            normalizeDynamicTextPair((ObjectNode) badgeNode, "text", "textField");
        }
        JsonNode chipNode = obj.get("chip");
        if (chipNode != null && chipNode.isObject()) {
            normalizeDynamicTextPair((ObjectNode) chipNode, "text", "textField");
        }
        JsonNode linkNode = obj.get("link");
        if (linkNode != null && linkNode.isObject()) {
            normalizeDynamicTextPair((ObjectNode) linkNode, "text", "textField");
        }
        JsonNode buttonNode = obj.get("button");
        if (buttonNode != null && buttonNode.isObject()) {
            normalizeDynamicTextPair((ObjectNode) buttonNode, "label", "labelField");
        }
        JsonNode iconNode = obj.get("icon");
        if (iconNode != null && iconNode.isObject()) {
            normalizeDynamicTextPair((ObjectNode) iconNode, "name", "nameField");
        }
        JsonNode avatarNode = obj.get("avatar");
        if (avatarNode != null && avatarNode.isObject()) {
            normalizeDynamicTextPair((ObjectNode) avatarNode, "alt", "altField");
        }
        obj.fields().forEachRemaining(entry -> normalizeRendererTextConflicts(entry.getValue()));
    }

    private void normalizeDynamicTextPair(ObjectNode node, String staticKey, String fieldKey) {
        if (node == null || staticKey == null || fieldKey == null) {
            return;
        }
        JsonNode fieldValue = node.get(fieldKey);
        JsonNode staticValue = node.get(staticKey);
        if (fieldValue != null && fieldValue.isTextual()
                && staticValue != null && staticValue.isTextual()
                && fieldValue.asText().equals(staticValue.asText())) {
            node.remove(staticKey);
        }
    }

    private JsonNode renderTemplateNode(
            JsonNode template,
            AiActionPlan.Action action,
            java.util.Set<String> missingTokens) {
        if (template == null || template.isNull()) {
            return template;
        }
        if (template.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            template.fields().forEachRemaining(entry -> {
                out.set(entry.getKey(), renderTemplateNode(entry.getValue(), action, missingTokens));
            });
            return out;
        }
        if (template.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            for (JsonNode item : template) {
                out.add(renderTemplateNode(item, action, missingTokens));
            }
            return out;
        }
        if (template.isTextual()) {
            return renderTemplateString(template.asText(), action, missingTokens);
        }
        return template;
    }

    private JsonNode renderTemplateString(
            String template,
            AiActionPlan.Action action,
            java.util.Set<String> missingTokens) {
        if (template == null || template.isBlank()) {
            return objectMapper.getNodeFactory().textNode(template);
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\{([^}]+)}}");
        java.util.regex.Matcher matcher = pattern.matcher(template);
        if (!matcher.find()) {
            return objectMapper.getNodeFactory().textNode(template);
        }
        matcher.reset();
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1).trim() : "";
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        if (tokens.size() == 1 && template.trim().equals("{{" + tokens.get(0) + "}}")) {
            JsonNode value = resolvePlaceholderValue(tokens.get(0), action);
            if (value == null || value.isNull()) {
                missingTokens.add(tokens.get(0));
                return objectMapper.getNodeFactory().nullNode();
            }
            return value;
        }
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1).trim() : "";
            JsonNode value = token.isBlank() ? null : resolvePlaceholderValue(token, action);
            if (value == null || value.isNull()) {
                if (!token.isBlank()) {
                    missingTokens.add(token);
                }
                matcher.appendReplacement(sb, "");
            } else {
                String replacement = value.isTextual() ? value.asText() : value.toString();
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(sb);
        return objectMapper.getNodeFactory().textNode(sb.toString());
    }

    private JsonNode resolvePlaceholderValue(String token, AiActionPlan.Action action) {
        if (token == null || token.isBlank() || action == null) {
            return null;
        }
        if ("target".equals(token)) {
            String target = action.getTarget();
            return target != null && !target.isBlank()
                    ? objectMapper.getNodeFactory().textNode(target)
                    : null;
        }
        if ("value".equals(token)) {
            return action.getValue();
        }
        if ("params".equals(token)) {
            return action.getParams();
        }
        if (token.startsWith("params.")) {
            return resolveParamValue(action.getParams(), token.substring("params.".length()));
        }
        JsonNode params = action.getParams();
        if (params != null && params.isObject() && params.has(token)) {
            return params.get(token);
        }
        return null;
    }

    private JsonNode resolveParamValue(JsonNode params, String path) {
        if (params == null || params.isNull() || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = params;
        String[] parts = path.split("\\.");
        for (String part : parts) {
            if (current == null || current.isNull() || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private boolean isActionPatchSatisfied(JsonNode expected, JsonNode patch, JsonNode currentState) {
        return patchContainsExpected(patch, expected)
                || patchContainsExpected(currentState, expected);
    }

    private String buildMissingActionMessage(String actionType, List<String> missingTokens) {
        String safeAction = actionType != null ? actionType : "acao";
        if (missingTokens == null || missingTokens.isEmpty()) {
            return "Preciso de mais detalhes para a acao " + safeAction + ".";
        }
        if (missingTokens.contains("target")) {
            return "Preciso do alvo da acao " + safeAction + ".";
        }
        if (missingTokens.contains("value")) {
            return "Preciso do valor da acao " + safeAction + ".";
        }
        String param = null;
        for (String token : missingTokens) {
            if (token != null && token.startsWith("params.")) {
                param = token.substring("params.".length());
                break;
            }
        }
        if (param != null && !param.isBlank()) {
            return "Preciso do valor de " + param + " para a acao " + safeAction + ".";
        }
        return "Preciso de mais detalhes para a acao " + safeAction + ".";
    }

    private AiActionPlan resolveTableActionPlanTargets(
            AiActionPlan actionPlan,
            List<ColumnDescriptor> columns,
            List<String> columnResolverKeys) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return actionPlan;
        }
        if (columns == null || columns.isEmpty()) {
            return actionPlan;
        }
        List<AiActionPlan.Action> resolvedActions = new ArrayList<>();
        boolean changed = false;
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null) continue;
            String target = action.getTarget();
            String resolvedField = resolveActionField(target, columns, columnResolverKeys);
            if (resolvedField != null && !resolvedField.equals(target)) {
                changed = true;
            }
            resolvedActions.add(AiActionPlan.Action.builder()
                    .type(action.getType())
                    .target(resolvedField != null ? resolvedField : target)
                    .value(action.getValue())
                    .params(action.getParams())
                    .build());
        }
        if (!changed) {
            return actionPlan;
        }
        return AiActionPlan.builder()
                .actions(resolvedActions)
                .ambiguities(actionPlan.getAmbiguities())
                .build();
    }

    private List<String> findUnknownActionFields(List<AiActionItem> actions, List<String> columns) {
        if (actions == null || actions.isEmpty() || columns == null || columns.isEmpty()) {
            return List.of();
        }
        List<String> unknown = new ArrayList<>();
        for (AiActionItem action : actions) {
            if (action == null || action.getField() == null) continue;
            if (!columns.contains(action.getField())) {
                unknown.add(action.getField());
            }
        }
        return unknown;
    }

    private AiActionItem findFormatActionMissingValue(List<AiActionItem> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        for (AiActionItem action : actions) {
            if (action == null) continue;
            String value = action.getValue();
            if ("SET_FORMAT".equals(action.getType())
                    && (value == null || value.isBlank() || isNullToken(value))) {
                return action;
            }
        }
        return null;
    }

    private ColumnDescriptor findColumnByField(String field, List<ColumnDescriptor> columns) {
        if (field == null || field.isBlank() || columns == null) {
            return null;
        }
        for (ColumnDescriptor column : columns) {
            if (column == null || column.field == null) continue;
            if (column.field.equals(field)) {
                return column;
            }
        }
        return null;
    }

    private List<AiActionItem> deriveFallbackTableActions(
            String userPrompt,
            List<ColumnDescriptor> columns,
            List<ComponentAction> actionCatalog,
            Set<String> allowedActionTypes,
            List<String> columnResolverKeys,
            List<ContextOption> formatOptions) {
        if (userPrompt == null || userPrompt.isBlank()
                || columns == null || columns.isEmpty()
                || actionCatalog == null || actionCatalog.isEmpty()) {
            return List.of();
        }
        String prompt = userPrompt.toLowerCase(Locale.ROOT);
        List<AiActionItem> actions = new ArrayList<>();
        List<String> clauses = splitPromptClauses(prompt);
        List<ComponentAction> lastActions = List.of();
        for (String clause : clauses) {
            if (clause == null || clause.isBlank()) {
                continue;
            }
            List<ComponentAction> clauseActions = matchActionsForClause(clause, actionCatalog);
            if (!clauseActions.isEmpty()) {
                lastActions = clauseActions;
            }
            List<ColumnDescriptor> clauseColumns = new ArrayList<>();
            for (ColumnDescriptor column : columns) {
                if (column == null || column.field == null || column.field.isBlank()) continue;
                if (clauseMentionsColumn(clause, column, columnResolverKeys)) {
                    clauseColumns.add(column);
                }
            }
            if (clauseColumns.isEmpty()) {
                continue;
            }
            List<ComponentAction> actionsToApply = !clauseActions.isEmpty() ? clauseActions : lastActions;
            if (actionsToApply == null || actionsToApply.isEmpty()) {
                continue;
            }
            for (ComponentAction actionDef : actionsToApply) {
                if (actionDef == null || actionDef.id == null || actionDef.id.isBlank()) {
                    continue;
                }
                String actionType = normalizeCatalogActionType(actionDef.id, allowedActionTypes);
                if (actionType == null) {
                    continue;
                }
                String selectedFormat = null;
                if ("SET_FORMAT".equals(actionType)) {
                    selectedFormat = detectOptionSelection(clause, formatOptions);
                    if (selectedFormat == null) {
                        selectedFormat = detectMaskFromPrompt(clause);
                    }
                }
                for (ColumnDescriptor column : clauseColumns) {
                    actions.add(AiActionItem.builder()
                            .type(actionType)
                            .field(column.field)
                            .value(selectedFormat)
                            .build());
                }
            }
        }
        return actions;
    }

    private List<String> splitPromptClauses(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        String normalized = prompt
                .replaceAll("[,;\\.]", " | ")
                .replaceAll("\\be\\b", " | ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] parts = normalized.split("\\|");
        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            String clause = part.trim();
            if (!clause.isBlank()) {
                clauses.add(clause);
            }
        }
        return clauses;
    }

    private List<ComponentAction> matchActionsForClause(
            String clause,
            List<ComponentAction> actionCatalog) {
        if (clause == null || clause.isBlank()
                || actionCatalog == null || actionCatalog.isEmpty()) {
            return List.of();
        }
        List<ComponentAction> matches = new ArrayList<>();
        String text = clause.toLowerCase(Locale.ROOT);
        for (ComponentAction action : actionCatalog) {
            if (action == null || action.id == null || action.id.isBlank()) {
                continue;
            }
            if (action.keywords == null || action.keywords.isEmpty()) {
                continue;
            }
            for (String keyword : action.keywords) {
                if (keyword != null && !keyword.isBlank()
                        && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matches.add(action);
                    break;
                }
            }
        }
        return matches;
    }

    private List<AiActionItem> mergeActions(List<AiActionItem> primary, List<AiActionItem> secondary) {
        if (secondary == null || secondary.isEmpty()) {
            return primary != null ? primary : List.of();
        }
        Map<String, AiActionItem> byKey = new LinkedHashMap<>();
        if (primary != null) {
            for (AiActionItem item : primary) {
                if (item == null) continue;
                String key = actionKey(item);
                if (key != null) {
                    byKey.put(key, item);
                }
            }
        }
        for (AiActionItem item : secondary) {
            if (item == null) continue;
            String key = actionKey(item);
            if (key != null && !byKey.containsKey(key)) {
                byKey.put(key, item);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private List<AiActionItem> applyPlanValueFallback(
            List<AiActionItem> actions,
            AiActionPlan actionPlan,
            AiIntentClassification intent,
            List<ColumnDescriptor> columns,
            Set<String> allowedActionTypes,
            List<String> columnResolverKeys) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return actions != null ? actions : List.of();
        }
        Map<String, String> valueByType = new LinkedHashMap<>();
        for (AiActionPlan.Action planAction : actionPlan.getActions()) {
            if (planAction == null) continue;
            String type = normalizePlanActionType(planAction.getType(), allowedActionTypes);
            if (type == null) continue;
            String value = textOrNull(planAction.getValue());
            if (value == null || value.isBlank()) continue;
            valueByType.putIfAbsent(type, value);
        }
        if (valueByType.isEmpty()) {
            return actions != null ? actions : List.of();
        }
        List<AiActionItem> next = actions != null ? new ArrayList<>(actions) : new ArrayList<>();
        for (int i = 0; i < next.size(); i++) {
            AiActionItem item = next.get(i);
            if (item == null) continue;
            String currentValue = item.getValue();
            if (currentValue != null && !currentValue.isBlank()) {
                continue;
            }
            String fallbackValue = valueByType.get(item.getType());
            if (fallbackValue == null || fallbackValue.isBlank()) {
                continue;
            }
            next.set(i, AiActionItem.builder()
                    .type(item.getType())
                    .field(item.getField())
                    .value(fallbackValue)
                    .build());
        }
        if (!next.isEmpty()) {
            return next;
        }
        String targetField = intent != null ? trimToNull(intent.getTargetField()) : null;
        if (targetField == null) {
            return next;
        }
        String resolvedField = resolveActionField(targetField, columns, columnResolverKeys);
        String field = resolvedField != null ? resolvedField : targetField;
        for (Map.Entry<String, String> entry : valueByType.entrySet()) {
            next.add(AiActionItem.builder()
                    .type(entry.getKey())
                    .field(field)
                    .value(entry.getValue())
                    .build());
        }
        return next;
    }

    private AiActionPlan applySingleActionTargetFallback(
            AiActionPlan actionPlan,
            AiIntentClassification intent,
            List<ColumnDescriptor> columns,
            List<String> columnResolverKeys) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return actionPlan;
        }
        String fallbackTarget = intent != null ? trimToNull(intent.getTargetField()) : null;
        if (fallbackTarget == null) {
            return actionPlan;
        }
        List<AiActionPlan.Action> actions = actionPlan.getActions();
        if (actions.size() != 1) {
            return actionPlan;
        }
        AiActionPlan.Action action = actions.get(0);
        if (action == null) {
            return actionPlan;
        }
        String target = trimToNull(action.getTarget());
        if (target != null) {
            return actionPlan;
        }
        String resolved = resolveActionField(fallbackTarget, columns, columnResolverKeys);
        String appliedTarget = resolved != null ? resolved : fallbackTarget;
        AiActionPlan.Action updated = AiActionPlan.Action.builder()
                .type(action.getType())
                .target(appliedTarget)
                .value(action.getValue())
                .params(action.getParams())
                .build();
        return AiActionPlan.builder()
                .actions(List.of(updated))
                .ambiguities(actionPlan.getAmbiguities())
                .build();
    }

    private AiActionPlan normalizeActionPlanFormatValues(
            AiActionPlan actionPlan,
            List<ContextOption> formatOptions,
            String userPrompt,
            List<String> warnings) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return actionPlan;
        }
        boolean changed = false;
        List<AiActionPlan.Action> actions = new ArrayList<>();
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null) continue;
            if (!"SET_FORMAT".equalsIgnoreCase(action.getType())) {
                actions.add(action);
                continue;
            }
            String rawValue = textOrNull(action.getValue());
            String normalized = normalizeFormatValue(rawValue, formatOptions);
            if (normalized == null) {
                normalized = inferFormatFromPrompt(userPrompt, formatOptions);
                if (normalized != null && warnings != null) {
                    warnings.add("Formato inferido a partir do pedido: '" + normalized + "'.");
                }
            }
            JsonNode nextValue = action.getValue();
            if (normalized == null) {
                if (rawValue != null && !rawValue.isBlank()) {
                    nextValue = objectMapper.getNodeFactory().nullNode();
                    changed = true;
                    if (warnings != null) {
                        warnings.add("Formato '" + rawValue + "' nao reconhecido; solicitando confirmacao.");
                    }
                }
            } else if (rawValue == null || !normalized.equals(rawValue)) {
                nextValue = objectMapper.getNodeFactory().textNode(normalized);
                changed = true;
                if (warnings != null && rawValue != null && !rawValue.isBlank()) {
                    warnings.add("Formato normalizado para '" + normalized + "'.");
                }
            }
            actions.add(AiActionPlan.Action.builder()
                    .type(action.getType())
                    .target(action.getTarget())
                    .value(nextValue)
                    .params(action.getParams())
                    .build());
        }
        if (!changed) {
            return actionPlan;
        }
        return AiActionPlan.builder()
                .actions(actions)
                .ambiguities(actionPlan.getAmbiguities())
                .build();
    }

    private String normalizeFormatValue(String rawValue, List<ContextOption> formatOptions) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (isNullToken(trimmed)) {
            return null;
        }
        String selection = detectOptionSelection(trimmed, formatOptions);
        if (selection != null && !selection.isBlank()) {
            return selection;
        }
        String exampleSelection = detectOptionExampleSelection(trimmed, formatOptions);
        if (exampleSelection != null && !exampleSelection.isBlank()) {
            return exampleSelection;
        }
        String extracted = extractFieldFromLabel(trimmed);
        if (extracted != null && !extracted.isBlank()) {
            if (looksLikeMaskFormat(extracted)) {
                return extracted.trim();
            }
            String extractedSelection = detectOptionSelection(extracted, formatOptions);
            if (extractedSelection != null && !extractedSelection.isBlank()) {
                return extractedSelection;
            }
            String extractedExample = detectOptionExampleSelection(extracted, formatOptions);
            if (extractedExample != null && !extractedExample.isBlank()) {
                return extractedExample;
            }
            if (looksLikeFormatToken(extracted)) {
                return extracted.trim();
            }
        }
        if (looksLikeMaskFormat(trimmed)) {
            return trimmed;
        }
        if (looksLikeFormatToken(trimmed)) {
            return trimmed;
        }
        return null;
    }

    private String normalizeFormatTokenFromLabel(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (looksLikeFormatToken(trimmed)) {
            return trimmed;
        }
        String extracted = extractFieldFromLabel(trimmed);
        if (extracted != null && !extracted.isBlank() && looksLikeFormatToken(extracted)) {
            return extracted.trim();
        }
        return null;
    }

    private String inferFormatFromPrompt(String prompt, List<ContextOption> formatOptions) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String lowered = prompt.toLowerCase(Locale.ROOT);
        String normalized = normalizeText(lowered);
        String mask = detectMaskFromPrompt(prompt);
        if (mask != null) {
            return mask;
        }

        String explicit = extractExplicitFormatToken(prompt);
        if (explicit != null) {
            return explicit;
        }

        String currency = extractCurrencyCode(lowered);
        if (currency != null) {
            String option = findCurrencyOption(formatOptions, currency);
            if (option != null) {
                return option;
            }
        }
        if (normalized.contains("moeda") || normalized.contains("currency") || normalized.contains("monet")) {
            String option = findFirstCurrencyOption(formatOptions);
            if (option != null) {
                return option;
            }
        }

        Integer decimals = extractDecimals(lowered);
        boolean wantsNoGroup = normalized.contains("nosep") || normalized.contains("semseparador")
                || normalized.contains("semagrupamento");
        if (normalized.contains("percent") || lowered.contains("%") || normalized.contains("porcent")) {
            String option = findPercentOption(formatOptions, decimals);
            if (option != null) {
                return option;
            }
        }

        if (normalized.contains("numero") || normalized.contains("numeric") || normalized.contains("decimal")) {
            String option = findNumberOption(formatOptions, decimals, wantsNoGroup);
            if (option != null) {
                return option;
            }
        }

        if (normalized.contains("dataehora") || (normalized.contains("data") && normalized.contains("hora"))) {
            String option = findDateTimeOption(formatOptions);
            if (option != null) {
                return option;
            }
        }
        if (normalized.contains("hora") || normalized.contains("time")) {
            String option = findTimeOption(formatOptions);
            if (option != null) {
                return option;
            }
        }
        if (normalized.contains("data") || normalized.contains("date")) {
            String option = findDateOption(formatOptions);
            if (option != null) {
                return option;
            }
        }

        String booleanOption = findBooleanOption(formatOptions, normalized);
        if (booleanOption != null) {
            return booleanOption;
        }

        String stringOption = findStringOption(formatOptions, normalized);
        if (stringOption != null) {
            return stringOption;
        }

        return null;
    }

    private String extractExplicitFormatToken(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String[] tokens = prompt.split("[\\s,;]+");
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            String cleaned = token.replaceAll("[\"'()\\[\\]]", "");
            if (looksLikeFormatToken(cleaned)) {
                return cleaned;
            }
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([A-Z]{3}\\|[^\\s]+|\\d\\.\\d-\\d(?:\\|[a-zA-Z0-9]+)*)")
                        .matcher(prompt);
        if (matcher.find()) {
            String candidate = matcher.group(1);
            if (looksLikeFormatToken(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String extractCurrencyCode(String text) {
        if (text == null) return null;
        if (text.contains("brl") || text.contains("r$")) {
            return "BRL";
        }
        if (text.contains("usd") || text.contains("us$") || text.contains("$")) {
            return "USD";
        }
        if (text.contains("eur") || text.contains("€")) {
            return "EUR";
        }
        return null;
    }

    private Integer extractDecimals(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)\\s*(casa|casas|decimal|decimais)")
                        .matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String findCurrencyOption(List<ContextOption> options, String currencyCode) {
        if (options == null || options.isEmpty() || currencyCode == null) {
            return null;
        }
        String upper = currencyCode.toUpperCase(Locale.ROOT);
        for (ContextOption option : options) {
            if (option == null) continue;
            String value = option.value;
            if (value != null && value.toUpperCase(Locale.ROOT).startsWith(upper + "|")) {
                return value;
            }
            String label = option.label;
            if (label != null && label.toLowerCase(Locale.ROOT).contains(upper.toLowerCase(Locale.ROOT))) {
                return value != null && !value.isBlank() ? value : label;
            }
        }
        return null;
    }

    private String findFirstCurrencyOption(List<ContextOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        for (ContextOption option : options) {
            if (option == null) continue;
            String value = option.value;
            if (value != null && looksLikeCurrencyFormat(value)) {
                return value;
            }
            String label = option.label;
            if (label != null && label.toLowerCase(Locale.ROOT).contains("currency")) {
                return value != null && !value.isBlank() ? value : label;
            }
        }
        return null;
    }

    private String findPercentOption(List<ContextOption> options, Integer decimals) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        String target = decimals == null ? null : "1." + decimals + "-" + decimals;
        for (ContextOption option : options) {
            if (option == null) continue;
            String label = option.label != null ? option.label.toLowerCase(Locale.ROOT) : "";
            if (!label.contains("percent")) {
                continue;
            }
            String value = option.value;
            if (target == null || (value != null && value.startsWith(target))) {
                return value != null && !value.isBlank() ? value : option.label;
            }
        }
        return null;
    }

    private String findNumberOption(List<ContextOption> options, Integer decimals, boolean noGroup) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        String target = decimals == null ? null : "1." + decimals + "-" + decimals;
        for (ContextOption option : options) {
            if (option == null) continue;
            String label = option.label != null ? option.label.toLowerCase(Locale.ROOT) : "";
            if (!label.contains("number")) {
                continue;
            }
            String value = option.value;
            if (noGroup && value != null && value.contains("nosep")) {
                return value;
            }
            if (target == null) {
                return value != null && !value.isBlank() ? value : option.label;
            }
            if (value != null && value.startsWith(target)) {
                return value;
            }
        }
        return null;
    }

    private String findDateOption(List<ContextOption> options) {
        return findOptionByLabelContains(options, "date");
    }

    private String findDateTimeOption(List<ContextOption> options) {
        return findOptionByLabelContains(options, "date+time");
    }

    private String findTimeOption(List<ContextOption> options) {
        return findOptionByLabelContains(options, "time");
    }

    private String findBooleanOption(List<ContextOption> options, String normalizedPrompt) {
        if (normalizedPrompt == null) return null;
        if (normalizedPrompt.contains("simnao") || normalizedPrompt.contains("yesno")) {
            return findOptionByValue(options, "yes-no");
        }
        if (normalizedPrompt.contains("ativoinativo")) {
            return findOptionByValue(options, "active-inactive");
        }
        if (normalizedPrompt.contains("onoff")) {
            return findOptionByValue(options, "on-off");
        }
        if (normalizedPrompt.contains("enableddisabled")) {
            return findOptionByValue(options, "enabled-disabled");
        }
        if (normalizedPrompt.contains("boolean") || normalizedPrompt.contains("verdadeiro") || normalizedPrompt.contains("truefalse")) {
            return findOptionByValue(options, "true-false");
        }
        return null;
    }

    private String findStringOption(List<ContextOption> options, String normalizedPrompt) {
        if (normalizedPrompt == null) return null;
        if (normalizedPrompt.contains("uppercase") || normalizedPrompt.contains("maiusculo")) {
            return findOptionByValue(options, "uppercase");
        }
        if (normalizedPrompt.contains("lowercase") || normalizedPrompt.contains("minusculo")) {
            return findOptionByValue(options, "lowercase");
        }
        if (normalizedPrompt.contains("titlecase")) {
            return findOptionByValue(options, "titlecase");
        }
        if (normalizedPrompt.contains("capitalize")) {
            return findOptionByValue(options, "capitalize");
        }
        if (normalizedPrompt.contains("semformatacao") || normalizedPrompt.contains("none")) {
            return findOptionByValue(options, "none");
        }
        return null;
    }

    private String findOptionByLabelContains(List<ContextOption> options, String token) {
        if (options == null || options.isEmpty() || token == null) {
            return null;
        }
        String lowerToken = token.toLowerCase(Locale.ROOT);
        for (ContextOption option : options) {
            if (option == null) continue;
            String label = option.label;
            if (label != null && label.toLowerCase(Locale.ROOT).contains(lowerToken)) {
                return option.value != null && !option.value.isBlank() ? option.value : label;
            }
        }
        return null;
    }

    private String findOptionByValue(List<ContextOption> options, String value) {
        if (options == null || options.isEmpty() || value == null) {
            return null;
        }
        for (ContextOption option : options) {
            if (option == null) continue;
            if (value.equalsIgnoreCase(option.value)) {
                return option.value;
            }
        }
        return null;
    }

    private String detectOptionExampleSelection(String text, List<ContextOption> options) {
        if (text == null || text.isBlank() || options == null || options.isEmpty()) {
            return null;
        }
        String normalized = normalizeText(text.toLowerCase(Locale.ROOT));
        for (ContextOption option : options) {
            if (option == null) continue;
            String example = option.example;
            if (example == null || example.isBlank()) {
                continue;
            }
            String normalizedExample = normalizeText(example.toLowerCase(Locale.ROOT));
            if (!normalizedExample.isBlank()
                    && (normalized.equals(normalizedExample) || normalized.contains(normalizedExample))) {
                String value = option.value != null && !option.value.isBlank() ? option.value : option.label;
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private boolean looksLikeFormatToken(String format) {
        if (format == null || format.isBlank()) {
            return false;
        }
        return looksLikeCurrencyFormat(format)
                || looksLikeDateFormat(format)
                || looksLikeNumberFormat(format)
                || looksLikeBooleanFormat(format)
                || looksLikeStringFormat(format)
                || looksLikeMaskFormat(format);
    }

    private boolean looksLikeMaskFormat(String format) {
        if (format == null || format.isBlank()) {
            return false;
        }
        String fmt = format.trim();
        if (fmt.matches("0{8,14}")) {
            return true;
        }
        return fmt.matches("0{3}\\.0{3}\\.0{3}-0{2}")
                || fmt.matches("0{2}\\.0{3}\\.0{3}/0{4}-0{2}")
                || fmt.matches("0{5}-0{3}")
                || fmt.matches("\\(0{2}\\)\\s*0{4,5}-0{4}");
    }

    private String detectMaskFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(0{3}\\.0{3}\\.0{3}-0{2}|0{2}\\.0{3}\\.0{3}/0{4}-0{2}|0{5}-0{3}|\\(0{2}\\)\\s*0{4,5}-0{4}|0{8,14})")
                .matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private boolean isNullToken(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return "null".equals(trimmed) || "undefined".equals(trimmed);
    }

    private boolean isSensitiveMaskField(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("cpf")
                || normalized.contains("cnpj")
                || normalized.contains("cep")
                || normalized.contains("telefone")
                || normalized.contains("phone")
                || normalized.contains("celular");
    }

    private List<ContextOption> buildMaskOptionsForField(String field) {
        String normalized = field != null
                ? field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")
                : "";
        List<ContextOption> out = new ArrayList<>();
        if (normalized.contains("cpf")) {
            out.add(new ContextOption("000.000.000-00", "CPF (padrão)", "123.456.789-00"));
            out.add(new ContextOption("00000000000", "CPF (apenas dígitos)", "12345678900"));
        } else if (normalized.contains("cnpj")) {
            out.add(new ContextOption("00.000.000/0000-00", "CNPJ (padrão)", "12.345.678/0001-99"));
            out.add(new ContextOption("00000000000000", "CNPJ (apenas dígitos)", "12345678000199"));
        } else if (normalized.contains("cep")) {
            out.add(new ContextOption("00000-000", "CEP (padrão)", "01001-000"));
        } else {
            out.add(new ContextOption("(00) 0000-0000", "Telefone fixo", "(11) 4000-0000"));
            out.add(new ContextOption("(00) 00000-0000", "Telefone celular", "(11) 90000-0000"));
        }
        return out;
    }

    private List<AiOption> buildMaskOptionPayloads(String field, List<ContextOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<AiOption> out = new ArrayList<>();
        for (ContextOption option : options) {
            if (option == null || option.value == null || option.value.isBlank()) continue;
            ObjectNode hints = objectMapper.createObjectNode();
            ObjectNode selection = hints.putObject("optionSelected");
            selection.put("targetField", field);
            ObjectNode selectionValue = selection.putObject("selection");
            selectionValue.put("value", option.value);
            selectionValue.put("mode", "mask");
            out.add(AiOption.builder()
                    .value(option.value)
                    .label(option.label != null && !option.label.isBlank() ? option.label : option.value)
                    .example(option.example)
                    .contextHints(hints)
                    .build());
        }
        return out;
    }

    private ClarificationPayload buildFormatClarificationPayload(
            AiIntentClassification intent,
            List<ContextOption> formatOptions) {
        if (intent == null) {
            return new ClarificationPayload(List.of(), List.of());
        }
        String targetField = intent.getTargetField();
        List<ContextOption> choices = formatOptions != null ? formatOptions : List.of();
        if (isSensitiveMaskField(targetField)) {
            choices = buildMaskOptionsForField(targetField);
            List<String> labels = buildOptionLabels(choices);
            List<AiOption> payloads = buildMaskOptionPayloads(targetField, choices);
            return new ClarificationPayload(labels, payloads);
        }
        List<String> labels = intent.getOptions() != null
                ? intent.getOptions()
                : buildOptionLabels(choices);
        List<AiOption> payloads = buildOptionPayloads(choices);
        return new ClarificationPayload(labels, payloads);
    }

    private SelectedFormatSelection extractSelectedFormatFromHints(JsonNode contextHints) {
        if (contextHints == null || contextHints.isNull()) {
            return null;
        }
        JsonNode selected = contextHints.get("optionSelected");
        if (selected == null || selected.isNull() || !selected.isObject()) {
            return null;
        }
        String targetField = textOrNull(selected.get("targetField"));
        JsonNode selection = selected.get("selection");
        if (selection == null || selection.isNull()) {
            return null;
        }
        String value = textOrNull(selection.get("value"));
        if (value == null || value.isBlank()) {
            return null;
        }
        String mode = textOrNull(selection.get("mode"));
        return new SelectedFormatSelection(targetField, value, mode);
    }

    private List<ContextOption> augmentFormatOptionsForPrompt(
            List<ContextOption> formatOptions,
            String userPrompt,
            String targetField) {
        List<ContextOption> base = formatOptions != null ? new ArrayList<>(formatOptions) : new ArrayList<>();
        String mask = detectMaskFromPrompt(userPrompt);
        if (mask != null) {
            base.add(0, new ContextOption(mask, "Máscara (selecionada)", null));
            return base;
        }
        if (isSensitiveMaskField(targetField) || (userPrompt != null && containsSensitiveFieldToken(userPrompt))) {
            base.addAll(0, buildMaskOptionsForField(targetField != null ? targetField : "cpf"));
        }
        return base;
    }

    private boolean containsSensitiveFieldToken(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("cpf")
                || normalized.contains("cnpj")
                || normalized.contains("cep")
                || normalized.contains("telefone")
                || normalized.contains("phone")
                || normalized.contains("celular");
    }

    private List<AiActionItem> applySelectedFormatOverride(
            List<AiActionItem> actions,
            SelectedFormatSelection selection,
            AiIntentClassification intent,
            List<ColumnDescriptor> columns,
            List<String> columnResolverKeys,
            List<String> warnings) {
        if (selection == null || selection.value == null || selection.value.isBlank()) {
            return actions;
        }
        String targetField = selection.targetField != null ? selection.targetField : (intent != null ? intent.getTargetField() : null);
        String resolvedField = targetField != null
                ? resolveActionField(targetField, columns, columnResolverKeys)
                : null;
        String field = resolvedField != null ? resolvedField : targetField;
        if (field == null || field.isBlank()) {
            return actions;
        }
        List<AiActionItem> next = actions != null ? new ArrayList<>(actions) : new ArrayList<>();
        boolean applied = false;
        for (int i = 0; i < next.size(); i++) {
            AiActionItem item = next.get(i);
            if (item == null) continue;
            if (!"SET_FORMAT".equals(item.getType())) {
                continue;
            }
            if (item.getField() != null && !item.getField().equals(field)) {
                continue;
            }
            next.set(i, AiActionItem.builder()
                    .type(item.getType())
                    .field(field)
                    .value(selection.value)
                    .build());
            applied = true;
        }
        if (!applied) {
            next.add(AiActionItem.builder()
                    .type("SET_FORMAT")
                    .field(field)
                    .value(selection.value)
                    .build());
        }
        if (warnings != null) {
            warnings.add("Formato selecionado aplicado: '" + selection.value + "'.");
        }
        return next;
    }

    private boolean looksLikeNumberFormat(String format) {
        if (format == null) return false;
        String fmt = format.trim().toLowerCase(Locale.ROOT);
        return fmt.matches("\\d+\\.\\d+-\\d+(\\|[a-z0-9]+)*");
    }

    private boolean looksLikeBooleanFormat(String format) {
        if (format == null) return false;
        String fmt = format.trim().toLowerCase(Locale.ROOT);
        if (fmt.matches("true-false|yes-no|active-inactive|on-off|enabled-disabled")) {
            return true;
        }
        return fmt.startsWith("custom|") && fmt.split("\\|").length >= 3;
    }

    private boolean looksLikeStringFormat(String format) {
        if (format == null) return false;
        String fmt = format.trim().toLowerCase(Locale.ROOT);
        if (fmt.matches("none|uppercase|lowercase|titlecase|capitalize")) {
            return true;
        }
        return fmt.startsWith("truncate|");
    }

    private String normalizePlanActionType(String type, Set<String> allowedActionTypes) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (allowedActionTypes == null || allowedActionTypes.isEmpty()) {
            return normalized;
        }
        return allowedActionTypes.contains(normalized) ? normalized : null;
    }

    private String actionKey(AiActionItem item) {
        if (item == null) return null;
        String type = item.getType();
        String field = item.getField();
        if (type == null || field == null) return null;
        return type + "::" + field;
    }

    private boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) return false;
        String pattern = "\\b" + java.util.regex.Pattern.quote(word) + "\\b";
        return java.util.regex.Pattern.compile(pattern).matcher(text).find();
    }

    private boolean canBypassClarification(AiIntentClassification intent, List<ComponentAction> actionCatalog) {
        if (intent == null || actionCatalog == null || actionCatalog.isEmpty()) {
            return false;
        }
        if (!"toggle_feature".equalsIgnoreCase(intent.getIntent())) {
            return false;
        }
        String category = intent.getCategory() != null ? intent.getCategory().toLowerCase(Locale.ROOT) : "";
        if (!Set.of("selection", "pagination", "sorting", "filtering", "export", "toolbar", "appearance", "behavior")
                .contains(category)) {
            return false;
        }
        Set<String> ids = new java.util.HashSet<>();
        for (ComponentAction action : actionCatalog) {
            if (action == null || action.id == null || action.id.isBlank()) continue;
            ids.add(action.id.trim().toUpperCase(Locale.ROOT));
        }
        return ids.contains("ENABLE_SELECTION")
                || ids.contains("DISABLE_PAGINATION")
                || ids.contains("ENABLE_PAGINATION")
                || ids.contains("ENABLE_FILTERING")
                || ids.contains("DISABLE_FILTERING")
                || ids.contains("ENABLE_SORTING")
                || ids.contains("DISABLE_SORTING")
                || ids.contains("ENABLE_EXPORT")
                || ids.contains("DISABLE_EXPORT")
                || ids.contains("SHOW_TOOLBAR")
                || ids.contains("HIDE_TOOLBAR");
    }

    private boolean shouldIgnoreIntentPlanQuestions(
            AiIntentClassification intent,
            IntentPlan plan,
            List<ComponentAction> actionCatalog) {
        if (plan == null || plan.getQuestions() == null || plan.getQuestions().isEmpty()) {
            return false;
        }
        if (intent == null || !"toggle_feature".equalsIgnoreCase(intent.getIntent())) {
            return false;
        }
        if (!canBypassClarification(intent, actionCatalog)) {
            return false;
        }
        return plan.getActions() != null && !plan.getActions().isEmpty();
    }

    private String detectOptionSelection(String text, List<ContextOption> options) {
        if (text == null || text.isBlank() || options == null || options.isEmpty()) {
            return null;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        String normalized = normalizeText(lowered);
        for (ContextOption option : options) {
            if (option == null) continue;
            String value = option.value;
            if (value != null && !value.isBlank()) {
                String valueLower = value.toLowerCase(Locale.ROOT);
                if (lowered.contains(valueLower)) {
                    return value;
                }
            }
            String label = option.label;
            if (label != null && !label.isBlank()) {
                String labelLower = label.toLowerCase(Locale.ROOT);
                if (lowered.contains(labelLower)) {
                    return value != null && !value.isBlank() ? value : label;
                }
                String normalizedLabel = normalizeText(labelLower);
                if (!normalizedLabel.isBlank() && normalized.contains(normalizedLabel)) {
                    return value != null && !value.isBlank() ? value : label;
                }
            }
        }
        return null;
    }

    private boolean clauseMentionsColumn(
            String clause,
            ColumnDescriptor column,
            List<String> resolverKeys) {
        if (clause == null || column == null || column.field == null) {
            return false;
        }
        String clauseText = clause.toLowerCase(Locale.ROOT);
        String fieldLower = column.field.toLowerCase(Locale.ROOT);
        List<String> keys = resolverKeys == null || resolverKeys.isEmpty()
                ? List.of("field", "header")
                : resolverKeys;
        if (keys.contains("field") && containsWord(clauseText, fieldLower)) {
            return true;
        }
        if (keys.contains("header") && column.header != null && !column.header.isBlank()) {
            String normalizedClause = normalizeText(clauseText);
            String normalizedHeader = normalizeText(column.header);
            if (!normalizedHeader.isBlank() && normalizedClause.contains(normalizedHeader)) {
                return true;
            }
        }
        return false;
    }

    private String resolveActionField(
            String rawField,
            List<ColumnDescriptor> columns,
            List<String> resolverKeys) {
        if (rawField == null || rawField.isBlank()
                || columns == null || columns.isEmpty()) {
            return null;
        }
        List<String> keys = resolverKeys == null || resolverKeys.isEmpty()
                ? List.of("field", "header")
                : resolverKeys;
        String trimmed = rawField.trim();
        String normalized = normalizeText(trimmed);
        String fromLabel = extractFieldFromLabel(trimmed);
        if (fromLabel != null) {
            String direct = resolveFieldByExactMatch(fromLabel, columns);
            if (direct != null) {
                return direct;
            }
        }
        String direct = keys.contains("field")
                ? resolveFieldByExactMatch(trimmed, columns)
                : null;
        if (direct != null) {
            return direct;
        }
        String byHeader = keys.contains("header")
                ? resolveFieldByHeader(trimmed, columns)
                : null;
        if (byHeader != null) {
            return byHeader;
        }
        ColumnDescriptor best = findBestColumnMatch(normalized, columns, keys);
        return best != null ? best.field : null;
    }

    private String resolveFieldByExactMatch(String value, List<ColumnDescriptor> columns) {
        if (value == null || value.isBlank() || columns == null) return null;
        for (ColumnDescriptor column : columns) {
            if (column == null || column.field == null) continue;
            if (column.field.equalsIgnoreCase(value.trim())) {
                return column.field;
            }
        }
        return null;
    }

    private String resolveFieldByHeader(String value, List<ColumnDescriptor> columns) {
        if (value == null || value.isBlank() || columns == null) return null;
        String normalized = normalizeText(value);
        ColumnDescriptor match = null;
        for (ColumnDescriptor column : columns) {
            if (column == null || column.header == null || column.header.isBlank()) continue;
            if (column.header.equalsIgnoreCase(value.trim())) {
                if (match != null) {
                    return null;
                }
                match = column;
            } else if (!normalized.isBlank()
                    && normalizeText(column.header).equals(normalized)) {
                if (match != null) {
                    return null;
                }
                match = column;
            }
        }
        return match != null ? match.field : null;
    }

    private ColumnDescriptor findBestColumnMatch(
            String normalizedValue,
            List<ColumnDescriptor> columns,
            List<String> resolverKeys) {
        if (normalizedValue == null || normalizedValue.isBlank() || columns == null) {
            return null;
        }
        ColumnDescriptor best = null;
        double bestScore = 0d;
        double secondScore = 0d;
        List<String> keys = resolverKeys == null || resolverKeys.isEmpty()
                ? List.of("field", "header")
                : resolverKeys;
        for (ColumnDescriptor column : columns) {
            if (column == null || column.field == null) continue;
            double score = 0d;
            if (keys.contains("field")) {
                score = Math.max(score, similarityScore(normalizedValue, column.field));
            }
            if (keys.contains("header")) {
                score = Math.max(score, similarityScore(normalizedValue, column.header));
            }
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = column;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        if (bestScore >= 0.85d && (bestScore - secondScore) >= 0.1d) {
            return best;
        }
        return null;
    }

    private String extractFieldFromLabel(String label) {
        if (label == null) return null;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(label);
        if (matcher.find()) {
            String value = matcher.group(1);
            return value != null ? value.trim() : null;
        }
        return null;
    }

    private String normalizeText(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private JsonNode mergePatchNodes(JsonNode base, JsonNode extra) {
        if (extra == null || extra.isNull()) {
            return base;
        }
        if (base == null || base.isNull()) {
            return extra;
        }
        if (base.isObject() && extra.isObject()) {
            ObjectNode merged = base.deepCopy();
            extra.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                JsonNode existing = merged.get(key);
                merged.set(key, mergePatchNodes(existing, value));
            });
            return merged;
        }
        if (base.isArray() && extra.isArray()) {
            return mergeArrayNodes((ArrayNode) base, (ArrayNode) extra);
        }
        return extra;
    }

    private List<AiPatchDiff> buildPatchDiff(JsonNode currentState, JsonNode patch) {
        if (patch == null || patch.isNull()) {
            return null;
        }
        JsonNode before = currentState != null ? currentState : objectMapper.createObjectNode();
        JsonNode after = mergePatchNodes(before, patch);
        List<AiPatchDiff> diffs = new ArrayList<>();
        collectDiffs(before, after, "", diffs);
        return diffs.isEmpty() ? null : diffs;
    }

    private void collectDiffs(JsonNode before, JsonNode after, String path, List<AiPatchDiff> diffs) {
        JsonNode left = before != null ? before : MissingNode.getInstance();
        JsonNode right = after != null ? after : MissingNode.getInstance();
        if (nodesEquivalent(left, right)) {
            return;
        }
        if (left.isObject() && right.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            left.fieldNames().forEachRemaining(keys::add);
            right.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                collectDiffs(left.get(key), right.get(key), joinPath(path, key), diffs);
            }
            return;
        }
        if (left.isArray() && right.isArray()) {
            collectArrayDiffs((ArrayNode) left, (ArrayNode) right, path, diffs);
            return;
        }
        diffs.add(AiPatchDiff.builder()
                .path(formatRootPath(path))
                .before(normalizeDiffNode(left))
                .after(normalizeDiffNode(right))
                .build());
    }

    private void collectArrayDiffs(ArrayNode before, ArrayNode after, String path, List<AiPatchDiff> diffs) {
        String identityKey = detectIdentityKey(before);
        if (identityKey == null) {
            identityKey = detectIdentityKey(after);
        }
        if (identityKey == null) {
            if (!before.equals(after)) {
                diffs.add(AiPatchDiff.builder()
                        .path(formatRootPath(path))
                        .before(normalizeDiffNode(before))
                        .after(normalizeDiffNode(after))
                        .build());
            }
            return;
        }
        if (arrayHasNonIdentityItems(before, identityKey) || arrayHasNonIdentityItems(after, identityKey)) {
            if (!before.equals(after)) {
                diffs.add(AiPatchDiff.builder()
                        .path(formatRootPath(path))
                        .before(normalizeDiffNode(before))
                        .after(normalizeDiffNode(after))
                        .build());
            }
            return;
        }
        Map<String, JsonNode> beforeById = indexArrayById(before, identityKey);
        Map<String, JsonNode> afterById = indexArrayById(after, identityKey);
        List<String> orderedIds = new ArrayList<>(beforeById.keySet());
        for (String id : afterById.keySet()) {
            if (!beforeById.containsKey(id)) {
                orderedIds.add(id);
            }
        }
        for (String id : orderedIds) {
            collectDiffs(
                    beforeById.get(id),
                    afterById.get(id),
                    joinArrayPath(path, identityKey, id),
                    diffs);
        }
    }

    private Map<String, JsonNode> indexArrayById(ArrayNode array, String identityKey) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode item : array) {
            if (item == null || item.isNull() || !item.isObject()) {
                continue;
            }
            String id = textOrNull(item.get(identityKey));
            if (id != null) {
                byId.put(id, item);
            }
        }
        return byId;
    }

    private boolean arrayHasNonIdentityItems(ArrayNode array, String identityKey) {
        for (JsonNode item : array) {
            if (item == null || item.isNull() || !item.isObject()) {
                return true;
            }
            String id = textOrNull(item.get(identityKey));
            if (id == null) {
                return true;
            }
        }
        return false;
    }

    private String joinPath(String base, String key) {
        if (base == null || base.isBlank()) {
            return key;
        }
        return base + "." + key;
    }

    private String joinArrayPath(String base, String identityKey, String id) {
        String suffix = "[" + identityKey + "=" + id + "]";
        if (base == null || base.isBlank()) {
            return suffix;
        }
        return base + suffix;
    }

    private String formatRootPath(String path) {
        return (path == null || path.isBlank()) ? "$" : path;
    }

    private JsonNode normalizeDiffNode(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return objectMapper.getNodeFactory().nullNode();
        }
        return node;
    }

    private boolean nodesEquivalent(JsonNode left, JsonNode right) {
        if (left == null || left.isMissingNode()) {
            return right == null || right.isMissingNode();
        }
        if (right == null || right.isMissingNode()) {
            return false;
        }
        if (left.isNull() && right.isNull()) {
            return true;
        }
        return left.equals(right);
    }

    private ArrayNode mergeArrayNodes(ArrayNode base, ArrayNode extra) {
        ArrayNode merged = objectMapper.createArrayNode();
        String identityKey = detectIdentityKey(base);
        if (identityKey == null) {
            identityKey = detectIdentityKey(extra);
        }
        if (identityKey == null) {
            merged.addAll(base);
            merged.addAll(extra);
            return merged;
        }
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (JsonNode item : base) {
            if (item == null || item.isNull()) continue;
            if (item.isObject()) {
                String id = textOrNull(item.get(identityKey));
                if (id != null) {
                    byId.put(id, ((ObjectNode) item).deepCopy());
                    continue;
                }
            }
            merged.add(item);
        }
        for (JsonNode item : extra) {
            if (item == null || item.isNull()) continue;
            if (item.isObject()) {
                String id = textOrNull(item.get(identityKey));
                if (id != null) {
                    ObjectNode existing = byId.get(id);
                    if (existing != null) {
                        JsonNode mergedNode = mergePatchNodes(existing, item);
                        if (mergedNode != null && mergedNode.isObject()) {
                            byId.put(id, (ObjectNode) mergedNode);
                        }
                    } else {
                        byId.put(id, ((ObjectNode) item).deepCopy());
                    }
                    continue;
                }
            }
            merged.add(item);
        }
        for (ObjectNode node : byId.values()) {
            merged.add(node);
        }
        return merged;
    }

    private String detectIdentityKey(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String key = detectIdentityKey(item);
                if (key != null) {
                    return key;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        String[] candidates = new String[] { "field", "id", "name", "key" };
        for (String candidate : candidates) {
            JsonNode value = node.get(candidate);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean patchContainsExpected(JsonNode actual, JsonNode expected) {
        if (expected == null || expected.isNull()) {
            return true;
        }
        if (actual == null || actual.isNull()) {
            return false;
        }
        if (expected.isObject()) {
            if (!actual.isObject()) {
                return false;
            }
            var fields = expected.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode actualValue = actual.get(entry.getKey());
                if (!patchContainsExpected(actualValue, entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            if (!actual.isArray()) {
                return false;
            }
            for (JsonNode expectedItem : expected) {
                if (!arrayContainsExpected((ArrayNode) actual, expectedItem)) {
                    return false;
                }
            }
            return true;
        }
        return actual.equals(expected);
    }

    private boolean arrayContainsExpected(ArrayNode actual, JsonNode expectedItem) {
        if (expectedItem == null || expectedItem.isNull()) {
            return true;
        }
        String identityKey = detectIdentityKey(expectedItem);
        if (identityKey != null && expectedItem.isObject()) {
            String expectedId = textOrNull(expectedItem.get(identityKey));
            if (expectedId != null) {
                for (JsonNode actualItem : actual) {
                    if (actualItem == null || !actualItem.isObject()) continue;
                    String actualId = textOrNull(actualItem.get(identityKey));
                    if (expectedId.equals(actualId)) {
                        return patchContainsExpected(actualItem, expectedItem);
                    }
                }
                return false;
            }
        }
        for (JsonNode actualItem : actual) {
            if (patchContainsExpected(actualItem, expectedItem)) {
                return true;
            }
        }
        return false;
    }

    private String formatActionNotes(List<AiActionItem> actions) {
        if (actions == null || actions.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("Acoes esperadas (nao omitir):");
        for (AiActionItem action : actions) {
            if (action == null) continue;
            String hint = actionHint(action);
            joiner.add("- " + hint);
        }
        return joiner.toString();
    }

    private String formatActionPlanNotes(AiActionPlan actionPlan) {
        if (actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("Acoes esperadas (nao omitir):");
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null) continue;
            joiner.add("- " + planActionHint(action));
        }
        return joiner.toString();
    }

    private String planActionHint(AiActionPlan.Action action) {
        if (action == null) {
            return "";
        }
        String type = action.getType() != null ? action.getType() : "?";
        String target = action.getTarget();
        String value = textOrNull(action.getValue());
        StringBuilder sb = new StringBuilder(type);
        if (target != null && !target.isBlank()) {
            sb.append(" target=").append(target);
        }
        if (value != null && !value.isBlank()) {
            sb.append(" value=\"").append(value).append("\"");
        }
        return sb.toString();
    }
    private String appendExplanation(String base, String extra) {
        if (extra == null || extra.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return extra.trim();
        }
        String trimmedBase = base.trim();
        String trimmedExtra = extra.trim();
        String separator = trimmedBase.endsWith(".")
                || trimmedBase.endsWith("!")
                || trimmedBase.endsWith("?") ? " " : ". ";
        return trimmedBase + separator + trimmedExtra;
    }

    private String actionHint(AiActionItem action) {
        if (action == null) {
            return "";
        }
        String type = action.getType() != null ? action.getType() : "?";
        String field = action.getField() != null ? action.getField() : "?";
        String value = action.getValue();
        if (value != null && !value.isBlank()) {
            return type + " " + field + " value=\"" + safe(value) + "\"";
        }
        return type + " " + field;
    }

    private List<String> suggestClosestColumns(
            List<String> unknownFields,
            List<ColumnDescriptor> columns) {
        if (unknownFields == null || unknownFields.isEmpty() || columns == null || columns.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> headerCounts = countHeaders(columns);
        Map<String, Double> suggestions = new LinkedHashMap<>();
        for (String unknown : unknownFields) {
            if (unknown == null || unknown.isBlank()) continue;
            List<ScoredColumn> scored = new ArrayList<>();
            for (ColumnDescriptor column : columns) {
                if (column == null || column.field == null || column.field.isBlank()) continue;
                double score = Math.max(
                        similarityScore(unknown, column.field),
                        similarityScore(unknown, column.header));
                if (score > 0) {
                    scored.add(new ScoredColumn(displayColumnLabel(column, headerCounts), score));
                }
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));
            int limit = Math.min(3, scored.size());
            for (int i = 0; i < limit; i++) {
                ScoredColumn candidate = scored.get(i);
                if (candidate.score < 0.5d) {
                    continue;
                }
                suggestions.putIfAbsent(candidate.name, candidate.score);
            }
        }
        return new ArrayList<>(suggestions.keySet());
    }

    private String buildUnknownColumnsMessage(List<String> unknownFields, List<String> suggestions) {
        if (unknownFields == null || unknownFields.isEmpty()) {
            return "Nao encontrei a coluna solicitada.";
        }
        String base = "Nao encontrei as colunas: " + unknownFields + ".";
        if (suggestions == null || suggestions.isEmpty()) {
            return base + " Informe o nome correto da coluna.";
        }
        return base + " Selecione a coluna correta abaixo.";
    }

    private double similarityScore(String a, String b) {
        String na = normalizeToken(a);
        String nb = normalizeToken(b);
        if (na.isBlank() || nb.isBlank()) {
            return 0d;
        }
        if (na.equals(nb)) {
            return 1d;
        }
        if (na.contains(nb) || nb.contains(na)) {
            return 0.9d;
        }
        int max = Math.max(na.length(), nb.length());
        if (max == 0) {
            return 0d;
        }
        int dist = levenshteinDistance(na, nb);
        return 1d - ((double) dist / (double) max);
    }

    private String normalizeToken(String value) {
        return normalizeText(value);
    }

    private int levenshteinDistance(String a, String b) {
        int aLen = a.length();
        int bLen = b.length();
        int[] prev = new int[bLen + 1];
        int[] curr = new int[bLen + 1];
        for (int j = 0; j <= bLen; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= aLen; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= bLen; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[bLen];
    }

    private String joinNotes(String notesA, String notesB) {
        if (notesA == null || notesA.isBlank()) return notesB != null ? notesB : "";
        if (notesB == null || notesB.isBlank()) return notesA;
        return notesA + "\n" + notesB;
    }

    private AiIntentClassification normalizeIntent(
            AiIntentClassification intent,
            List<String> columnNames,
            List<String> configCategories,
            List<String> componentCategories,
            List<String> warnings) {
        if (intent == null) return null;
        String category = normalizeCategory(intent.getCategory());
        intent.setCategory(category);
        String scope = normalizeScope(intent.getScope());

        boolean inConfig = category != null && configCategories.contains(category);
        boolean inComponent = category != null && componentCategories.contains(category);
        if ("component".equals(scope) && inConfig && !inComponent) {
            scope = "config";
            if (warnings != null) {
                warnings.add("Scope ajustado para config com base na categoria detectada.");
            }
        } else if ("config".equals(scope) && inComponent && !inConfig) {
            scope = "component";
            if (warnings != null) {
                warnings.add("Scope ajustado para component com base na categoria detectada.");
            }
        }
        intent.setScope(scope);

        List<String> missing = new ArrayList<>();
        if (intent.getMissingContext() != null) {
            missing.addAll(intent.getMissingContext());
        }

        List<String> allowedCategories = allowedCategoriesForScope(
                scope, configCategories, componentCategories);
        boolean categoryKnown = category != null && !category.isBlank() && !"unknown".equalsIgnoreCase(category);
        if (!allowedCategories.isEmpty()) {
            if (!categoryKnown || !allowedCategories.contains(category)) {
                addMissing(missing, "category");
                intent.setNeedsClarification(true);
                if (intent.getOptions() == null || intent.getOptions().isEmpty()) {
                    intent.setOptions(allowedCategories);
                }
            }
        } else if (!categoryKnown) {
            addMissing(missing, "category");
            intent.setNeedsClarification(true);
        }

        String targetField = intent.getTargetField();
        if (targetField != null && !targetField.isBlank()
                && columnNames != null && !columnNames.isEmpty()
                && !columnNames.contains(targetField)) {
            addMissing(missing, "column");
            intent.setNeedsClarification(true);
            if (intent.getOptions() == null || intent.getOptions().isEmpty()) {
                intent.setOptions(columnNames);
            }
        }

        intent.setMissingContext(missing.isEmpty() ? null : missing);
        return intent;
    }

    private void addMissing(List<String> missing, String value) {
        if (missing == null || value == null || value.isBlank()) return;
        if (!missing.contains(value)) {
            missing.add(value);
        }
    }

    private boolean shouldIgnoreCategoryClarification(
            AiIntentClassification intent,
            List<ComponentAction> componentActions) {
        if (intent == null || componentActions == null || componentActions.isEmpty()) {
            return false;
        }
        List<String> missing = intent.getMissingContext();
        if (missing == null || missing.isEmpty()) {
            return false;
        }
        if (missing.size() != 1 || !"category".equalsIgnoreCase(missing.get(0))) {
            return false;
        }
        String category = intent.getCategory();
        boolean unknown = category == null || category.isBlank() || "unknown".equalsIgnoreCase(category);
        String intentType = intent.getIntent();
        return unknown && "update_column_rules".equalsIgnoreCase(intentType);
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;
        return category.trim().toLowerCase();
    }

    private List<String> allowedCategoriesForScope(
            String scope,
            List<String> configCategories,
            List<String> componentCategories) {
        if ("component".equals(scope)) {
            return componentCategories != null ? componentCategories : List.of();
        }
        if ("mixed".equals(scope)) {
            List<String> merged = new ArrayList<>();
            if (configCategories != null) {
                configCategories.forEach((cat) -> {
                    if (!merged.contains(cat)) merged.add(cat);
                });
            }
            if (componentCategories != null) {
                componentCategories.forEach((cat) -> {
                    if (!merged.contains(cat)) merged.add(cat);
                });
            }
            return merged;
        }
        return configCategories != null ? configCategories : List.of();
    }

    private String buildClarificationMessage(AiIntentClassification intent, AiOrchestratorRequest request) {
        if (intent == null) {
            return "Preciso de mais detalhes para continuar.";
        }
        List<String> missing = intent.getMissingContext();
        if (missing != null && !missing.isEmpty()) {
            if (hasMissingResourceContext(missing)) {
                return buildResourceClarificationMessage(
                        request != null ? request.getComponentId() : null);
            }
            return "Preciso de mais contexto para continuar: " + String.join(", ", missing) + ".";
        }
        return "Preciso de mais detalhes para continuar.";
    }

    private AiOrchestratorResponse tryHandleCreateFlow(
            AiOrchestratorRequest request,
            AiContextDTO context,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        if (request == null || !isPageBuilder(request.getComponentId())) {
            return null;
        }
        JsonNode currentState = context.getCurrentState();
        boolean isCreatePrompt = promptMentionsTable(request.getUserPrompt());
        JsonNode createFlowNode = extractCreateFlow(request.getContextHints());
        if (createFlowNode == null && !isCreatePrompt) {
            return null;
        }
        CreateFlowState state = parseCreateFlow(createFlowNode);
        if (state.invalid) {
            return clarification("Escolha uma opção válida para continuar.", List.of());
        }
        String step = state.step;
        if (step == null) {
            step = isCreatePrompt ? "endpoint" : "widget";
        }

        if ("widget".equals(step)) {
            List<AiOption> widgets = resolveWidgetOptions(request, context);
            if (widgets.isEmpty()) {
                return clarification("Nenhum widget disponível para seleção.", List.of());
            }
            List<AiOption> payloads = widgets.stream()
                    .map(opt -> AiOption.builder()
                            .value(opt.getValue())
                            .label(opt.getLabel())
                            .example(opt.getExample())
                            .contextHints(buildCreateFlowHints("endpoint", opt.getValue(), null))
                            .build())
                    .collect(Collectors.toList());
            return clarification("Qual componente deseja adicionar?",
                    buildAiOptionLabels(payloads),
                    payloads);
        }

        if ("endpoint".equals(step)) {
            String widgetId = state.widgetId;
            if (isCreatePrompt && isBlank(widgetId)) {
                widgetId = "praxis-table";
            }
            if (isBlank(widgetId) || !isValidWidgetId(widgetId, request, context)) {
                return buildWidgetStepResponse(request, context);
            }
            List<ApiSearchResult> results = searchEndpoints(request, embeddingConfig);
            if (results.isEmpty()) {
                return clarification("nenhum endpoint encontrado", List.of());
            }
            if (isCreatePrompt) {
                results = filterTableEndpoints(results);
                if (results.isEmpty()) {
                    return clarification("nenhum endpoint de listagem (POST filter) encontrado", List.of());
                }
            }
            if (!isBlank(state.resourcePath)
                    && isValidResourcePath(state.resourcePath, results)) {
                JsonNode patch = buildPageWidgetPatch(widgetId, state.resourcePath);
                return AiOrchestratorResponse.builder()
                        .type("patch")
                        .patch(patch)
                        .diff(buildPatchDiff(currentState, patch))
                        .build();
            }

            final String finalWidgetId = widgetId;
            List<AiOption> payloads = buildApiOptionPayloads(results).stream()
                    .map(opt -> AiOption.builder()
                            .value(opt.getValue())
                            .label(opt.getLabel())
                            .example(opt.getExample())
                            .contextHints(buildCreateFlowHints("finalize", finalWidgetId, opt.getValue()))
                            .build())
                    .collect(Collectors.toList());
            return clarification("Qual endpoint devo usar?",
                    buildAiOptionLabels(payloads),
                    payloads);
        }

        if ("finalize".equals(step)) {
            String widgetId = state.widgetId;
            if (isCreatePrompt && isBlank(widgetId)) {
                widgetId = "praxis-table";
            }
            if (isBlank(widgetId) || !isValidWidgetId(widgetId, request, context)) {
                return buildWidgetStepResponse(request, context);
            }
            if (isBlank(state.resourcePath)) {
                return clarification("Escolha um endpoint válido.", List.of());
            }
            JsonNode patch = buildPageWidgetPatch(widgetId, state.resourcePath);
            return AiOrchestratorResponse.builder()
                    .type("patch")
                    .patch(patch)
                    .diff(buildPatchDiff(currentState, patch))
                    .build();
        }

        return clarification("Escolha uma opção válida para continuar.", List.of());
    }

    private AiOrchestratorResponse buildWidgetStepResponse(
            AiOrchestratorRequest request,
            AiContextDTO context) {
        List<AiOption> widgets = resolveWidgetOptions(request, context);
        if (widgets.isEmpty()) {
            return clarification("Nenhum widget disponível para seleção.", List.of());
        }
        List<AiOption> payloads = widgets.stream()
                .map(opt -> AiOption.builder()
                        .value(opt.getValue())
                        .label(opt.getLabel())
                        .example(opt.getExample())
                        .contextHints(buildCreateFlowHints("endpoint", opt.getValue(), null))
                        .build())
                .collect(Collectors.toList());
        return clarification("Qual componente deseja adicionar?",
                buildAiOptionLabels(payloads),
                payloads);
    }

    private JsonNode extractCreateFlow(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return null;
        }
        JsonNode node = contextHints.get("createFlow");
        return node != null && node.isObject() ? node : null;
    }

    private CreateFlowState parseCreateFlow(JsonNode createFlow) {
        if (createFlow == null || !createFlow.isObject()) {
            return new CreateFlowState(null, null, null, false);
        }
        String step = textOrNull(createFlow.get("step"));
        if (step != null) {
            String normalized = step.trim().toLowerCase(Locale.ROOT);
            if (!List.of("widget", "endpoint", "finalize").contains(normalized)) {
                return CreateFlowState.invalid();
            }
            step = normalized;
        }
        String widgetId = textOrNull(createFlow.get("widgetId"));
        String resourcePath = textOrNull(createFlow.get("resourcePath"));
        return new CreateFlowState(step, widgetId, resourcePath, false);
    }

    private JsonNode buildCreateFlowHints(String step, String widgetId, String resourcePath) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode flow = root.putObject("createFlow");
        flow.put("version", "v1");
        flow.put("step", step);
        if (!isBlank(widgetId)) {
            flow.put("widgetId", widgetId);
        }
        if (!isBlank(resourcePath)) {
            flow.put("resourcePath", resourcePath);
        }
        return root;
    }

    private List<AiOption> resolveWidgetOptions(
            AiOrchestratorRequest request,
            AiContextDTO context) {
        List<AiOption> out = new ArrayList<>();
        JsonNode runtime = request != null ? request.getRuntimeState() : null;
        JsonNode catalog = runtime != null ? runtime.get("componentCatalog") : null;
        if (catalog != null && catalog.isArray()) {
            for (JsonNode item : catalog) {
                String id = textOrNull(item.get("id"));
                if (isBlank(id)) continue;
                String label = textOrNull(item.get("friendlyName"));
                out.add(AiOption.builder()
                        .value(id)
                        .label(!isBlank(label) ? label : id)
                        .build());
            }
        }
        if (!out.isEmpty()) {
            return out;
        }

        JsonNode contextPack = extractComponentContextPack(
                context != null ? context.getComponentDefinition() : null);
        JsonNode options = contextPack != null
                ? contextPack.path("optionsByPath").path("page.widgets[].definition.id").path("options")
                : null;
        if (options != null && options.isArray()) {
            for (JsonNode opt : options) {
                String value = textOrNull(opt.get("value"));
                String label = textOrNull(opt.get("label"));
                if (isBlank(value) && isBlank(label)) continue;
                String id = !isBlank(value) ? value : label;
                out.add(AiOption.builder()
                        .value(id)
                        .label(!isBlank(label) ? label : id)
                        .build());
            }
        }
        return out;
    }

    private List<ApiSearchResult> searchEndpoints(
            AiOrchestratorRequest request,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        return retrievalService.searchApiMetadata(
                request.getUserPrompt(),
                request.getApiMethod(),
                request.getApiTags(),
                request.getApiSearchLimit() != null ? request.getApiSearchLimit() : DEFAULT_API_SEARCH_LIMIT,
                embeddingConfig);
    }

    private List<ApiSearchResult> filterTableEndpoints(List<ApiSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<ApiSearchResult> filtered = new ArrayList<>();
        for (ApiSearchResult result : results) {
            if (result == null) {
                continue;
            }
            String method = result.getMethod();
            if (method == null || !method.equalsIgnoreCase("POST")) {
                continue;
            }
            String path = result.getPath();
            String summary = result.getSummary();
            if (containsFilterKeyword(path) || containsFilterKeyword(summary)) {
                filtered.add(result);
            }
        }
        return filtered;
    }

    private boolean containsFilterKeyword(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("filter");
    }

    private boolean isValidWidgetId(
            String widgetId,
            AiOrchestratorRequest request,
            AiContextDTO context) {
        if (isBlank(widgetId)) {
            return false;
        }
        Set<String> ids = new LinkedHashSet<>();
        JsonNode runtime = request != null ? request.getRuntimeState() : null;
        JsonNode catalog = runtime != null ? runtime.get("componentCatalog") : null;
        if (catalog != null && catalog.isArray()) {
            for (JsonNode item : catalog) {
                String id = textOrNull(item.get("id"));
                if (!isBlank(id)) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            JsonNode contextPack = extractComponentContextPack(
                    context != null ? context.getComponentDefinition() : null);
            JsonNode options = contextPack != null
                    ? contextPack.path("optionsByPath").path("page.widgets[].definition.id").path("options")
                    : null;
            if (options != null && options.isArray()) {
                for (JsonNode opt : options) {
                    String id = textOrNull(opt.get("value"));
                    if (isBlank(id)) {
                        id = textOrNull(opt.get("label"));
                    }
                    if (!isBlank(id)) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids.contains(widgetId);
    }

    private boolean isValidResourcePath(String resourcePath, List<ApiSearchResult> results) {
        if (isBlank(resourcePath) || results == null || results.isEmpty()) {
            return false;
        }
        for (ApiSearchResult result : results) {
            if (result != null && resourcePath.equalsIgnoreCase(result.getPath())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode buildPageWidgetPatch(String widgetId, String resourcePath) {
        ObjectNode patch = objectMapper.createObjectNode();
        ObjectNode page = patch.putObject("page");
        ArrayNode widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", widgetId);
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("resourcePath", resourcePath);
        ObjectNode layout = widget.putObject("layout");
        layout.put("col", 1);
        layout.put("row", 1);
        layout.put("colSpan", 3);
        layout.put("rowSpan", 3);
        return patch;
    }

    private List<String> buildAiOptionLabels(List<AiOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (AiOption option : options) {
            if (option == null) continue;
            String label = option.getLabel();
            if (label != null && !label.isBlank()) {
                labels.add(label);
                continue;
            }
            String value = option.getValue();
            if (value != null && !value.isBlank()) {
                labels.add(value);
            }
        }
        return labels;
    }

    private boolean promptMentionsTable(String prompt) {
        if (prompt == null) {
            return false;
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        return normalized.contains("tabela") || normalized.contains("table");
    }

    private void adjustPageBuilderCreateIntent(
            AiIntentClassification intent,
            AiOrchestratorRequest request,
            AiContextDTO context) {
        if (intent == null || request == null) {
            return;
        }
        if (!isPageBuilder(request.getComponentId())) {
            return;
        }
        if (!isCreateTableRequest(request)) {
            return;
        }
        String resourcePath = request.getResourcePath();
        if ((resourcePath == null || resourcePath.isBlank()) && context != null) {
            resourcePath = context.getResourcePath();
        }
        if (resourcePath != null && !resourcePath.isBlank()) {
            intent.setNeedsClarification(false);
            intent.setMissingContext(null);
            intent.setOptions(null);
            return;
        }
        List<String> missing = new ArrayList<>();
        missing.add("resourcePath");
        intent.setMissingContext(missing);
        intent.setNeedsClarification(true);
        intent.setOptions(null);
    }

    private boolean isPageBuilder(String componentId) {
        if (componentId == null || componentId.isBlank()) {
            return false;
        }
        String normalized = componentId.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("gridster-page") || normalized.contains("dynamic-gridster-page");
    }

    private boolean isCreateTableRequest(AiOrchestratorRequest request) {
        if (request == null || request.getUserPrompt() == null) {
            return false;
        }
        String prompt = request.getUserPrompt().toLowerCase(Locale.ROOT);
        boolean mentionsTable = prompt.contains("tabela") || prompt.contains("table");
        if (!mentionsTable) {
            return false;
        }
        JsonNode runtime = request.getRuntimeState();
        if (runtime != null && runtime.has("widgetsCount") && runtime.get("widgetsCount").isInt()) {
            return runtime.get("widgetsCount").asInt() == 0;
        }
        return true;
    }

    private boolean shouldIgnoreColumnClarification(
            boolean isTable,
            List<String> columnNames,
            List<String> missingContext) {
        if (!isTable || missingContext == null || missingContext.isEmpty()) {
            return false;
        }
        if (columnNames != null && !columnNames.isEmpty()) {
            return false;
        }
        return missingContext.stream()
                .map(this::normalizeMissingKey)
                .anyMatch(key -> key.contains("column"));
    }

    private boolean shouldIgnoreMissingColumnForCreate(
            AiIntentClassification intent,
            AiActionPlan actionPlan,
            List<ComponentAction> componentActions) {
        if (intent == null || actionPlan == null || componentActions == null || componentActions.isEmpty()) {
            return false;
        }
        List<String> missing = intent.getMissingContext();
        if (missing == null || missing.isEmpty()) {
            return false;
        }
        boolean hasColumnMissing = missing.stream()
                .map(this::normalizeMissingKey)
                .anyMatch(key -> key.contains("column"));
        if (!hasColumnMissing) {
            return false;
        }
        Map<String, ComponentAction> actionById = indexActionCatalog(componentActions);
        if (actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return false;
        }
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null || action.getType() == null) {
                continue;
            }
            ComponentAction def = actionById.get(normalizeActionKey(action.getType()));
            if (def == null) {
                continue;
            }
            if (Boolean.FALSE.equals(def.requiresExistingTarget)) {
                return true;
            }
            if (def.operation != null && "create".equalsIgnoreCase(def.operation)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCreateOnlyPlan(AiActionPlan actionPlan, List<ComponentAction> componentActions) {
        if (actionPlan == null
                || actionPlan.getActions() == null
                || actionPlan.getActions().isEmpty()
                || componentActions == null
                || componentActions.isEmpty()) {
            return false;
        }
        Map<String, ComponentAction> actionById = indexActionCatalog(componentActions);
        boolean hasCreate = false;
        for (AiActionPlan.Action action : actionPlan.getActions()) {
            if (action == null || action.getType() == null) {
                return false;
            }
            ComponentAction def = actionById.get(normalizeActionKey(action.getType()));
            if (def == null) {
                return false;
            }
            if (Boolean.TRUE.equals(def.requiresExistingTarget)) {
                return false;
            }
            if (def.operation == null || def.operation.isBlank()) {
                if (!Boolean.FALSE.equals(def.requiresExistingTarget)) {
                    return false;
                }
            } else if (!"create".equalsIgnoreCase(def.operation)) {
                return false;
            }
            hasCreate = true;
        }
        return hasCreate;
    }

    private List<String> removeMissingContext(List<String> missingContext, String key) {
        if (missingContext == null || missingContext.isEmpty()) {
            return null;
        }
        String normalizedKey = normalizeMissingKey(key);
        List<String> filtered = missingContext.stream()
                .filter(item -> !normalizeMissingKey(item).contains(normalizedKey))
                .collect(Collectors.toList());
        return filtered.isEmpty() ? null : filtered;
    }

    private AiOrchestratorResponse resolveResourceClarification(
            AiOrchestratorRequest request,
            AiIntentClassification intent,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        if (request == null || intent == null) {
            return null;
        }
        List<String> missing = intent.getMissingContext();
        if (!hasMissingResourceContext(missing)) {
            return null;
        }

        List<ApiSearchResult> results = retrievalService.searchApiMetadata(
                request.getUserPrompt(),
                request.getApiMethod(),
                request.getApiTags(),
                request.getApiSearchLimit() != null ? request.getApiSearchLimit() : DEFAULT_API_SEARCH_LIMIT,
                embeddingConfig);

        if (results.isEmpty()) {
            return clarification(buildResourceClarificationMessage(request.getComponentId()), List.of());
        }
        if (results.size() == 1) {
            ApiSearchResult picked = results.get(0);
            if (picked != null && picked.getPath() != null && !picked.getPath().isBlank()) {
                request.setResourcePath(picked.getPath());
                intent.setMissingContext(removeMissingResourceContext(missing));
                if (intent.getMissingContext() == null) {
                    intent.setNeedsClarification(false);
                }
            }
            return null;
        }

        List<AiOption> optionPayloads = buildApiOptionPayloads(results);
        List<String> labels = optionPayloads.stream()
                .map(AiOption::getLabel)
                .filter(label -> label != null && !label.isBlank())
                .collect(Collectors.toList());
        return clarification(
                buildResourceClarificationMessage(request.getComponentId()),
                labels,
                optionPayloads);
    }

    private boolean hasMissingResourceContext(List<String> missingContext) {
        if (missingContext == null || missingContext.isEmpty()) {
            return false;
        }
        return missingContext.stream()
                .map(this::normalizeMissingKey)
                .anyMatch(key -> RESOURCE_MISSING_KEYS.stream().anyMatch(key::contains));
    }

    private List<String> removeMissingResourceContext(List<String> missingContext) {
        if (missingContext == null || missingContext.isEmpty()) {
            return null;
        }
        List<String> filtered = missingContext.stream()
                .filter(item -> !RESOURCE_MISSING_KEYS.stream()
                        .anyMatch(key -> normalizeMissingKey(item).contains(key)))
                .collect(Collectors.toList());
        return filtered.isEmpty() ? null : filtered;
    }

    private String buildResourceClarificationMessage(String componentId) {
        if (componentId == null) {
            return "Qual conjunto de dados devo usar?";
        }
        String normalized = componentId.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("table")) {
            return "Qual conjunto de dados voce quer listar?";
        }
        if (normalized.contains("crud")) {
            return "Qual conjunto de dados devo usar nesse CRUD?";
        }
        if (normalized.contains("form")) {
            return "Qual conjunto de dados devo usar nesse formulario?";
        }
        if (normalized.contains("list")) {
            return "Qual conjunto de dados voce quer exibir?";
        }
        return "Qual conjunto de dados devo usar?";
    }

    private String normalizeMissingKey(String key) {
        if (key == null) {
            return "";
        }
        return key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private List<AiOption> buildApiOptionPayloads(List<ApiSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<AiOption> payloads = new ArrayList<>();
        for (ApiSearchResult result : results) {
            if (result == null || result.getPath() == null || result.getPath().isBlank()) {
                continue;
            }
            String label = buildApiOptionLabel(result);
            String example = buildApiOptionExample(result);
            payloads.add(AiOption.builder()
                    .value(result.getPath().trim())
                    .label(label)
                    .example(example)
                    .build());
        }
        return payloads;
    }

    private String buildApiOptionLabel(ApiSearchResult result) {
        String summaryLabel = normalizeSummaryLabel(result != null ? result.getSummary() : null);
        if (summaryLabel != null && !summaryLabel.isBlank()) {
            return summaryLabel;
        }
        String path = result != null ? result.getPath() : null;
        if (path != null && !path.isBlank()) {
            return path.trim();
        }
        return "Endpoint";
    }

    private String buildApiOptionExample(ApiSearchResult result) {
        if (result == null) {
            return null;
        }
        String summary = result.getSummary();
        if (summary != null && !summary.isBlank()) {
            return summary.trim();
        }
        String method = result.getMethod();
        String path = result.getPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        if (method == null || method.isBlank()) {
            return path.trim();
        }
        return method.trim().toUpperCase(Locale.ROOT) + " " + path.trim();
    }

    private String normalizeSummaryLabel(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String cleaned = summary.trim();
        if (cleaned.length() > 80) {
            return null;
        }
        String[] split = cleaned.split("[\\.;-]");
        String candidate = split.length > 0 ? split[0].trim() : cleaned;
        if (candidate.isBlank()) {
            candidate = cleaned;
        }
        if (candidate.length() > 60) {
            return null;
        }
        return candidate;
    }

    private ContextRequest parseContextRequest(JsonNode result) {
        if (result == null || result.isNull()) {
            return null;
        }
        JsonNode node = result.get("contextRequest");
        if (node == null || node.isNull() || !node.isArray()) {
            return null;
        }
        List<Integer> codes = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) continue;
            Integer code = parseContextCode(item);
            if (code != null) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return null;
        }
        String message = textOrNull(result.get("message"));
        return new ContextRequest(codes, message);
    }

    private Integer parseContextCode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            String raw = node.asText().trim();
            if (raw.isBlank()) return null;
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private JsonNode buildContextHintsFromRequest(
            AiContextDTO context,
            AiOrchestratorRequest request,
            JsonNode schema,
            EmbeddingService.EmbeddingCallConfig embeddingConfig,
            List<Integer> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        ObjectNode hints = objectMapper.createObjectNode();
        ObjectNode pack = objectMapper.createObjectNode();
        for (Integer code : codes) {
            if (code == null) continue;
            switch (code) {
                case CONTEXT_CODE_COMPONENT_DESCRIPTION -> {
                    if (context != null
                            && context.getDescription() != null
                            && !context.getDescription().isBlank()) {
                        pack.put("componentDescription", context.getDescription());
                    }
                }
                case CONTEXT_CODE_COMPONENT_SIGNATURE -> {
                    JsonNode signature = buildComponentSignature(context, request);
                    if (signature != null && !signature.isNull() && signature.size() > 0) {
                        pack.set("componentSignature", signature);
                    }
                }
                case CONTEXT_CODE_SCHEMA_FIELDS -> {
                    ArrayNode fields = buildSchemaFieldList(
                            request != null ? request.getSchemaFields() : null,
                            schema);
                    if (fields != null && fields.size() > 0) {
                        pack.set("schemaFields", fields);
                    }
                }
                case CONTEXT_CODE_SCHEMA_SAMPLE -> {
                    JsonNode example = buildSchemaSample(
                            request != null ? request.getSchemaFields() : null,
                            schema);
                    if (example != null && !example.isNull() && example.size() > 0) {
                        pack.set("schemaSample", example);
                    }
                }
                case CONTEXT_CODE_ENDPOINT_CANDIDATES -> {
                    ArrayNode endpoints = buildEndpointCandidates(request, embeddingConfig);
                    if (endpoints != null && endpoints.size() > 0) {
                        pack.set("endpointCandidates", endpoints);
                    }
                }
                case CONTEXT_CODE_CURRENT_STATE -> {
                    ArrayNode keys = buildCurrentStateKeys(
                            request != null ? request.getCurrentState() : null);
                    if (keys != null && keys.size() > 0) {
                        pack.set("currentStateKeys", keys);
                    }
                }
                default -> {
                }
            }
        }
        if (pack.size() == 0) {
            return null;
        }
        hints.set("contextPack", pack);
        return hints;
    }

    private JsonNode buildComponentSignature(AiContextDTO context, AiOrchestratorRequest request) {
        ObjectNode signature = objectMapper.createObjectNode();
        JsonNode def = context != null ? context.getComponentDefinition() : null;
        if (def != null && def.isObject()) {
            JsonNode inputs = def.get("inputs");
            JsonNode outputs = def.get("outputs");
            if (inputs != null && !inputs.isNull()) {
                signature.set("inputs", inputs);
            }
            if (outputs != null && !outputs.isNull()) {
                signature.set("outputs", outputs);
            }
        }
        if (signature.size() == 0 && request != null && request.getCurrentState() != null) {
            List<String> inputs = extractObjectKeys(request.getCurrentState(), "inputs");
            List<String> outputs = extractObjectKeys(request.getCurrentState(), "outputs");
            if (inputs != null && !inputs.isEmpty()) {
                ArrayNode inputArr = objectMapper.createArrayNode();
                inputs.forEach(inputArr::add);
                signature.set("inputs", inputArr);
            }
            if (outputs != null && !outputs.isEmpty()) {
                ArrayNode outputArr = objectMapper.createArrayNode();
                outputs.forEach(outputArr::add);
                signature.set("outputs", outputArr);
            }
        }
        return signature;
    }

    private ArrayNode buildSchemaFieldList(JsonNode schemaFields, JsonNode schema) {
        ArrayNode out = objectMapper.createArrayNode();
        List<String> names = new ArrayList<>();
        if (schemaFields != null && schemaFields.isArray()) {
            for (JsonNode field : schemaFields) {
                String name = extractFieldName(field);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        } else if (schema != null && schema.isObject()) {
            JsonNode props = schema.get("properties");
            if (props != null && props.isObject()) {
                props.fieldNames().forEachRemaining(names::add);
            }
        }
        names.stream().distinct().forEach(out::add);
        return out;
    }

    private JsonNode buildSchemaSample(JsonNode schemaFields, JsonNode schema) {
        ObjectNode sample = objectMapper.createObjectNode();
        if (schema != null && schema.isObject()) {
            JsonNode props = schema.get("properties");
            if (props != null && props.isObject()) {
                props.fields().forEachRemaining(entry -> {
                    JsonNode value = buildSampleValue(entry.getValue());
                    if (value != null) {
                        sample.set(entry.getKey(), value);
                    }
                });
            }
        }
        if (sample.size() == 0 && schemaFields != null && schemaFields.isArray()) {
            for (JsonNode field : schemaFields) {
                String name = extractFieldName(field);
                if (name != null && !name.isBlank()) {
                    sample.set(name, objectMapper.getNodeFactory().textNode("<value>"));
                }
            }
        }
        return sample;
    }

    private JsonNode buildSampleValue(JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isNull()) {
            return null;
        }
        String type = schemaNode.has("type") ? schemaNode.get("type").asText() : null;
        if (type == null && schemaNode.has("format")) {
            type = schemaNode.get("format").asText();
        }
        if (type == null || type.isBlank()) {
            return objectMapper.getNodeFactory().textNode("<value>");
        }
        return switch (type) {
            case "string", "date", "date-time", "uuid" -> objectMapper.getNodeFactory().textNode("<text>");
            case "integer", "int", "int32", "int64" -> objectMapper.getNodeFactory().numberNode(0);
            case "number", "float", "double", "decimal" -> objectMapper.getNodeFactory().numberNode(0.0d);
            case "boolean" -> objectMapper.getNodeFactory().booleanNode(false);
            case "array" -> objectMapper.createArrayNode();
            case "object" -> objectMapper.createObjectNode();
            default -> objectMapper.getNodeFactory().textNode("<value>");
        };
    }

    private ArrayNode buildEndpointCandidates(
            AiOrchestratorRequest request,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        if (request == null) {
            return objectMapper.createArrayNode();
        }
        List<ApiSearchResult> results = retrievalService.searchApiMetadata(
                request.getUserPrompt(),
                request.getApiMethod(),
                request.getApiTags(),
                request.getApiSearchLimit() != null ? request.getApiSearchLimit() : DEFAULT_API_SEARCH_LIMIT,
                embeddingConfig);
        ArrayNode out = objectMapper.createArrayNode();
        if (results == null || results.isEmpty()) {
            return out;
        }
        for (ApiSearchResult result : results) {
            if (result == null || result.getPath() == null || result.getPath().isBlank()) {
                continue;
            }
            ObjectNode node = objectMapper.createObjectNode();
            if (result.getMethod() != null && !result.getMethod().isBlank()) {
                node.put("method", result.getMethod());
            }
            node.put("path", result.getPath());
            if (result.getSummary() != null && !result.getSummary().isBlank()) {
                node.put("summary", result.getSummary());
            }
            out.add(node);
        }
        return out;
    }

    private ArrayNode buildCurrentStateKeys(JsonNode currentState) {
        ArrayNode out = objectMapper.createArrayNode();
        if (currentState == null || !currentState.isObject()) {
            return out;
        }
        currentState.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private String extractFieldName(JsonNode field) {
        if (field == null || field.isNull()) {
            return null;
        }
        if (field.has("name")) {
            return textOrNull(field.get("name"));
        }
        if (field.has("field")) {
            return textOrNull(field.get("field"));
        }
        if (field.has("id")) {
            return textOrNull(field.get("id"));
        }
        return null;
    }

    private JsonNode mergeContextHints(JsonNode existing, JsonNode incoming) {
        ObjectNode merged = objectMapper.createObjectNode();
        if (existing != null && existing.isObject()) {
            merged.setAll((ObjectNode) existing);
        }
        if (incoming != null && incoming.isObject()) {
            incoming.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        }
        return merged;
    }

    private List<String> resolveContextHintKeys(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        contextHints.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) return "config";
        String normalized = scope.trim().toLowerCase();
        if ("config".equals(normalized) || "component".equals(normalized) || "mixed".equals(normalized)) {
            return normalized;
        }
        return "config";
    }

    private String answerQuestion(
            String userPrompt,
            JsonNode currentState,
            AiOrchestratorRequest request,
            AiCallConfig callConfig) {
        String prompt = AiPromptTemplates.buildPrompt(
                AiPromptTemplates.PROMPT_QA,
                Map.of(
                        "USER_INPUT", safe(userPrompt),
                        "TARGET_CONFIG", currentState != null ? currentState.toPrettyString() : "{}"));
        return callAiText("qa_answer", prompt, callConfig, request, 1);
    }

    private String buildExecutionPrompt(
            String userPrompt,
            AiContextDTO context,
            JsonNode contextConfig,
            List<AiCapability> capabilities,
            String capabilityNotes,
            String relevantConcepts,
            AiSchemaContext schemaContext,
            JsonNode schema,
            String runtimeMetadata,
            String ragHints,
            String intentPlan,
            String completenessHints) {
        String contextDesc = buildContextDescription(context, schemaContext);
        String caps = truncateBlock("capabilities",
                formatCapabilities(capabilities),
                maxCapabilitiesChars);
        String notes = truncateBlock("capability_notes",
                capabilityNotes != null ? capabilityNotes : "",
                maxCapabilityNotesChars);
        String concepts = truncateBlock("relevant_concepts",
                relevantConcepts != null ? relevantConcepts : "",
                maxConceptsChars);
        String configJson = truncateBlock("target_config",
                contextConfig != null ? contextConfig.toPrettyString() : "{}",
                maxConfigChars);
        String schemaJson = truncateBlock("schema_json",
                schema != null ? schema.toPrettyString() : "N/A",
                maxSchemaChars);
        String metadataJson = safeMetadata(runtimeMetadata);
        String ragBlock = truncateBlock("rag_hints",
                safeMetadata(ragHints),
                maxRagHintsChars);
        boolean hasTargetConfig = contextConfig != null
                && !contextConfig.isNull()
                && !contextConfig.isMissingNode()
                && !(contextConfig.isObject() && contextConfig.size() == 0);
        String templateConfig = truncateBlock("template_config",
                !hasTargetConfig && context.getTemplate() != null && context.getTemplate().getConfigJson() != null
                ? context.getTemplate().getConfigJson().toPrettyString()
                : "N/A",
                maxTemplateConfigChars);
        String templateMeta = truncateBlock("template_meta",
                !hasTargetConfig && context.getTemplate() != null && context.getTemplate().getTemplateMeta() != null
                ? context.getTemplate().getTemplateMeta().toPrettyString()
                : "N/A",
                maxTemplateMetaChars);
        boolean isTable = "praxis-table".equals(context.getComponentId());
        String contractFormatting = isTable ? AiPromptTemplates.CONTRACT_FORMATTING : "";
        String contractRenderers = isTable ? AiPromptTemplates.CONTRACT_RENDERER_PAYLOADS : "";
        String intentPlanBlock = truncateBlock(
                "intent_plan",
                intentPlan != null ? intentPlan : "N/A",
                maxCapabilityNotesChars);
        String completenessBlock = truncateBlock(
                "completeness_hints",
                completenessHints != null ? completenessHints : "N/A",
                maxCapabilityNotesChars);

        return AiPromptTemplates.buildPrompt(
                AiPromptTemplates.PROMPT_EXECUTION_ENRICHED,
                Map.ofEntries(
                        Map.entry("USER_INPUT", safe(userPrompt)),
                        Map.entry("CONTEXT_DESCRIPTION", contextDesc),
                        Map.entry("CAPABILITIES_RESTRICTION", caps),
                        Map.entry("CAPABILITY_NOTES", notes),
                        Map.entry("RELEVANT_CONCEPTS", concepts),
                        Map.entry("TARGET_CONFIG", configJson),
                        Map.entry("TEMPLATE_CONFIG", templateConfig),
                        Map.entry("TEMPLATE_META", templateMeta),
                        Map.entry("RAG_HINTS", ragBlock),
                        Map.entry("SCHEMA_JSON", schemaJson),
                        Map.entry("RUNTIME_METADATA", metadataJson),
                        Map.entry("INTENT_PLAN", intentPlanBlock),
                        Map.entry("COMPLETENESS_HINTS", completenessBlock),
                        Map.entry("CONTRACT_DSL", AiPromptTemplates.CONTRACT_DSL),
                        Map.entry("CONTRACT_ICONS", AiPromptTemplates.CONTRACT_ICONS),
                        Map.entry("CONTRACT_SAFETY", AiPromptTemplates.CONTRACT_SAFETY),
                        Map.entry("CONTRACT_FORMATTING", contractFormatting),
                        Map.entry("CONTRACT_RENDERER_PAYLOADS", contractRenderers)));
    }

    private IntentPlan generateIntentPlan(
            AiOrchestratorRequest request,
            AiContextDTO context,
            List<AiCapability> capabilities,
            JsonNode currentState,
            JsonNode componentContext,
            AiCallConfig callConfig) {
        JsonNode definition = context != null ? context.getComponentDefinition() : null;
        String prompt = buildIntentPlanPrompt(
                request != null ? request.getComponentType() : null,
                request != null ? request.getUserPrompt() : null,
                capabilities,
                currentState,
                componentContext,
                definition);
        AiJsonSchema schema = buildIntentPlanSchema();
        JsonNode json = generateIntentPlanJson(prompt, schema, request, callConfig);
        if (json == null) {
            return null;
        }
        return objectMapper.convertValue(json, IntentPlan.class);
    }

    private JsonNode generateIntentPlanJson(
            String prompt,
            AiJsonSchema schema,
            AiOrchestratorRequest request,
            AiCallConfig callConfig) {
        AiCallConfig baseConfig = buildActionPlanConfig(callConfig);
        JsonNode json = callAiJson("intent_plan", prompt, schema, baseConfig != null ? baseConfig : callConfig, request, 1);
        if (json != null) {
            return json;
        }
        AiCallConfig retryConfig = buildActionPlanRetryConfig(baseConfig);
        if (retryConfig == null) {
            return null;
        }
        return callAiJson("intent_plan", prompt, schema, retryConfig, request, 2);
    }

    private String buildIntentPlanPrompt(
            String componentType,
            String userPrompt,
            List<AiCapability> capabilities,
            JsonNode currentState,
            JsonNode componentContext,
            JsonNode componentDefinition) {
        String caps = truncateBlock(
                "capabilities",
                formatCapabilities(capabilities),
                maxCapabilitiesChars);
        String stateSummary = truncateBlock(
                "current_state_summary",
                buildCurrentStateSummary(currentState),
                maxRuntimeMetadataChars);
        
        String concepts = buildConceptsBlock(componentDefinition);
        String options = buildOptionsByPathSummary(componentContext);
        
        String optionsSummary = truncateBlock(
                "options_by_path_and_concepts",
                options + concepts,
                maxCapabilityNotesChars);
        return AiPromptTemplates.buildPrompt(
                AiPromptTemplates.PROMPT_INTENT_PLAN,
                Map.of(
                        "COMPONENT_TYPE", safe(componentType),
                        "USER_INPUT", safe(userPrompt),
                        "CAPABILITIES_RESTRICTION", caps,
                        "CURRENT_STATE_SUMMARY", stateSummary,
                        "OPTIONS_BY_PATH", optionsSummary));
    }

    private String buildCurrentStateSummary(JsonNode currentState) {
        ObjectNode summary = objectMapper.createObjectNode();
        ArrayNode keys = buildCurrentStateKeys(currentState);
        if (keys != null && keys.size() > 0) {
            summary.set("keys", keys);
        }
        List<String> columns = extractColumnNames(currentState);
        if (!columns.isEmpty()) {
            summary.set("columns", objectMapper.valueToTree(columns));
        }
        List<String> inputs = extractObjectKeys(currentState, "inputs");
        if (!inputs.isEmpty()) {
            summary.set("inputs", objectMapper.valueToTree(inputs));
        }
        List<String> outputs = extractObjectKeys(currentState, "outputs");
        if (!outputs.isEmpty()) {
            summary.set("outputs", objectMapper.valueToTree(outputs));
        }
        return summary.size() > 0 ? summary.toPrettyString() : "{}";
    }

    private String buildConceptsBlock(JsonNode componentDefinition) {
        if (componentDefinition == null) return "";
        JsonNode conceptsNode = componentDefinition.get("aiConcepts");
        if (conceptsNode == null || conceptsNode.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n### REGRAS E CONCEITOS IMPORTANTES (Leia com atenção):\n");
        conceptsNode.fields().forEachRemaining(entry -> {
            JsonNode concept = entry.getValue();
            sb.append("- ").append(textOrNull(concept.get("title"))).append(" (").append(entry.getKey()).append(")\n");
            sb.append("  Desc: ").append(textOrNull(concept.get("description"))).append("\n");
            
            JsonNode rules = concept.get("rules");
            if (rules != null && rules.isArray()) {
                rules.forEach(r -> sb.append("  * RULE: ").append(r.asText()).append("\n"));
            }
            
            JsonNode syntax = concept.get("syntax");
            if (syntax != null && !syntax.isNull()) {
                sb.append("  * SYNTAX: ").append(syntax.asText()).append("\n");
            }

            JsonNode anti = concept.get("antiPatterns");
            if (anti != null && anti.isArray()) {
                anti.forEach(a -> sb.append("  * AVOID (Anti-Pattern): ").append(a.asText()).append("\n"));
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    private String buildOptionsByPathSummary(JsonNode componentContext) {
        if (componentContext == null || !componentContext.isObject()) {
            return "N/A";
        }
        JsonNode optionsByPath = componentContext.get("optionsByPath");
        if (optionsByPath == null || !optionsByPath.isObject()) {
            return "N/A";
        }
        ArrayNode keys = objectMapper.createArrayNode();
        optionsByPath.fieldNames().forEachRemaining(keys::add);
        return keys.size() > 0 ? keys.toPrettyString() : "N/A";
    }

    private String formatIntentPlan(IntentPlan plan) {
        if (plan == null) {
            return "N/A";
        }
        return objectMapper.valueToTree(plan).toPrettyString();
    }

    private IntentPlan applyDeterministicIntentChecks(
            IntentPlan plan,
            AiActionPlan actionPlan,
            List<ComponentAction> actionCatalog,
            JsonNode currentState,
            boolean isTable,
            List<ColumnDescriptor> columnDescriptors,
            List<String> columnResolverKeys) {
        if (plan == null || actionPlan == null || actionPlan.getActions() == null || actionPlan.getActions().isEmpty()) {
            return plan;
        }
        if (actionCatalog == null || actionCatalog.isEmpty()) {
            return plan;
        }
        AiActionPlan resolvedPlan = isTable
                ? resolveTableActionPlanTargets(actionPlan, columnDescriptors, columnResolverKeys)
                : actionPlan;
        Map<String, ComponentAction> actionById = indexActionCatalog(actionCatalog);
        List<IntentAction> derivedActions = new ArrayList<>();
        int expectedActions = 0;
        for (AiActionPlan.Action action : resolvedPlan.getActions()) {
            if (action == null || action.getType() == null || action.getType().isBlank()) {
                continue;
            }
            expectedActions++;
            ComponentAction def = actionById.get(normalizeActionKey(action.getType()));
            RenderedActionPatch rendered = renderActionPatch(def, action);
            if (rendered == null || rendered.patch == null || !rendered.missingTokens.isEmpty()) {
                continue;
            }
            List<AiPatchDiff> diffs = buildPatchDiff(currentState, rendered.patch);
            if (diffs == null || diffs.isEmpty()) {
                continue;
            }
            List<ActionCheck> checks = new ArrayList<>();
            for (AiPatchDiff diff : diffs) {
                if (diff == null || diff.getPath() == null || diff.getPath().isBlank()) {
                    continue;
                }
                JsonNode after = diff.getAfter();
                if (after != null && after.isArray() && isConditionalRenderersPath(diff.getPath())) {
                    for (JsonNode item : after) {
                        checks.add(ActionCheck.builder()
                                .type("contains")
                                .path(diff.getPath())
                                .value(item)
                                .build());
                    }
                } else {
                    checks.add(ActionCheck.builder()
                            .type("pathEquals")
                            .path(diff.getPath())
                            .value(after)
                            .build());
                }
            }
            if (!checks.isEmpty()) {
                derivedActions.add(IntentAction.builder()
                        .id(formatActionCheckId(action))
                        .checks(checks)
                        .build());
            }
        }
        if (expectedActions > 0 && derivedActions.size() == expectedActions) {
            plan.setActions(derivedActions);
        }
        return plan;
    }

    private String formatActionCheckId(AiActionPlan.Action action) {
        if (action == null) {
            return "action";
        }
        String type = action.getType();
        if (type == null || type.isBlank()) {
            return "action";
        }
        String target = action.getTarget();
        if (target == null || target.isBlank()) {
            return type;
        }
        return type + "[" + target + "]";
    }

    private String buildCompletenessHints(
            IntentPlan plan,
            List<AiPatchDiff> diff,
            List<IntentAction> missingActions) {
        String missing = missingActions != null
                ? objectMapper.valueToTree(missingActions).toPrettyString()
                : "[]";
        String diffJson = diff != null
                ? objectMapper.valueToTree(diff).toPrettyString()
                : "[]";
        String planJson = plan != null
                ? objectMapper.valueToTree(plan).toPrettyString()
                : "N/A";
        return "INTENT_PLAN:\n" + planJson
                + "\nMISSING_ACTIONS:\n" + missing
                + "\nCURRENT_DIFF:\n" + diffJson
                + "\nREGRAS: nao mude o que ja esta correto; apenas complete o que falta.";
    }

    private CompletenessResult evaluateCompleteness(
            IntentPlan plan,
            List<AiPatchDiff> diffs,
            JsonNode currentState,
            JsonNode componentContext,
            List<AiCapability> capabilities,
            boolean allowUnknownColumns) {
        if (plan == null || plan.getActions() == null || plan.getActions().isEmpty()) {
            return CompletenessResult.complete();
        }
        List<String> planQuestions = plan.getQuestions();
        if (planQuestions != null && !planQuestions.isEmpty()) {
            return CompletenessResult.incomplete(List.of(), planQuestions);
        }
        List<IntentAction> missingActions = new ArrayList<>();
        for (IntentAction action : plan.getActions()) {
            if (!isActionComplete(action, diffs)) {
                missingActions.add(action);
            }
        }
        if (missingActions.isEmpty()) {
            return CompletenessResult.complete();
        }
        List<String> questions = buildMissingActionQuestions(
                missingActions,
                currentState,
                componentContext,
                capabilities,
                allowUnknownColumns);
        return CompletenessResult.incomplete(missingActions, questions);
    }

    private boolean isActionComplete(IntentAction action, List<AiPatchDiff> diffs) {
        if (action == null || action.getChecks() == null || action.getChecks().isEmpty()) {
            return false;
        }
        for (ActionCheck check : action.getChecks()) {
            if (!isCheckSatisfied(check, diffs)) {
                return false;
            }
        }
        return true;
    }

    private boolean isCheckSatisfied(ActionCheck check, List<AiPatchDiff> diffs) {
        if (check == null || diffs == null || diffs.isEmpty()) {
            return false;
        }
        String type = check.getType() != null ? check.getType().trim() : "";
        String checkPath = normalizePath(check.getPath());
        JsonNode expected = check.getValue();
        for (AiPatchDiff diff : diffs) {
            if (diff == null) continue;
            String diffPath = normalizePath(diff.getPath());
            if ("pathchanged".equalsIgnoreCase(type)) {
                if (diffPath.equals(checkPath) || diffPath.startsWith(checkPath + ".")) {
                    return true;
                }
                if (checkPath.isBlank() && !diffPath.isBlank()) {
                    return true;
                }
            } else if ("pathequals".equalsIgnoreCase(type)) {
                if (diffPath.equals(checkPath) && expected != null && diff.getAfter() != null) {
                    JsonNode actual = diff.getAfter();
                    JsonNode exp = expected;
                    if (isConditionalRenderersPath(checkPath)) {
                        actual = stripConditionalRendererDescription(actual);
                        exp = stripConditionalRendererDescription(exp);
                    }
                    if (actual != null && exp != null && actual.equals(exp)) {
                        return true;
                    }
                }
            } else if ("contains".equalsIgnoreCase(type)) {
                if (diffPath.equals(checkPath)) {
                    JsonNode actual = diff.getAfter();
                    JsonNode exp = expected;
                    if (isConditionalRenderersPath(checkPath)) {
                        actual = stripConditionalRendererDescription(actual);
                        exp = stripConditionalRendererDescription(exp);
                    }
                    if (containsValue(actual, exp)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean containsValue(JsonNode actual, JsonNode expected) {
        if (actual == null || actual.isNull() || expected == null || expected.isNull()) {
            return false;
        }
        if (actual.isArray()) {
            for (JsonNode item : actual) {
                if (item != null && item.equals(expected)) {
                    return true;
                }
                if (patchContainsExpected(item, expected)) {
                    return true;
                }
            }
            return false;
        }
        return patchContainsExpected(actual, expected);
    }

    private boolean isConditionalRenderersPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return path.endsWith("conditionalRenderers");
    }

    private JsonNode stripConditionalRendererDescription(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.remove("description");
            return copy;
        }
        if (!node.isArray()) {
            return node;
        }
        ArrayNode copy = ((ArrayNode) node).deepCopy();
        for (JsonNode item : copy) {
            if (item != null && item.isObject()) {
                ((ObjectNode) item).remove("description");
            }
        }
        return copy;
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        if ("$".equals(trimmed)) {
            return "";
        }
        if (trimmed.startsWith("$.")) {
            return trimmed.substring(2);
        }
        if (trimmed.startsWith("$")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    private String normalizeOptionsPath(String path) {
        String normalized = normalizePath(path);
        return normalized.replaceAll("\\[[^\\]]+\\]", "[]");
    }

    private List<String> buildMissingActionQuestions(
            List<IntentAction> actions,
            JsonNode currentState,
            JsonNode componentContext,
            List<AiCapability> capabilities,
            boolean allowUnknownColumns) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> questions = new java.util.LinkedHashSet<>();
        List<String> columns = extractColumnNames(currentState);
        for (IntentAction action : actions) {
            if (action == null) continue;
            String actionId = action.getId() != null ? action.getId() : "acao solicitada";
            List<ActionCheck> checks = action.getChecks();
            if (checks == null || checks.isEmpty()) {
                questions.add("Preciso de mais detalhes para completar \"" + actionId + "\".");
                continue;
            }
            for (ActionCheck check : checks) {
                if (check == null) continue;
                String path = normalizePath(check.getPath());
                if (path.startsWith("columns[") && !columns.isEmpty()) {
                    ColumnIdentity identity = parseColumnIdentity(path);
                    if (identity != null && identity.value != null
                            && !hasColumnIdentity(currentState, identity.key, identity.value)) {
                        if (allowUnknownColumns) {
                            continue;
                        }
                        String available = String.join(", ", columns);
                        questions.add("Qual coluna devo usar? Colunas disponiveis: " + available + ".");
                        continue;
                    }
                }
                if ("pathequals".equalsIgnoreCase(check.getType())
                        || "contains".equalsIgnoreCase(check.getType())) {
                    String optionPath = normalizeOptionsPath(check.getPath());
                    List<ContextOption> options = resolveOptionsForPath(
                            componentContext,
                            capabilities,
                            optionPath);
                    if (!options.isEmpty()) {
                        List<String> labels = buildOptionLabels(options);
                        if (!labels.isEmpty()) {
                            questions.add("Qual valor devo usar em \"" + optionPath
                                    + "\"? Opcoes: " + String.join(", ", labels) + ".");
                            continue;
                        }
                    }
                }
                questions.add("Preciso de mais detalhes para completar \"" + actionId + "\".");
            }
        }
        return new ArrayList<>(questions);
    }

    private ColumnIdentity parseColumnIdentity(String path) {
        if (path == null) return null;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("columns\\[([^=]+)=([^\\]]+)\\]").matcher(path);
        if (!matcher.find()) {
            return null;
        }
        String key = matcher.group(1);
        String value = matcher.group(2);
        if (key == null || value == null) {
            return null;
        }
        return new ColumnIdentity(key.trim(), value.trim());
    }

    private boolean hasColumnIdentity(JsonNode currentState, String key, String value) {
        if (currentState == null || !currentState.has("columns")) {
            return false;
        }
        JsonNode columns = currentState.get("columns");
        if (columns == null || !columns.isArray()) {
            return false;
        }
        for (JsonNode column : columns) {
            if (column == null || !column.isObject()) continue;
            String found = textOrNull(column.get(key));
            if (value.equals(found)) {
                return true;
            }
        }
        return false;
    }

    private String buildQuestionsMessage(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return "Preciso de mais detalhes para continuar.";
        }
        StringBuilder message = new StringBuilder("Preciso de alguns detalhes para continuar:");
        for (String question : questions) {
            if (question == null || question.isBlank()) continue;
            message.append("\n- ").append(question.trim());
        }
        return message.toString();
    }

    private String formatRuntimeMetadata(
            JsonNode dataProfile,
            JsonNode schemaFields,
            JsonNode runtimeState,
            JsonNode contextHints) {
        ObjectNode metadata = objectMapper.createObjectNode();
        if (dataProfile != null && !dataProfile.isNull()) {
            metadata.set("dataProfile", dataProfile);
        }
        if (schemaFields != null && !schemaFields.isNull()) {
            metadata.set("schemaFields", schemaFields);
        }
        if (runtimeState != null && !runtimeState.isNull()) {
            metadata.set("runtimeState", runtimeState);
        }
        if (contextHints != null && !contextHints.isNull()) {
            metadata.set("contextHints", contextHints);
        }
        if (metadata.size() == 0) {
            return "N/A";
        }
        return metadata.toPrettyString();
    }

    private String formatContextHints(JsonNode contextHints) {
        if (contextHints == null || contextHints.isNull()) {
            return "N/A";
        }
        if (contextHints.isTextual()) {
            String text = contextHints.asText();
            return text == null || text.isBlank() ? "N/A" : text;
        }
        return contextHints.toPrettyString();
    }

    private String safeMetadata(String metadata) {
        return metadata == null || metadata.isBlank() ? "N/A" : metadata;
    }

    private String buildContextDescription(AiContextDTO context, AiSchemaContext schemaContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("Component: ").append(context.getComponentId()).append(". ");
        if (context.getResourcePath() != null) {
            sb.append("Resource: ").append(context.getResourcePath()).append(". ");
        }
        if (schemaContext != null) {
            sb.append("SchemaContext: ").append(schemaContext.getPath())
                    .append(" (").append(schemaContext.getOperation())
                    .append(" / ").append(schemaContext.getSchemaType()).append("). ");
        }
        sb.append("Modo: ").append(context.getAiMode()).append(".");
        return sb.toString();
    }

    private String truncateBlock(String label, String value, int maxChars) {
        String safeValue = value != null ? value : "";
        int originalChars = safeValue.length();
        if (maxChars <= 0) {
            logBlockSize(label, 0, originalChars, true);
            return "";
        }
        if (originalChars <= maxChars) {
            logBlockSize(label, originalChars, originalChars, false);
            return safeValue;
        }
        String marker = "\n... [truncated] ...\n";
        int head = (int) Math.round(maxChars * 0.7d);
        int tail = maxChars - head - marker.length();
        if (tail < 0) {
            head = Math.max(0, maxChars - marker.length());
            tail = 0;
        }
        String prefix = safeValue.substring(0, Math.min(head, safeValue.length()));
        String suffix = tail > 0 ? safeValue.substring(Math.max(safeValue.length() - tail, 0)) : "";
        String truncated = prefix + marker + suffix;
        logBlockSize(label, truncated.length(), originalChars, true);
        return truncated;
    }

    private void logBlockSize(String label, int finalChars, int originalChars, boolean truncated) {
        int estimatedTokens = estimateTokens(finalChars);
        if (truncated) {
            log.info("[AiOrchestratorService] Prompt block '{}' truncated: chars {} -> {}, tokens~{}.",
                    label, originalChars, finalChars, estimatedTokens);
        } else {
            log.info("[AiOrchestratorService] Prompt block '{}' size: chars {}, tokens~{}.",
                    label, finalChars, estimatedTokens);
        }
    }

    private int estimateTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (int) Math.ceil(chars / 4.0d);
    }

    private TemplateSelection resolveTemplateVariant(
            AiOrchestratorRequest request,
            AiContextDTO context,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        List<String> warnings = new ArrayList<>();
        AiRegistryTemplateRecord baseTemplate = context.getTemplate();
        if (baseTemplate == null) {
            return new TemplateSelection(null, warnings);
        }

        String componentId = context.getComponentId();
        String requestedVariantId = request.getVariantId();
        if (requestedVariantId != null && !requestedVariantId.isBlank()) {
            String registryKey = componentId + ":" + requestedVariantId.trim();
            AiRegistryTemplateRecord variant = loadTemplateRecord(registryKey, null);
            if (variant != null) {
                return new TemplateSelection(variant, warnings);
            }
            warnings.add("Template variante nao encontrada: " + registryKey);
            return new TemplateSelection(baseTemplate, warnings);
        }

        JsonNode templateMeta = baseTemplate.getTemplateMeta();
        List<String> variantKeys = extractVariantKeys(componentId, templateMeta);
        if (!variantKeys.isEmpty()) {
            int limit = Math.max(DEFAULT_VARIANT_SEARCH_LIMIT, variantKeys.size());
            List<AiRegistryTemplateSearchResult> results =
                    templateService.searchTemplatesByPrefix(
                            request.getUserPrompt(),
                            componentId + ":",
                            limit,
                            embeddingConfig);
            AiRegistryTemplateSearchResult selected = selectVariantCandidate(results, variantKeys);
            if (selected != null && selected.getSimilarityScore() >= VARIANT_SIMILARITY_THRESHOLD) {
                AiRegistryTemplateRecord variant = loadTemplateRecord(selected.getComponentId(), warnings);
                if (variant != null) {
                    return new TemplateSelection(variant, warnings);
                }
            }
        }

        String defaultVariantId = templateMeta != null ? textOrNull(templateMeta.get("defaultVariantId")) : null;
        if (defaultVariantId != null) {
            String registryKey = componentId + ":" + defaultVariantId;
            AiRegistryTemplateRecord variant = loadTemplateRecord(registryKey, warnings);
            if (variant != null) {
                return new TemplateSelection(variant, warnings);
            }
        }

        return new TemplateSelection(baseTemplate, warnings);
    }

    private AiRegistryTemplateRecord loadTemplateRecord(String registryKey, List<String> warnings) {
        if (registryKey == null || registryKey.isBlank()) {
            return null;
        }
        Optional<AiRegistry> found = templateService.getTemplate(registryKey);
        if (found.isEmpty()) {
            if (warnings != null) {
                warnings.add("Template nao encontrado: " + registryKey);
            }
            return null;
        }
        return templateService.toRecord(found.get());
    }

    private List<String> extractVariantKeys(String componentId, JsonNode templateMeta) {
        if (templateMeta == null || !templateMeta.has("variants") || !templateMeta.get("variants").isArray()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode variant : templateMeta.get("variants")) {
            if (variant == null || variant.isNull()) continue;
            String registryKey = textOrNull(variant.get("registryKey"));
            if (registryKey == null) {
                String id = textOrNull(variant.get("id"));
                if (id == null) {
                    id = textOrNull(variant.get("variantId"));
                }
                if (id != null) {
                    registryKey = componentId + ":" + id;
                }
            }
            if (registryKey != null && !registryKey.isBlank()) {
                keys.add(registryKey);
            }
        }
        return keys;
    }

    private AiRegistryTemplateSearchResult selectVariantCandidate(
            List<AiRegistryTemplateSearchResult> results,
            List<String> allowedKeys) {
        if (results == null || results.isEmpty()) return null;
        if (allowedKeys == null || allowedKeys.isEmpty()) {
            return results.get(0);
        }
        for (AiRegistryTemplateSearchResult result : results) {
            if (result != null && allowedKeys.contains(result.getComponentId())) {
                return result;
            }
        }
        return null;
    }

    private List<AiCapability> extractCapabilities(JsonNode componentDefinition) {
        if (componentDefinition == null) return Collections.emptyList();
        JsonNode jsonSchema = componentDefinition.get("jsonSchema");
        JsonNode capsNode = jsonSchema != null ? jsonSchema.get("capabilities") : null;
        if (capsNode == null || !capsNode.isArray()) {
            JsonNode directCaps = componentDefinition.get("capabilities");
            if (directCaps == null || !directCaps.isArray()) return Collections.emptyList();
            capsNode = directCaps;
        }
        return objectMapper.convertValue(capsNode, new TypeReference<List<AiCapability>>() {});
    }

    private List<AiCapability> extractComponentCapabilities(JsonNode componentDefinition) {
        if (componentDefinition == null) return Collections.emptyList();
        JsonNode jsonSchema = componentDefinition.get("jsonSchema");
        JsonNode capsNode = jsonSchema != null ? jsonSchema.get("componentCapabilities") : null;
        if (capsNode == null || !capsNode.isArray()) {
            JsonNode directCaps = componentDefinition.get("componentCapabilities");
            if (directCaps == null || !directCaps.isArray()) return Collections.emptyList();
            capsNode = directCaps;
        }
        return objectMapper.convertValue(capsNode, new TypeReference<List<AiCapability>>() {});
    }

    private List<String> extractComponentCapabilityNotes(JsonNode componentDefinition) {
        if (componentDefinition == null) return Collections.emptyList();
        JsonNode jsonSchema = componentDefinition.get("jsonSchema");
        JsonNode notesNode = jsonSchema != null ? jsonSchema.get("componentCapabilityNotes") : null;
        if (notesNode == null || notesNode.isNull()) {
            notesNode = componentDefinition.get("componentCapabilityNotes");
        }
        if (notesNode == null || !notesNode.isArray()) {
            return Collections.emptyList();
        }
        return objectMapper.convertValue(notesNode, new TypeReference<List<String>>() {});
    }

    private JsonNode extractComponentContextPack(JsonNode componentDefinition) {
        if (componentDefinition == null) {
            return null;
        }
        JsonNode jsonSchema = componentDefinition.get("jsonSchema");
        JsonNode contextNode = jsonSchema != null ? jsonSchema.get("componentContext") : null;
        if (contextNode == null || contextNode.isNull()) {
            contextNode = componentDefinition.get("componentContext");
        }
        return contextNode != null && !contextNode.isNull() ? contextNode : null;
    }

    private List<ComponentAction> extractComponentActions(JsonNode componentContext) {
        List<ComponentAction> actions = new ArrayList<>();
        if (componentContext == null) {
            return actions;
        }
        JsonNode catalog = componentContext.get("actionCatalog");
        if (catalog == null || !catalog.isArray()) {
            return actions;
        }
        for (JsonNode item : catalog) {
            String id = textOrNull(item.get("id"));
            if (id == null) continue;
            List<String> keywords = new ArrayList<>();
            JsonNode kwNode = item.get("keywords");
            if (kwNode != null && kwNode.isArray()) {
                kwNode.forEach(k -> {
                    String val = k.asText();
                    if (val != null && !val.isBlank()) {
                        keywords.add(val);
                    }
                });
            }
            JsonNode patchTemplate = item.get("patchTemplate");
            String scope = textOrNull(item.get("scope"));
            String valueType = textOrNull(item.get("valueType"));
            JsonNode defaultValue = item.get("defaultValue");
            String operation = textOrNull(item.get("operation"));
            Boolean requiresExistingTarget = item.has("requiresExistingTarget")
                    ? item.get("requiresExistingTarget").asBoolean()
                    : null;
            List<String> relatedConcepts = new ArrayList<>();
            JsonNode relatedNode = item.get("relatedConcepts");
            if (relatedNode != null && relatedNode.isArray()) {
                relatedNode.forEach(node -> {
                    String val = node.asText();
                    if (val != null && !val.isBlank()) {
                        relatedConcepts.add(val);
                    }
                });
            }

            List<ActionParam> params = new ArrayList<>();
            JsonNode paramsNode = item.get("params");
            if (paramsNode != null && paramsNode.isArray()) {
                for (JsonNode p : paramsNode) {
                    String pName = textOrNull(p.get("name"));
                    if (pName == null) continue;
                    String pType = textOrNull(p.get("type"));
                    String pDesc = textOrNull(p.get("description"));
                    List<String> pOptions = new ArrayList<>();
                    JsonNode opts = p.get("options");
                    if (opts != null && opts.isArray()) {
                        opts.forEach(o -> {
                            String val = o.asText();
                            if (val != null) pOptions.add(val);
                        });
                    }
                    params.add(new ActionParam(pName, pType, pOptions, pDesc));
                }
            }

            actions.add(new ComponentAction(
                    id,
                    keywords,
                    patchTemplate,
                    scope,
                    valueType,
                    defaultValue,
                    params,
                    relatedConcepts,
                    operation,
                    requiresExistingTarget));
        }
        return actions;
    }

    private Map<String, JsonNode> extractAiConcepts(JsonNode componentDefinition) {
        if (componentDefinition == null) {
            return Collections.emptyMap();
        }
        JsonNode jsonSchema = componentDefinition.get("jsonSchema");
        JsonNode conceptsNode = jsonSchema != null ? jsonSchema.get("aiConcepts") : null;
        if (conceptsNode == null || conceptsNode.isNull()) {
            conceptsNode = componentDefinition.get("aiConcepts");
        }
        if (conceptsNode == null || !conceptsNode.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, JsonNode> out = new LinkedHashMap<>();
        conceptsNode.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }

    private List<String> resolveRelatedConceptIds(
            List<ComponentAction> actions,
            AiActionPlan actionPlan,
            List<AiActionItem> expectedActions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        Map<String, ComponentAction> byId = new LinkedHashMap<>();
        for (ComponentAction action : actions) {
            if (action != null && action.id != null) {
                byId.put(action.id, action);
            }
        }
        Set<String> related = new LinkedHashSet<>();
        if (expectedActions != null && !expectedActions.isEmpty()) {
            for (AiActionItem item : expectedActions) {
                if (item == null || item.getType() == null) continue;
                ComponentAction action = byId.get(item.getType());
                if (action == null || action.relatedConcepts == null) continue;
                related.addAll(action.relatedConcepts);
            }
        } else if (actionPlan != null && actionPlan.getActions() != null) {
            for (AiActionPlan.Action item : actionPlan.getActions()) {
                if (item == null || item.getType() == null) continue;
                ComponentAction action = byId.get(item.getType());
                if (action == null || action.relatedConcepts == null) continue;
                related.addAll(action.relatedConcepts);
            }
        }
        return related.isEmpty() ? List.of() : new ArrayList<>(related);
    }

    private String formatConceptsForPrompt(
            Map<String, JsonNode> conceptsById,
            List<String> conceptIds) {
        if (conceptsById == null || conceptsById.isEmpty()
                || conceptIds == null || conceptIds.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String id : conceptIds) {
            JsonNode concept = conceptsById.get(id);
            if (concept == null || !concept.isObject()) {
                continue;
            }
            String title = textOrNull(concept.get("title"));
            String description = textOrNull(concept.get("description"));
            out.append("[").append(id).append("]");
            if (title != null && !title.isBlank()) {
                out.append(" ").append(title);
            }
            out.append('\n');
            if (description != null && !description.isBlank()) {
                out.append(description).append('\n');
            }
            appendListBlock(out, "Rules", concept.get("rules"), 4);
            String syntax = textOrNull(concept.get("syntax"));
            if (syntax != null && !syntax.isBlank()) {
                out.append("Syntax: ").append(syntax).append('\n');
            }
            appendListBlock(out, "Examples", concept.get("examples"), 3);
            appendListBlock(out, "AntiPatterns", concept.get("antiPatterns"), 2);
            out.append('\n');
        }
        return out.toString().trim();
    }

    private List<String> resolveMissingConceptIds(
            Map<String, JsonNode> conceptsById,
            List<String> conceptIds) {
        if (conceptIds == null || conceptIds.isEmpty()) {
            return List.of();
        }
        if (conceptsById == null || conceptsById.isEmpty()) {
            return new ArrayList<>(conceptIds);
        }
        List<String> missing = new ArrayList<>();
        for (String id : conceptIds) {
            if (id != null && !id.isBlank() && !conceptsById.containsKey(id)) {
                missing.add(id);
            }
        }
        return missing.isEmpty() ? List.of() : missing;
    }

    private void appendListBlock(StringBuilder out, String label, JsonNode items, int limit) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return;
        }
        out.append(label).append(":\n");
        int count = 0;
        for (JsonNode item : items) {
            if (count >= limit) break;
            if (item == null || !item.isTextual()) continue;
            out.append("- ").append(item.asText()).append('\n');
            count++;
        }
    }

    private Set<String> resolveAllowedActionTypes(List<ComponentAction> actions, boolean normalizeTableTypes) {
        if (actions == null || actions.isEmpty()) {
            return Set.of();
        }
        Set<String> allowed = new java.util.LinkedHashSet<>();
        for (ComponentAction action : actions) {
            if (action == null || action.id == null || action.id.isBlank()) continue;
            String id = action.id.trim();
            allowed.add(normalizeTableTypes ? id.toUpperCase(Locale.ROOT) : id);
        }
        return allowed;
    }

    private String normalizeCatalogActionType(String actionId, Set<String> allowedActionTypes) {
        if (actionId == null || actionId.isBlank()) {
            return null;
        }
        if (allowedActionTypes == null || allowedActionTypes.isEmpty()) {
            return null;
        }
        String normalized = actionId.trim().toUpperCase(Locale.ROOT);
        return allowedActionTypes.contains(normalized) ? normalized : null;
    }

    private List<String> resolveFieldResolverKeys(JsonNode componentContext, String path) {
        if (componentContext == null || path == null || path.isBlank()) {
            return List.of();
        }
        JsonNode resolvers = componentContext.get("fieldResolvers");
        if (resolvers == null || !resolvers.isObject()) {
            return List.of();
        }
        JsonNode keysNode = resolvers.get(path);
        return parseResolverKeys(keysNode, true);
    }

    private List<String> parseResolverKeys(JsonNode keysNode, boolean normalize) {
        if (keysNode == null || keysNode.isNull()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        if (keysNode.isArray()) {
            for (JsonNode item : keysNode) {
                String key = item != null ? item.asText() : null;
                if (key != null && !key.isBlank()) {
                    keys.add(normalize ? key.trim().toLowerCase(Locale.ROOT) : key.trim());
                }
            }
        } else if (keysNode.isTextual()) {
            String key = keysNode.asText();
            if (key != null && !key.isBlank()) {
                keys.add(normalize ? key.trim().toLowerCase(Locale.ROOT) : key.trim());
            }
        }
        return keys;
    }

    private List<ContextOption> resolveOptionsForPath(
            JsonNode componentContext,
            List<AiCapability> capabilities,
            String path) {
        List<ContextOption> options = extractContextOptions(componentContext, path);
        if (options.isEmpty()) {
            options = fallbackOptionsFromCapabilities(capabilities, path);
        }
        return options;
    }

    private List<ContextOption> extractContextOptions(JsonNode componentContext, String path) {
        if (componentContext == null || path == null || path.isBlank()) {
            return List.of();
        }
        JsonNode optionsByPath = componentContext.get("optionsByPath");
        if (optionsByPath == null || !optionsByPath.isObject()) {
            return List.of();
        }
        JsonNode pathNode = optionsByPath.get(path);
        if (pathNode == null || pathNode.isNull()) {
            return List.of();
        }
        JsonNode optionsNode = pathNode.get("options");
        if (optionsNode == null || optionsNode.isNull()) {
            optionsNode = pathNode;
        }
        return parseOptionsArray(optionsNode);
    }

    private List<ContextOption> fallbackOptionsFromCapabilities(
            List<AiCapability> capabilities,
            String path) {
        if (capabilities == null || capabilities.isEmpty() || path == null || path.isBlank()) {
            return List.of();
        }
        for (AiCapability cap : capabilities) {
            if (cap == null || cap.getPath() == null) continue;
            if (!path.equals(cap.getPath())) continue;
            List<Object> allowedValues = cap.getAllowedValues();
            if (allowedValues == null || allowedValues.isEmpty()) {
                return List.of();
            }
            List<ContextOption> options = new ArrayList<>();
            for (Object value : allowedValues) {
                if (value == null) continue;
                options.add(new ContextOption(String.valueOf(value), null, null));
            }
            return options;
        }
        return List.of();
    }

    private List<ContextOption> parseOptionsArray(JsonNode optionsNode) {
        if (optionsNode == null || !optionsNode.isArray()) {
            return List.of();
        }
        List<ContextOption> options = new ArrayList<>();
        for (JsonNode optionNode : optionsNode) {
            if (optionNode == null || optionNode.isNull()) continue;
            if (optionNode.isTextual() || optionNode.isNumber()) {
                options.add(new ContextOption(optionNode.asText(), null, null));
                continue;
            }
            if (!optionNode.isObject()) {
                continue;
            }
            String value = textOrNull(optionNode.get("value"));
            String label = textOrNull(optionNode.get("label"));
            String example = textOrNull(optionNode.get("example"));
            if ((value == null || value.isBlank())
                    && (label == null || label.isBlank())) {
                continue;
            }
            options.add(new ContextOption(value, label, example));
        }
        return options;
    }

    private List<String> buildOptionLabels(List<ContextOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (ContextOption option : options) {
            if (option == null) continue;
            String value = option.value;
            String label = option.label;
            if (label != null && !label.isBlank() && value != null && !value.isBlank()
                    && !label.equalsIgnoreCase(value)) {
                out.add(label + " (" + value + ")");
            } else if (label != null && !label.isBlank()) {
                out.add(label);
            } else if (value != null && !value.isBlank()) {
                out.add(value);
            }
        }
        return new ArrayList<>(out);
    }

    private List<AiOption> buildOptionPayloads(List<ContextOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<AiOption> out = new ArrayList<>();
        for (ContextOption option : options) {
            if (option == null) continue;
            String value = option.value != null ? option.value : option.label;
            String label = option.label != null ? option.label : option.value;
            if (value == null || value.isBlank()) {
                continue;
            }
            out.add(AiOption.builder()
                    .value(value)
                    .label(label != null && !label.isBlank() ? label : value)
                    .example(option.example)
                    .build());
        }
        return out;
    }

    private List<AiCapability> selectCapabilitiesByScope(
            String scope,
            List<AiCapability> configCapabilities,
            List<AiCapability> componentCapabilities) {
        String normalizedScope = normalizeScope(scope);
        if ("component".equals(normalizedScope)) {
            return componentCapabilities != null ? componentCapabilities : Collections.emptyList();
        }
        if ("mixed".equals(normalizedScope)) {
            return mergeCapabilities(configCapabilities, componentCapabilities);
        }
        return configCapabilities != null ? configCapabilities : Collections.emptyList();
    }

    private List<AiCapability> mergeCapabilities(
            List<AiCapability> primary,
            List<AiCapability> secondary) {
        List<AiCapability> result = new ArrayList<>();
        if (primary == null && secondary == null) return result;
        List<String> seen = new ArrayList<>();
        if (primary != null) {
            for (AiCapability cap : primary) {
                result.add(cap);
                String path = cap != null ? cap.getPath() : null;
                if (path != null && !path.isBlank()) {
                    seen.add(path);
                }
            }
        }
        if (secondary != null) {
            for (AiCapability cap : secondary) {
                String path = cap != null ? cap.getPath() : null;
                if (path != null && !path.isBlank() && seen.contains(path)) {
                    continue;
                }
                result.add(cap);
            }
        }
        return result;
    }

    private List<AiCapability> filterCapabilities(
            String componentId,
            String category,
            String scope,
            List<AiCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return Collections.emptyList();
        if (category == null || category.isBlank() || "unknown".equalsIgnoreCase(category)) {
            return capabilities;
        }
        String normalizedScope = normalizeScope(scope);
        if ("component".equals(normalizedScope)) {
            return capabilities.stream()
                    .filter(c -> category.equalsIgnoreCase(c.getCategory()))
                    .collect(Collectors.toList());
        }

        if ("praxis-table".equals(componentId)) {
            Map<String, List<String>> categoryMap = new LinkedHashMap<>();
            categoryMap.put("columns", List.of("columns", "format", "mapping", "renderer", "conditional"));
            categoryMap.put("appearance", List.of("appearance", "conditional"));
            categoryMap.put("conditional", List.of("conditional", "renderer", "columns"));
            categoryMap.put("behavior", List.of("behavior", "pagination", "sorting", "filtering", "selection", "interaction"));
            categoryMap.put("actions", List.of("actions", "toolbar", "export"));

            List<String> targets = categoryMap.getOrDefault(category, List.of(category));
            return capabilities.stream()
                    .filter(c -> c.getCategory() != null && targets.contains(c.getCategory()))
                    .collect(Collectors.toList());
        }

        return capabilities.stream()
                .filter(c -> category.equalsIgnoreCase(c.getCategory()))
                .collect(Collectors.toList());
    }

    private JsonNode extractContextForIntent(
            String componentId, JsonNode fullConfig, AiIntentClassification intent) {
        if (!"praxis-table".equals(componentId) || fullConfig == null || !fullConfig.isObject()) {
            return fullConfig;
        }

        String category = intent.getCategory();
        String targetField = intent.getTargetField();
        ObjectNode out = objectMapper.createObjectNode();

        if (("columns".equals(category) || "conditional".equals(category)) && targetField != null) {
            JsonNode cols = fullConfig.get("columns");
            if (cols != null && cols.isArray()) {
                for (JsonNode col : cols) {
                    if (targetField.equals(textOrNull(col.get("field")))) {
                        ArrayNode arr = objectMapper.createArrayNode();
                        arr.add(col);
                        out.set("columns", arr);
                        return out;
                    }
                }
            }
        }

        if ("behavior".equals(category) && fullConfig.has("behavior")) {
            out.set("behavior", fullConfig.get("behavior"));
            return out;
        }
        if ("appearance".equals(category) && fullConfig.has("appearance")) {
            out.set("appearance", fullConfig.get("appearance"));
            return out;
        }
        if ("actions".equals(category) && (fullConfig.has("actions") || fullConfig.has("toolbar"))) {
            if (fullConfig.has("actions")) out.set("actions", fullConfig.get("actions"));
            if (fullConfig.has("toolbar")) out.set("toolbar", fullConfig.get("toolbar"));
            return out;
        }

        return fullConfig;
    }

    private JsonNode resolveContextForScope(
            String scope,
            String componentId,
            JsonNode currentState,
            AiIntentClassification intent) {
        String normalizedScope = normalizeScope(scope);
        if ("component".equals(normalizedScope)) {
            return extractComponentContext(currentState);
        }
        if ("mixed".equals(normalizedScope)) {
            return currentState;
        }
        return extractContextForIntent(componentId, currentState, intent);
    }

    private JsonNode extractComponentContext(JsonNode currentState) {
        if (currentState == null || !currentState.isObject()) return currentState;
        ObjectNode out = objectMapper.createObjectNode();
        JsonNode inputs = currentState.get("inputs");
        if (inputs != null && !inputs.isNull()) {
            out.set("inputs", inputs);
        }
        JsonNode outputs = currentState.get("outputs");
        if (outputs != null && !outputs.isNull()) {
            out.set("outputs", outputs);
        }
        if (out.size() == 0) {
            return currentState;
        }
        return out;
    }

    private List<ColumnDescriptor> extractColumnDescriptors(JsonNode currentState) {
        if (currentState == null || !currentState.has("columns")) return List.of();
        JsonNode cols = currentState.get("columns");
        if (!cols.isArray()) return List.of();
        List<ColumnDescriptor> out = new ArrayList<>();
        for (JsonNode col : cols) {
            String field = textOrNull(col.get("field"));
            if (field == null || field.isBlank()) continue;
            String header = textOrNull(col.get("header"));
            out.add(new ColumnDescriptor(field, header));
        }
        return out;
    }

    private List<String> extractColumnNames(List<ColumnDescriptor> columns) {
        if (columns == null || columns.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (ColumnDescriptor column : columns) {
            if (column == null || column.field == null || column.field.isBlank()) continue;
            names.add(column.field);
        }
        return names;
    }

    private List<String> buildColumnOptions(List<ColumnDescriptor> columns) {
        if (columns == null || columns.isEmpty()) return List.of();
        Map<String, Integer> headerCounts = countHeaders(columns);
        List<String> options = new ArrayList<>();
        for (ColumnDescriptor column : columns) {
            if (column == null || column.field == null || column.field.isBlank()) continue;
            options.add(displayColumnLabel(column, headerCounts));
        }
        return options;
    }

    private Map<String, Integer> countHeaders(List<ColumnDescriptor> columns) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (columns == null) return counts;
        for (ColumnDescriptor column : columns) {
            if (column == null || column.header == null || column.header.isBlank()) continue;
            String headerKey = column.header.trim();
            counts.put(headerKey, counts.getOrDefault(headerKey, 0) + 1);
        }
        return counts;
    }

    private String displayColumnLabel(ColumnDescriptor column, Map<String, Integer> headerCounts) {
        if (column == null || column.field == null || column.field.isBlank()) return "";
        String header = column.header != null ? column.header.trim() : "";
        if (header.isBlank()) {
            return column.field;
        }
        boolean duplicated = headerCounts != null && headerCounts.getOrDefault(header, 0) > 1;
        return duplicated ? header + " (" + column.field + ")" : header;
    }

    private List<String> extractColumnNames(JsonNode currentState) {
        if (currentState == null || !currentState.has("columns")) return List.of();
        JsonNode cols = currentState.get("columns");
        if (!cols.isArray()) return List.of();
        List<String> names = new ArrayList<>();
        for (JsonNode col : cols) {
            String field = textOrNull(col.get("field"));
            if (field != null && !field.isBlank()) {
                names.add(field);
            }
        }
        return names;
    }

    private List<String> extractObjectKeys(JsonNode currentState, String field) {
        if (currentState == null || field == null || field.isBlank()) return List.of();
        JsonNode node = currentState.get(field);
        if (node == null || !node.isObject()) return List.of();
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private List<String> extractCategoryNames(List<AiCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return List.of();
        List<String> categories = new ArrayList<>();
        for (AiCapability cap : capabilities) {
            if (cap == null) continue;
            String category = cap.getCategory();
            if (category == null || category.isBlank()) continue;
            String normalized = category.trim().toLowerCase();
            if (!categories.contains(normalized)) {
                categories.add(normalized);
            }
        }
        return categories;
    }

    private String formatCapabilityNotes(List<String> notes) {
        if (notes == null || notes.isEmpty()) return "";
        return notes.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> "- " + n)
                .collect(Collectors.joining("\n"));
    }

    private String formatCapabilities(List<AiCapability> caps) {
        if (caps == null || caps.isEmpty()) return "Todas as configurações permitidas.";
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (AiCapability c : caps) {
            String cat = c.getCategory() != null ? c.getCategory() : "Geral";
            groups.putIfAbsent(cat, new ArrayList<>());
            StringBuilder line = new StringBuilder("- ").append(c.getPath());
            if (c.getAllowedValues() != null && !c.getAllowedValues().isEmpty()) {
                String values = c.getAllowedValues().stream().map(String::valueOf).collect(Collectors.joining(", "));
                line.append(" (Enum: ").append(values).append(")");
            } else if (c.getValueKind() != null) {
                line.append(" (").append(c.getValueKind()).append(")");
            }
            if (c.getDescription() != null) {
                line.append(": ").append(c.getDescription());
            }
            if (c.getSafetyNotes() != null && !c.getSafetyNotes().isBlank()) {
                line.append(" [SAFETY: ").append(c.getSafetyNotes()).append("]");
            }
            groups.get(cat).add(line.toString());
        }
        return groups.entrySet().stream()
                .map(e -> "### " + e.getKey().toUpperCase() + "\n" + String.join("\n", e.getValue()))
                .collect(Collectors.joining("\n\n"));
    }

    private String summarizeCapabilityPaths(List<AiCapability> caps, int limit) {
        if (caps == null || caps.isEmpty()) {
            return "[]";
        }
        int effectiveLimit = Math.max(1, limit);
        List<String> paths = new ArrayList<>();
        for (AiCapability cap : caps) {
            if (cap == null || cap.getPath() == null || cap.getPath().isBlank()) {
                continue;
            }
            paths.add(cap.getPath());
            if (paths.size() >= effectiveLimit) {
                break;
            }
        }
        String suffix = caps.size() > paths.size() ? ", ..." : "";
        return "[" + String.join(", ", paths) + suffix + "]";
    }

    private SchemaResolution resolveSchema(
            AiOrchestratorRequest request,
            AiContextDTO context,
            String requestBaseUrl,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        AiSchemaContext schemaContext = request.getSchemaContext();
        String resolvedResourcePath = request.getResourcePath() != null
                ? request.getResourcePath()
                : context.getResourcePath();
        if (schemaContext == null && resolvedResourcePath != null) {
            schemaContext = AiSchemaContext.builder()
                    .path(resolvedResourcePath)
                    .operation(defaultOperation(request.getComponentId()))
                    .schemaType(defaultSchemaType(request.getComponentId()))
                    .build();
        }

        if (schemaContext == null) {
            List<ApiSearchResult> results = retrievalService.searchApiMetadata(
                    request.getUserPrompt(),
                    request.getApiMethod(),
                    request.getApiTags(),
                    request.getApiSearchLimit() != null ? request.getApiSearchLimit() : DEFAULT_API_SEARCH_LIMIT,
                    embeddingConfig);

            if (results.isEmpty()) {
                return new SchemaResolution(error("Nenhum endpoint encontrado para o contexto solicitado."));
            }
            if (results.size() > 1) {
                List<AiOption> optionPayloads = buildApiOptionPayloads(results);
                List<String> labels = optionPayloads.stream()
                        .map(AiOption::getLabel)
                        .filter(label -> label != null && !label.isBlank())
                        .collect(Collectors.toList());
                return new SchemaResolution(clarification(
                        buildResourceClarificationMessage(request.getComponentId()),
                        labels,
                        optionPayloads));
            }
            ApiSearchResult picked = results.get(0);
            schemaContext = AiSchemaContext.builder()
                    .path(picked.getPath())
                    .operation(picked.getMethod())
                    .schemaType(defaultSchemaType(request.getComponentId()))
                    .build();
            if (request.getResourcePath() == null || request.getResourcePath().isBlank()) {
                request.setResourcePath(picked.getPath());
            }
        }

        JsonNode schema = schemaRetrievalService.fetchSchema(schemaContext, requestBaseUrl);
        if (schema == null) {
            return new SchemaResolution(error("Não foi possível carregar o schema informado."));
        }
        return new SchemaResolution(schemaContext, schema);
    }

    private String defaultSchemaType(String componentId) {
        if (componentId != null && componentId.contains("table")) return "response";
        return "request";
    }

    private String defaultOperation(String componentId) {
        if (componentId != null && componentId.contains("table")) return "get";
        return "post";
    }

    private AiOrchestratorResponse tryApplyContextHintPatch(
            JsonNode contextHints,
            JsonNode currentState,
            String componentId,
            List<String> warnings,
            List<AiCapability> configCapabilities,
            List<AiCapability> componentCapabilities,
            JsonNode componentContext) {
        if (contextHints == null || contextHints.isNull()) {
            return null;
        }
        JsonNode patch = buildPatchFromContextHints(contextHints, componentId);
        if (patch == null || patch.isNull()) {
            return null;
        }
        return applySuggestedPatch(
                patch,
                currentState,
                componentId,
                warnings,
                configCapabilities,
                componentCapabilities,
                componentContext);
    }

    private JsonNode buildPatchFromContextHints(JsonNode contextHints, String componentId) {
        if (!COMPONENT_ID_TABLE.equals(componentId) || contextHints == null || !contextHints.isObject()) {
            return null;
        }
        JsonNode badgeHints = resolveBadgeHintsNode(contextHints);
        if (badgeHints != null) {
            JsonNode patch = buildBadgePatchFromHints(badgeHints);
            if (patch != null) {
                return patch;
            }
        }
        JsonNode computedHints = resolveComputedHintsNode(contextHints);
        if (computedHints != null) {
            return buildComputedPatchFromHints(computedHints);
        }
        return null;
    }

    private JsonNode resolveBadgeHintsNode(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return null;
        }
        JsonNode badge = contextHints.get("badge");
        if (badge != null && badge.isObject()) {
            return badge;
        }
        if (contextHints.has("values") || contextHints.has("valueColorMap") || contextHints.has("palette")) {
            return contextHints;
        }
        return null;
    }

    private JsonNode resolveComputedHintsNode(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return null;
        }
        JsonNode computed = contextHints.get("computed");
        if (computed != null && computed.isObject()) {
            return computed;
        }
        return null;
    }

    private JsonNode buildBadgePatchFromHints(JsonNode badgeHints) {
        if (badgeHints == null || !badgeHints.isObject()) {
            return null;
        }
        String field = textOrNull(badgeHints.get("field"));
        List<String> values = readStringArray(badgeHints.get("values"));
        Map<String, String> colorMap = readStringMap(badgeHints.get("valueColorMap"));
        if ((values == null || values.isEmpty()) && colorMap != null && !colorMap.isEmpty()) {
            values = new ArrayList<>(colorMap.keySet());
        }
        values = limitBadgeValues(values);
        if (isBlank(field) || values.isEmpty()) {
            return null;
        }
        List<String> palette = readStringArray(badgeHints.get("palette"));
        String inferredType = textOrNull(badgeHints.get("inferredType"));
        String explicitType = textOrNull(badgeHints.get("explicitType"));
        boolean isBoolean = isBooleanType(inferredType, explicitType) || looksLikeBooleanValues(values);
        boolean quoteValues = !isBoolean;
        Map<String, String> resolved = assignColors(values, palette);
        if (colorMap != null && !colorMap.isEmpty()) {
            for (Map.Entry<String, String> entry : colorMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().trim();
                if (key.isEmpty()) {
                    continue;
                }
                resolved.put(key, entry.getValue());
            }
        }
        return buildConditionalBadgePatch(field, resolved, quoteValues);
    }

    private JsonNode buildComputedPatchFromHints(JsonNode computedHints) {
        if (computedHints == null || !computedHints.isObject()) {
            return null;
        }
        String field = textOrNull(computedHints.get("field"));
        String expression = textOrNull(computedHints.get("expression"));
        if (isBlank(field) || isBlank(expression)) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.length() > 200) {
            return null;
        }
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", field);
        ObjectNode computed = column.putObject("computed");
        computed.put("expression", trimmed);
        String outputType = textOrNull(computedHints.get("outputType"));
        if (!isBlank(outputType)) {
            computed.put("outputType", outputType);
        }
        String format = textOrNull(computedHints.get("format"));
        if (!isBlank(format)) {
            computed.put("format", format);
        }
        JsonNode depsNode = computedHints.get("dependencies");
        if (depsNode != null && depsNode.isArray()) {
            ArrayNode deps = computed.putArray("dependencies");
            for (JsonNode dep : depsNode) {
                if (dep == null || dep.isNull()) {
                    continue;
                }
                String value = dep.asText();
                if (value != null && !value.isBlank()) {
                    deps.add(value);
                }
            }
        }
        return patch;
    }

    private AiOrchestratorResponse tryResolveRendererRuleFallback(
            AiOrchestratorRequest request,
            JsonNode currentState,
            List<String> warnings,
            List<AiCapability> configCapabilities,
            List<AiCapability> componentCapabilities,
            JsonNode componentContext) {
        if (request == null || request.getUserPrompt() == null) {
            return null;
        }
        String prompt = request.getUserPrompt();
        String promptLower = prompt.toLowerCase(Locale.ROOT);
        if (!COMPONENT_ID_TABLE.equals(request.getComponentId())) {
            return null;
        }

        if (promptLower.contains("badge") || promptLower.contains("chip")) {
            String field = inferFieldFromPrompt(promptLower, currentState, request.getSchemaFields(),
                    "status", "priority");
            if (!isBlank(field)) {
                BadgeValuesContext ctx = resolveBadgeValuesContext(request, field);
                List<String> values = ctx != null ? ctx.values : List.of();
                if (values == null || values.isEmpty()) {
                    values = extractValuesFromPrompt(prompt);
                }
                if ((values == null || values.isEmpty()) && "status".equalsIgnoreCase(field)) {
                    values = List.of("ERROR", "SUCCESS", "PENDING");
                }
                if ((values == null || values.isEmpty()) && "priority".equalsIgnoreCase(field)) {
                    values = List.of("HIGH", "MEDIUM", "LOW");
                }
                values = limitBadgeValues(values);
                Map<String, String> colorMap = buildDefaultColorMap(values, promptLower);
                boolean quoteValues = true;
                if (ctx != null) {
                    quoteValues = !isBooleanType(ctx.inferredType, ctx.explicitType);
                }
                JsonNode patch = promptLower.contains("chip")
                        ? buildConditionalChipPatch(field, colorMap, quoteValues)
                        : buildConditionalBadgePatch(field, colorMap, quoteValues);
                if (patch != null) {
                    warnings.add("Patch deterministico aplicado para renderer badge/chip.");
                    return applySuggestedPatch(
                            patch,
                            currentState,
                            request.getComponentId(),
                            warnings,
                            configCapabilities,
                            componentCapabilities,
                            componentContext);
                }
            }
        }

        if (promptLower.contains("icon") || promptLower.contains("icone")) {
            String field = inferFieldFromPrompt(promptLower, currentState, request.getSchemaFields(), "status");
            if (!isBlank(field)) {
                String value = promptLower.contains("error") || promptLower.contains("erro") ? "ERROR" : null;
                String color = promptLower.contains("red") || promptLower.contains("vermelh") ? "warn" : "primary";
                String iconName = promptLower.contains("alerta") || promptLower.contains("warning")
                        || promptLower.contains("erro") || promptLower.contains("error")
                        ? "error"
                        : "check";
                JsonNode patch = buildConditionalIconPatch(field, value, iconName, color);
                if (patch != null) {
                    warnings.add("Patch deterministico aplicado para renderer icon.");
                    return applySuggestedPatch(
                            patch,
                            currentState,
                            request.getComponentId(),
                            warnings,
                            configCapabilities,
                            componentCapabilities,
                            componentContext);
                }
            }
        }

        if (promptLower.contains("condicional")
                || promptLower.contains(">")
                || promptLower.contains("<")
                || promptLower.contains("quando")
                || promptLower.contains("when")) {
            String field = inferFieldFromPrompt(promptLower, currentState, request.getSchemaFields(), "score");
            if (!isBlank(field)) {
                String condition = extractNumericCondition(promptLower, field);
                Map<String, String> style = extractStyleFromPrompt(promptLower);
                JsonNode patch = null;
                if (promptLower.contains("linha") || promptLower.contains("row")) {
                    Map<String, String> rowStyle = coerceRowStyleDefaults(style);
                    patch = buildConditionalRowStylePatch(condition, rowStyle);
                }
                if (patch == null) {
                    Map<String, String> colStyle = coerceColumnStyleDefaults(style);
                    patch = buildConditionalStylePatch(field, condition, colStyle);
                }
                if (patch != null) {
                    warnings.add("Patch deterministico aplicado para estilo condicional de coluna.");
                    return applySuggestedPatch(
                            patch,
                            currentState,
                            request.getComponentId(),
                            warnings,
                            configCapabilities,
                            componentCapabilities,
                            componentContext);
                }
            }
        }
        return null;
    }

    private AiOrchestratorResponse tryResolveComputedFallback(
            AiOrchestratorRequest request,
            JsonNode currentState,
            List<String> warnings,
            List<AiCapability> configCapabilities,
            List<AiCapability> componentCapabilities,
            JsonNode componentContext) {
        if (request == null || request.getUserPrompt() == null) {
            return null;
        }
        if (!COMPONENT_ID_TABLE.equals(request.getComponentId())) {
            return null;
        }
        String prompt = request.getUserPrompt();
        String promptLower = prompt.toLowerCase(Locale.ROOT);
        if (!promptLower.contains("calculad") && !promptLower.contains("=")) {
            return null;
        }
        ComputedSpec spec = parseComputedSpec(prompt, currentState);
        if (spec == null || isBlank(spec.field) || isBlank(spec.expression)) {
            return null;
        }
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", spec.field);
        if (!isBlank(spec.header)) {
            column.put("header", spec.header);
        }
        ObjectNode computed = column.putObject("computed");
        computed.put("expression", spec.expression);
        if (!isBlank(spec.outputType)) {
            computed.put("outputType", spec.outputType);
        }
        if (!isBlank(spec.format)) {
            computed.put("format", spec.format);
        }
        if (spec.dependencies != null && !spec.dependencies.isEmpty()) {
            ArrayNode deps = computed.putArray("dependencies");
            for (String dep : spec.dependencies) {
                if (!isBlank(dep)) {
                    deps.add(dep);
                }
            }
        }
        warnings.add("Patch deterministico aplicado para coluna calculada.");
        return applySuggestedPatch(
                patch,
                currentState,
                request.getComponentId(),
                warnings,
                configCapabilities,
                componentCapabilities,
                componentContext);
    }

    private ComputedSpec parseComputedSpec(String prompt, JsonNode currentState) {
        if (prompt == null) {
            return null;
        }
        String field = null;
        String header = null;
        String expression = null;
        String outputType = null;
        String format = null;
        List<String> deps = new ArrayList<>();

        java.util.regex.Matcher eqMatcher = java.util.regex.Pattern.compile(
                "([A-Za-z0-9_\\-\\s]+)\\s*=\\s*([A-Za-z0-9_\\-\\s\\*\\/\\+\\-\\(\\)\\.]+)")
                .matcher(prompt);
        if (eqMatcher.find()) {
            String left = eqMatcher.group(1).trim();
            String right = eqMatcher.group(2).trim();
            expression = right;
            header = left;
            field = normalizeFieldName(left);
        }
        if (field == null) {
            java.util.regex.Matcher nameMatcher = java.util.regex.Pattern.compile(
                    "(coluna|column)\\s+(calculada|computed)\\s*(chamada|named)?\\s*([A-Za-z0-9_\\-]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(prompt);
            if (nameMatcher.find()) {
                field = normalizeFieldName(nameMatcher.group(4));
                header = nameMatcher.group(4);
            }
        }
        if (expression == null) {
            java.util.regex.Matcher exprMatcher = java.util.regex.Pattern.compile(
                    "([A-Za-z_][A-Za-z0-9_]*)\\s*[\\*\\+\\-/]\\s*([A-Za-z_][A-Za-z0-9_]*)")
                    .matcher(prompt);
            if (exprMatcher.find()) {
                expression = exprMatcher.group(0).trim();
            }
        }
        if (isBlank(expression)) {
            return null;
        }
        List<String> columns = extractColumnNames(currentState);
        for (String col : columns) {
            if (col != null && expression.contains(col)) {
                deps.add(col);
            }
        }
        if (prompt.toLowerCase(Locale.ROOT).contains("currency") || prompt.toLowerCase(Locale.ROOT).contains("moeda")) {
            outputType = "currency";
            format = "BRL|symbol|2";
        } else if (prompt.toLowerCase(Locale.ROOT).contains("percent")) {
            outputType = "percentage";
        } else if (prompt.toLowerCase(Locale.ROOT).contains("number")) {
            outputType = "number";
        }
        return new ComputedSpec(field, header, expression, outputType, format, deps);
    }

    private String normalizeFieldName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = trimmed.replaceAll("[^A-Za-z0-9_]", "_");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static final class ComputedSpec {
        private final String field;
        private final String header;
        private final String expression;
        private final String outputType;
        private final String format;
        private final List<String> dependencies;

        private ComputedSpec(
                String field,
                String header,
                String expression,
                String outputType,
                String format,
                List<String> dependencies) {
            this.field = field;
            this.header = header;
            this.expression = expression;
            this.outputType = outputType;
            this.format = format;
            this.dependencies = dependencies != null ? dependencies : List.of();
        }
    }

    private String inferFieldFromPrompt(
            String promptLower,
            JsonNode currentState,
            JsonNode schemaFields,
            String... preferred) {
        if (promptLower == null) {
            return null;
        }
        if (preferred != null) {
            for (String candidate : preferred) {
                if (!isBlank(candidate) && promptLower.contains(candidate.toLowerCase(Locale.ROOT))) {
                    return candidate;
                }
            }
        }
        List<String> columns = extractColumnNames(currentState);
        for (String col : columns) {
            if (!isBlank(col) && promptLower.contains(col.toLowerCase(Locale.ROOT))) {
                return col;
            }
        }
        if (columns.size() == 1) {
            return columns.get(0);
        }
        if (schemaFields != null && schemaFields.isArray()) {
            for (JsonNode field : schemaFields) {
                if (field == null || !field.isObject()) {
                    continue;
                }
                String name = textOrNull(field.get("name"));
                if (isBlank(name)) {
                    name = textOrNull(field.get("field"));
                }
                if (!isBlank(name) && promptLower.contains(name.toLowerCase(Locale.ROOT))) {
                    return name;
                }
            }
        }
        return null;
    }

    private List<String> extractValuesFromPrompt(String prompt) {
        if (prompt == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : prompt.split("[,\\s]+")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String clean = token.replaceAll("[^A-Za-z0-9_-]", "");
            if (clean.matches("^[A-Z_]{3,}$")) {
                values.add(clean);
            }
        }
        return values;
    }

    private Map<String, String> buildDefaultColorMap(List<String> values, String promptLower) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) {
            return out;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if ("ERROR".equals(normalized) || "FAILED".equals(normalized)) {
                out.put(value, "warn");
            } else if ("SUCCESS".equals(normalized) || "OK".equals(normalized)) {
                out.put(value, "success");
            } else if ("PENDING".equals(normalized)) {
                out.put(value, "accent");
            } else if ("HIGH".equals(normalized)) {
                out.put(value, "warn");
            } else if ("MEDIUM".equals(normalized)) {
                out.put(value, "accent");
            } else if ("LOW".equals(normalized)) {
                out.put(value, "success");
            }
        }
        if (out.isEmpty()) {
            out.putAll(assignColors(values, DEFAULT_BADGE_PALETTE));
        } else if (out.size() < values.size()) {
            Map<String, String> fallback = assignColors(values, DEFAULT_BADGE_PALETTE);
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                out.putIfAbsent(value, fallback.get(value));
            }
        }
        return out;
    }

    private JsonNode buildConditionalChipPatch(
            String field,
            Map<String, String> colorMap,
            boolean quoteValues) {
        if (isBlank(field) || colorMap == null || colorMap.isEmpty()) {
            return null;
        }
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", field);
        ObjectNode renderer = column.putObject("renderer");
        renderer.put("type", "chip");
        ObjectNode chip = renderer.putObject("chip");
        chip.put("textField", field);
        chip.put("variant", "filled");

        ArrayNode conditional = column.putArray("conditionalRenderers");
        for (Map.Entry<String, String> entry : colorMap.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            ObjectNode rule = conditional.addObject();
            rule.put("condition", buildEqualityCondition(field, entry.getKey(), quoteValues));
            ObjectNode ruleRenderer = rule.putObject("renderer");
            ruleRenderer.put("type", "chip");
            ObjectNode ruleChip = ruleRenderer.putObject("chip");
            ruleChip.put("textField", field);
            ruleChip.put("variant", "filled");
            if (!isBlank(entry.getValue())) {
                ruleChip.put("color", entry.getValue());
            }
        }
        return patch;
    }

    private JsonNode buildConditionalIconPatch(
            String field,
            String value,
            String iconName,
            String color) {
        if (isBlank(field) || isBlank(value)) {
            return null;
        }
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", field);
        ArrayNode conditional = column.putArray("conditionalRenderers");
        ObjectNode rule = conditional.addObject();
        rule.put("condition", buildEqualityCondition(field, value, true));
        ObjectNode renderer = rule.putObject("renderer");
        renderer.put("type", "icon");
        ObjectNode icon = renderer.putObject("icon");
        icon.put("name", isBlank(iconName) ? "error" : iconName);
        icon.put("color", isBlank(color) ? "warn" : color);
        return patch;
    }

    private JsonNode buildConditionalStylePatch(
            String field,
            String condition,
            Map<String, String> style) {
        if (isBlank(field) || isBlank(condition) || style == null || style.isEmpty()) {
            return null;
        }
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", field);
        ArrayNode conditional = column.putArray("conditionalStyles");
        ObjectNode rule = conditional.addObject();
        rule.put("condition", condition);
        ObjectNode styleNode = rule.putObject("style");
        for (Map.Entry<String, String> entry : style.entrySet()) {
            styleNode.put(entry.getKey(), entry.getValue());
        }
        return patch;
    }

    private JsonNode buildConditionalRowStylePatch(String condition, Map<String, String> style) {
        if (isBlank(condition) || style == null || style.isEmpty()) {
            return null;
        }
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode styles = patch.putArray("rowConditionalStyles");
        ObjectNode rule = styles.addObject();
        rule.put("condition", condition);
        ObjectNode styleNode = rule.putObject("style");
        for (Map.Entry<String, String> entry : style.entrySet()) {
            styleNode.put(entry.getKey(), entry.getValue());
        }
        return patch;
    }

    private Map<String, String> coerceRowStyleDefaults(Map<String, String> style) {
        Map<String, String> out = new LinkedHashMap<>();
        if (style != null) {
            out.putAll(style);
        }
        if (!out.containsKey("backgroundColor")) {
            String color = out.remove("color");
            if (!isBlank(color)) {
                out.put("backgroundColor", color);
            }
        }
        if (out.isEmpty()) {
            out.put("backgroundColor", "#ffebee");
        }
        return out;
    }

    private Map<String, String> coerceColumnStyleDefaults(Map<String, String> style) {
        Map<String, String> out = new LinkedHashMap<>();
        if (style != null) {
            out.putAll(style);
        }
        boolean hasColor = out.containsKey("color") && !isBlank(out.get("color"));
        boolean hasBackground = out.containsKey("backgroundColor") && !isBlank(out.get("backgroundColor"));
        if (!hasColor && !hasBackground) {
            out.put("color", "#2e7d32");
        }
        return out;
    }

    private String extractNumericCondition(String promptLower, String field) {
        if (promptLower == null || isBlank(field)) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(\\b" + java.util.regex.Pattern.quote(field.toLowerCase(Locale.ROOT)) + "\\b)\\s*([><]=?)\\s*(\\d+)")
                .matcher(promptLower);
        if (matcher.find()) {
            return field + " " + matcher.group(2) + " " + matcher.group(3);
        }
        java.util.regex.Matcher generic = java.util.regex.Pattern.compile("([><]=?)\\s*(\\d+)").matcher(promptLower);
        if (generic.find()) {
            return field + " " + generic.group(1) + " " + generic.group(2);
        }
        return null;
    }

    private Map<String, String> extractStyleFromPrompt(String promptLower) {
        Map<String, String> style = new LinkedHashMap<>();
        if (promptLower == null) {
            return style;
        }
        if (promptLower.contains("negrito") || promptLower.contains("bold")) {
            style.put("fontWeight", "600");
        }
        if (promptLower.contains("verde") || promptLower.contains("green")) {
            style.put("color", "#2e7d32");
        } else if (promptLower.contains("vermelh claro") || promptLower.contains("light red")) {
            style.put("color", "#ffebee");
        } else if (promptLower.contains("vermelh") || promptLower.contains("red")) {
            style.put("color", "#d32f2f");
        } else if (promptLower.contains("laranja") || promptLower.contains("orange")) {
            style.put("color", "#f57c00");
        }
        return style;
    }

    private AiOrchestratorResponse tryResolveBadgeMissingContext(
            AiIntentClassification intent,
            AiOrchestratorRequest request,
            JsonNode currentState,
            List<String> warnings,
            List<AiCapability> configCapabilities,
            List<AiCapability> componentCapabilities,
            JsonNode componentContext) {
        if (intent == null || request == null) {
            return null;
        }
        if (!COMPONENT_ID_TABLE.equals(request.getComponentId())) {
            return null;
        }
        if (!requiresBadgeValues(intent.getMissingContext())) {
            return null;
        }
        String targetField = intent.getTargetField();
        if (isBlank(targetField)) {
            return null;
        }
        BadgeValuesContext ctx = resolveBadgeValuesContext(request, targetField);
        if (ctx == null || ctx.values.isEmpty()) {
            return null;
        }
        boolean isBoolean = isBooleanType(ctx.inferredType, ctx.explicitType) || looksLikeBooleanValues(ctx.values);
        boolean quoteValues = !isBoolean;
        Map<String, String> colorMap = assignColors(ctx.values, DEFAULT_BADGE_PALETTE);
        JsonNode patch = buildConditionalBadgePatch(targetField, colorMap, quoteValues);
        return applySuggestedPatch(
                patch,
                currentState,
                request.getComponentId(),
                warnings,
                configCapabilities,
                componentCapabilities,
                componentContext);
    }

    private AiOrchestratorResponse applySuggestedPatch(
            JsonNode suggestedPatch,
            JsonNode currentState,
            String componentId,
            List<String> warnings,
            List<AiCapability> configCapabilities,
            List<AiCapability> componentCapabilities,
            JsonNode componentContext) {
        if (suggestedPatch == null || suggestedPatch.isNull()) {
            return error("Nenhum patch sugerido.");
        }
        List<String> outWarnings = warnings != null ? warnings : new ArrayList<>();
        SemanticPatchCheck semanticCheck = validateSemanticPatch(
                suggestedPatch, currentState, outWarnings, false);
        if (!semanticCheck.valid) {
            if (semanticCheck.needsClarification) {
                return clarification(semanticCheck.message, semanticCheck.options);
            }
            return errorWithWarnings(semanticCheck.message, outWarnings);
        }
        JsonNode normalizedPatch = normalizePatch(componentId, semanticCheck.patch);
        List<AiCapability> allowedCaps = mergeCapabilities(configCapabilities, componentCapabilities);
        SanitizeResult sanitizeResult = sanitizePatch(normalizedPatch, allowedCaps);
        List<String> sanitizeWarnings = filterInternalWarnings(
                sanitizeResult.warnings,
                suggestedPatch,
                componentId);
        if (sanitizeWarnings != null && !sanitizeWarnings.isEmpty()) {
            outWarnings.addAll(sanitizeWarnings);
        }
        if (sanitizeResult.sanitized == null || isEmptyObject(sanitizeResult.sanitized)) {
            return errorWithWarnings(
                    "O patch sugerido não continha configurações válidas permitidas.",
                    outWarnings);
        }
        EnumValidationResult enumValidation = validateEnumValues(
                sanitizeResult.sanitized,
                componentContext,
                allowedCaps);
        if (!enumValidation.valid) {
            return invalidEnumValue(enumValidation, outWarnings);
        }
        return AiOrchestratorResponse.builder()
                .type("patch")
                .patch(sanitizeResult.sanitized)
                .diff(buildPatchDiff(currentState, sanitizeResult.sanitized))
                .warnings(outWarnings.isEmpty() ? null : outWarnings)
                .build();
    }

    private JsonNode normalizePatch(String componentId, JsonNode patch) {
        if (!"praxis-table".equals(componentId) || patch == null || !patch.isObject()) {
            return patch;
        }
        Map<String, Object> map = objectMapper.convertValue(patch, new TypeReference<Map<String, Object>>() {});
        Object colsObj = map.get("columns");
        if (colsObj instanceof List<?> cols) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : cols) {
                if (item instanceof Map<?, ?> m) {
                    normalized.add(normalizeColumn(castMap(m)));
                } else {
                    normalized.add(item);
                }
            }
            map.put("columns", normalized);
        }
        return objectMapper.valueToTree(map);
    }

    private Map<String, Object> normalizeColumn(Map<String, Object> col) {
        if (col == null) return null;
        Map<String, Object> next = new LinkedHashMap<>(col);

        Object formatObj = next.get("format");
        if (formatObj instanceof String fmt) {
            String normalizedFormat = normalizeFormatTokenFromLabel(fmt);
            if (normalizedFormat != null && !normalizedFormat.equals(fmt)) {
                next.put("format", normalizedFormat);
                formatObj = normalizedFormat;
            }
        }
        Object typeObj = next.get("type");
        if (formatObj instanceof String fmt && typeObj == null) {
            if (looksLikeCurrencyFormat(fmt)) {
                next.put("type", "currency");
            } else if (looksLikeDateFormat(fmt)) {
                next.put("type", "date");
            }
        }

        Object rendererObj = next.get("renderer");
        if (rendererObj instanceof Map<?, ?> rendererMap) {
            next.put("renderer", normalizeRenderer(castMap(rendererMap)));
        }

        Object computedObj = next.get("computed");
        if (computedObj instanceof Map<?, ?> computedMap) {
            next.put("computed", normalizeComputed(castMap(computedMap)));
        }

        Object condObj = next.get("conditionalRenderers");
        if (condObj instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> rule = new LinkedHashMap<>(castMap(m));
                    Object r = rule.get("renderer");
                    if (r instanceof Map<?, ?> rMap) {
                        rule.put("renderer", normalizeRenderer(castMap(rMap)));
                    }
                    out.add(rule);
                } else {
                    out.add(item);
                }
            }
            next.put("conditionalRenderers", out);
        }
        return next;
    }

    private Map<String, Object> normalizeRenderer(Map<String, Object> renderer) {
        if (renderer == null) return null;
        Map<String, Object> out = new LinkedHashMap<>(renderer);
        Object type = out.get("type");
        if ("icon".equals(type)) {
            Map<String, Object> iconObj = new LinkedHashMap<>();
            Object icon = out.get("icon");
            if (icon instanceof String iconStr) {
                iconObj.put("name", iconStr);
            } else if (icon instanceof Map<?, ?> iconMap) {
                iconObj.putAll(castMap(iconMap));
            }
            if (out.get("color") != null && iconObj.get("color") == null) {
                iconObj.put("color", out.get("color"));
            }
            if (out.get("size") != null && iconObj.get("size") == null) {
                iconObj.put("size", out.get("size"));
            }
            out.put("icon", iconObj);
            out.remove("color");
            out.remove("size");
        }

        if ("badge".equals(type)) {
            Map<String, Object> badge = new LinkedHashMap<>();
            Object badgeObj = out.get("badge");
            if (badgeObj instanceof Map<?, ?> bMap) {
                badge.putAll(castMap(bMap));
            }
            if (out.get("color") != null && badge.get("color") == null) badge.put("color", out.get("color"));
            if (out.get("variant") != null && badge.get("variant") == null) badge.put("variant", out.get("variant"));
            if (out.get("text") != null && badge.get("text") == null) badge.put("text", out.get("text"));
            if (out.get("icon") instanceof String && badge.get("icon") == null) {
                badge.put("icon", out.get("icon"));
            }
            out.put("badge", badge);
            out.remove("color");
            out.remove("variant");
            out.remove("text");
            if (out.get("icon") instanceof String) {
                out.remove("icon");
            }
        }

        if ("chip".equals(type)) {
            Map<String, Object> chip = new LinkedHashMap<>();
            Object chipObj = out.get("chip");
            if (chipObj instanceof Map<?, ?> cMap) {
                chip.putAll(castMap(cMap));
            }
            if (out.get("color") != null && chip.get("color") == null) chip.put("color", out.get("color"));
            if (out.get("variant") != null && chip.get("variant") == null) chip.put("variant", out.get("variant"));
            if (out.get("text") != null && chip.get("text") == null) chip.put("text", out.get("text"));
            if (out.get("icon") instanceof String && chip.get("icon") == null) {
                chip.put("icon", out.get("icon"));
            }
            out.put("chip", chip);
            out.remove("color");
            out.remove("variant");
            out.remove("text");
            if (out.get("icon") instanceof String) {
                out.remove("icon");
            }
        }

        if ("link".equals(type)) {
            Object href = out.get("href");
            if (href instanceof String) {
                Map<String, Object> link = new LinkedHashMap<>();
                Object linkObj = out.get("link");
                if (linkObj instanceof Map<?, ?> lMap) {
                    link.putAll(castMap(lMap));
                }
                link.put("href", href);
                out.put("link", link);
                out.remove("href");
            }
        }
        return out;
    }

    private Map<String, Object> normalizeComputed(Map<String, Object> computed) {
        if (computed == null) return null;
        Map<String, Object> out = new LinkedHashMap<>(computed);
        Object outputTypeObj = out.get("outputType");
        Object formatObj = out.get("format");
        Object expressionObj = out.get("expression");
        String outputType = outputTypeObj instanceof String ? ((String) outputTypeObj).trim() : null;
        String format = formatObj instanceof String ? ((String) formatObj).trim() : null;
        String expression = expressionObj instanceof String ? ((String) expressionObj).trim() : null;

        if (outputTypeObj instanceof String && outputType != null && outputType.isBlank()) {
            out.remove("outputType");
            outputType = null;
        }
        if (outputType != null && !outputType.isBlank()) {
            String normalized = normalizeOutputType(outputType);
            if (normalized != null) {
                out.put("outputType", normalized);
            }
            if (format == null || format.isBlank()) {
                String extracted = extractFormatToken(outputType);
                if (extracted != null) {
                    out.put("format", extracted);
                } else if (looksLikeCurrencyFormat(outputType)) {
                    out.put("format", outputType);
                }
            }
        }
        if ((outputType == null || outputType.isBlank())) {
            String inferred = inferComputedOutputType(format, expression);
            if (!isBlank(inferred)) {
                out.put("outputType", inferred);
            }
        }
        return out;
    }

    private boolean looksLikeCreateColumnPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        return normalized.contains("coluna calculada")
                || normalized.contains("computed column")
                || normalized.contains("add column")
                || normalized.contains("nova coluna")
                || normalized.contains("criar coluna")
                || normalized.contains("adicionar coluna");
    }

    private String normalizeOutputType(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.contains("|") || looksLikeCurrencyFormat(raw)) {
            return "currency";
        }
        if (value.contains("currency") || value.contains("moeda") || value.contains("money")) {
            return "currency";
        }
        if (value.contains("percent") || value.contains("%") || value.contains("porcent")) {
            return "percentage";
        }
        if (value.contains("bool")) {
            return "boolean";
        }
        if (value.contains("date") && value.contains("time")) {
            return "datetime";
        }
        if (value.contains("date")) {
            return "date";
        }
        if (value.contains("number") || value.contains("numeric")) {
            return "number";
        }
        if (value.contains("string") || value.contains("text")) {
            return "string";
        }
        return null;
    }

    private String extractFormatToken(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([A-Z]{3}\\|[^\\)\\s]+)").matcher(raw);
        if (matcher.find()) {
            String token = matcher.group(1);
            if (token != null && !token.isBlank()) {
                return token.trim();
            }
        }
        return null;
    }

    private String inferComputedOutputType(String format, String expression) {
        if (!isBlank(format)) {
            if (looksLikeCurrencyFormat(format)) {
                return "currency";
            }
            String fmtLower = format.toLowerCase(Locale.ROOT);
            if (fmtLower.contains("%")) {
                return "percentage";
            }
            if (looksLikeDateFormat(format)) {
                return "date";
            }
        }
        if (!isBlank(expression)) {
            if (expression.contains("+") || expression.contains("-")
                    || expression.contains("*") || expression.contains("/")) {
                return "number";
            }
        }
        return "number";
    }

    private boolean looksLikeCurrencyFormat(String format) {
        return format != null && format.trim().matches("^[A-Z]{3}(\\|.*)?$");
    }

    private boolean looksLikeDateFormat(String format) {
        if (format == null) return false;
        String fmt = format.trim();
        if (looksLikeMaskFormat(fmt)) {
            return false;
        }
        return fmt.matches(".*(d|M|y){2,}.*") || fmt.contains("/") || fmt.contains("-");
    }

    private SemanticPatchCheck validateSemanticPatch(
            JsonNode patchNode,
            JsonNode currentState,
            List<String> warnings,
            boolean allowUnknownColumns) {
        if (patchNode == null || patchNode.isNull()) {
            return SemanticPatchCheck.invalid("Patch ausente ou invalido.", false, List.of(), patchNode);
        }
        JsonNode working = patchNode;
        if (working.isArray() || looksLikeJsonPatchObject(working)) {
            return SemanticPatchCheck.invalid(
                    "Patch invalido: JSON Patch nao e suportado. Use merge patch (objeto parcial) com identidade (ex.: columns[].field).",
                    false,
                    List.of(),
                    patchNode);
        }

        if (!working.isObject()) {
            return SemanticPatchCheck.invalid("Patch invalido: esperado objeto.", false, List.of(), patchNode);
        }

        SemanticPatchCheck columnCheck = validateColumnsPatch(working, currentState, allowUnknownColumns);
        if (!columnCheck.valid) {
            return columnCheck;
        }
        return SemanticPatchCheck.ok(working);
    }

    private boolean looksLikeJsonPatchObject(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        return node.has("op") || node.has("path") || node.has("from");
    }

    private JsonNode convertJsonPatchToSemantic(
            JsonNode patchOps,
            JsonNode currentState) {
        if (patchOps == null || !patchOps.isArray()) {
            return null;
        }
        JsonNode columns = currentState != null ? currentState.get("columns") : null;
        if (columns == null || !columns.isArray()) {
            return null;
        }

        Map<String, ObjectNode> columnPatches = new LinkedHashMap<>();
        for (JsonNode opNode : patchOps) {
            if (opNode == null || !opNode.isObject()) {
                return null;
            }
            String op = textOrNull(opNode.get("op"));
            if (op == null) {
                return null;
            }
            if ("test".equalsIgnoreCase(op)) {
                continue;
            }
            if (!"replace".equalsIgnoreCase(op) && !"add".equalsIgnoreCase(op)) {
                return null;
            }
            String path = textOrNull(opNode.get("path"));
            if (path == null || path.isBlank()) {
                return null;
            }
            JsonNode value = opNode.get("value");
            if (value == null || value.isNull()) {
                return null;
            }
            if (path.startsWith("/columns/")) {
                String[] parts = path.split("/");
                if (parts.length < 4) {
                    return null;
                }
                String indexPart = parts[2];
                int idx;
                try {
                    idx = Integer.parseInt(indexPart);
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (idx < 0 || idx >= columns.size()) {
                    return null;
                }
                JsonNode columnNode = columns.get(idx);
                String field = textOrNull(columnNode != null ? columnNode.get("field") : null);
                if (field == null || field.isBlank()) {
                    return null;
                }
                List<String> propPath = new ArrayList<>();
                for (int i = 3; i < parts.length; i++) {
                    if (parts[i] == null || parts[i].isBlank()) {
                        return null;
                    }
                    propPath.add(parts[i]);
                }
                if (propPath.isEmpty()) {
                    return null;
                }
                if ("field".equals(propPath.get(0))) {
                    continue;
                }
                ObjectNode colPatch = columnPatches.computeIfAbsent(field, key -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("field", key);
                    return node;
                });
                applyNestedValue(colPatch, propPath, value);
                continue;
            }
            if (path.startsWith("columns[")) {
                java.util.regex.Matcher matcher =
                        java.util.regex.Pattern.compile("^columns\\[field=([^\\]]+)]\\.(.+)$")
                                .matcher(path);
                if (!matcher.matches()) {
                    return null;
                }
                String field = matcher.group(1);
                String propPathRaw = matcher.group(2);
                if (field == null || field.isBlank() || propPathRaw == null || propPathRaw.isBlank()) {
                    return null;
                }
                if (propPathRaw.contains("[") || propPathRaw.contains("]")) {
                    return null;
                }
                String[] propParts = propPathRaw.split("\\.");
                List<String> propPath = new ArrayList<>();
                for (String part : propParts) {
                    if (part == null || part.isBlank()) {
                        return null;
                    }
                    propPath.add(part);
                }
                if (propPath.isEmpty()) {
                    return null;
                }
                if ("field".equals(propPath.get(0))) {
                    continue;
                }
                ObjectNode colPatch = columnPatches.computeIfAbsent(field, key -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("field", key);
                    return node;
                });
                applyNestedValue(colPatch, propPath, value);
                continue;
            }
            return null;
        }

        if (columnPatches.isEmpty()) {
            return null;
        }
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode cols = objectMapper.createArrayNode();
        for (ObjectNode node : columnPatches.values()) {
            cols.add(node);
        }
        out.set("columns", cols);
        return out;
    }

    private void applyNestedValue(ObjectNode root, List<String> path, JsonNode value) {
        ObjectNode current = root;
        for (int i = 0; i < path.size(); i++) {
            String key = path.get(i);
            if (i == path.size() - 1) {
                current.set(key, value);
                return;
            }
            JsonNode existing = current.get(key);
            if (existing != null && existing.isObject()) {
                current = (ObjectNode) existing;
            } else {
                ObjectNode next = objectMapper.createObjectNode();
                current.set(key, next);
                current = next;
            }
        }
    }

    private SemanticPatchCheck validateColumnsPatch(
            JsonNode patch,
            JsonNode currentState,
            boolean allowUnknownColumns) {
        JsonNode columnsPatch = patch.get("columns");
        if (columnsPatch == null || columnsPatch.isNull()) {
            return SemanticPatchCheck.ok(patch);
        }
        if (!columnsPatch.isArray()) {
            return SemanticPatchCheck.invalid(
                    "Patch invalido: 'columns' deve ser array.",
                    false,
                    List.of(),
                    patch);
        }
        List<String> available = extractColumnNames(currentState);
        List<String> missingField = new ArrayList<>();
        List<String> unknownField = new ArrayList<>();
        List<String> duplicateField = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (JsonNode item : columnsPatch) {
            if (item == null || !item.isObject()) {
                missingField.add("<unknown>");
                continue;
            }
            String field = textOrNull(item.get("field"));
            if (field == null || field.isBlank()) {
                missingField.add("<missing>");
                continue;
            }
            if (!seen.add(field)) {
                duplicateField.add(field);
            }
            if (!available.isEmpty() && !available.contains(field)) {
                unknownField.add(field);
            }
        }

        if (!duplicateField.isEmpty()) {
            return SemanticPatchCheck.invalid(
                    "Patch invalido: fields duplicados em columns: " + duplicateField,
                    false,
                    List.of(),
                    patch);
        }
        if (!missingField.isEmpty() || (!unknownField.isEmpty() && !allowUnknownColumns)) {
            StringBuilder msg = new StringBuilder("Preciso da coluna correta para aplicar o patch.");
            if (!unknownField.isEmpty() && !allowUnknownColumns) {
                msg.append(" Nao encontrei: ").append(unknownField).append(".");
            }
            if (!missingField.isEmpty()) {
                msg.append(" Informe o field da coluna.");
            }
            return SemanticPatchCheck.invalid(msg.toString(), true, available, patch);
        }
        return SemanticPatchCheck.ok(patch);
    }

    private SanitizeResult sanitizePatch(JsonNode patch, List<AiCapability> caps) {
        if (patch == null) return new SanitizeResult(null, List.of());
        if (caps == null || caps.isEmpty()) {
            return new SanitizeResult(null, List.of("Capabilities ausentes; patch bloqueado."));
        }
        List<String> allowedPaths = caps.stream()
                .map(AiCapability::getPath)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toList());
        List<String> objectPaths = caps.stream()
                .filter(c -> c != null
                        && c.getPath() != null
                        && !c.getPath().isBlank()
                        && c.getValueKind() != null
                        && "object".equalsIgnoreCase(c.getValueKind()))
                .map(AiCapability::getPath)
                .collect(Collectors.toList());
        List<String> warnings = new ArrayList<>();
        JsonNode sanitized = sanitizeNode(patch, "", allowedPaths, objectPaths, warnings);
        return new SanitizeResult(sanitized, warnings);
    }

    private List<String> filterInternalWarnings(
            List<String> warnings,
            JsonNode rawPatch,
            String componentId) {
        if (warnings == null || warnings.isEmpty()) {
            return warnings;
        }
        List<String> filtered = new ArrayList<>();
        for (String warning : warnings) {
            if (shouldSuppressWarning(warning, rawPatch, componentId)) {
                continue;
            }
            filtered.add(warning);
        }
        return filtered;
    }

    private boolean shouldSuppressWarning(String warning, JsonNode rawPatch, String componentId) {
        if (!"praxis-table".equals(componentId)) {
            return false;
        }
        if (!"Campo ignorado: columns[].type".equals(warning)) {
            return false;
        }
        return !patchHasColumnType(rawPatch);
    }

    private boolean patchHasColumnType(JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            return false;
        }
        JsonNode columns = patch.get("columns");
        if (columns == null || !columns.isArray()) {
            return false;
        }
        for (JsonNode column : columns) {
            if (column != null && column.isObject() && column.has("type")) {
                return true;
            }
        }
        return false;
    }

    private JsonNode sanitizeNode(
            JsonNode node,
            String currentPath,
            List<String> allowedPaths,
            List<String> objectPaths,
            List<String> warnings) {
        if (node == null) return null;
        if (node.isArray()) {
            boolean isRootArray = !currentPath.isBlank()
                    && (allowedPaths.contains(currentPath + "[]")
                    || allowedPaths.stream().anyMatch(p -> p.startsWith(currentPath + "[].")));
            if (!isRootArray) {
                warnings.add("Array ignorado: " + currentPath);
                return MissingNode.getInstance();
            }
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                arr.add(sanitizeNode(item, currentPath + "[]", allowedPaths, objectPaths, warnings));
            }
            return arr;
        }
        if (!node.isObject()) {
            return node;
        }

        ObjectNode clean = objectMapper.createObjectNode();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            String newPath = currentPath.isBlank() ? key : currentPath + "." + key;
            boolean isIdentityField = "columns[]".equals(currentPath) && "field".equals(key);
            boolean exactMatch = allowedPaths.contains(newPath) || isIdentityField;
            boolean prefixMatch = allowedPaths.stream().anyMatch(
                    p -> p.startsWith(newPath + ".") || p.startsWith(newPath + "["));
            boolean objectPrefix = objectPaths != null && !objectPaths.isEmpty()
                    && objectPaths.stream().anyMatch(p -> newPath.startsWith(p + ".") || newPath.startsWith(p + "["));

            if (exactMatch || prefixMatch || objectPrefix) {
                JsonNode sanitizedChild = sanitizeNode(value, newPath, allowedPaths, objectPaths, warnings);
                if (sanitizedChild == null
                        || sanitizedChild.isMissingNode()
                        || sanitizedChild.isNull()
                        || (sanitizedChild.isObject() && sanitizedChild.size() == 0)) {
                    return;
                }
                clean.set(key, sanitizedChild);
            } else {
                warnings.add("Campo ignorado: " + newPath);
            }
        });
        return clean;
    }

    private boolean isEmptyObject(JsonNode node) {
        return node == null || (node.isObject() && node.size() == 0);
    }

    private AiOrchestratorResponse clarification(String message, List<String> options) {
        return clarification(message, options, null);
    }

    private AiOrchestratorResponse clarification(
            String message,
            List<String> options,
            List<AiOption> optionPayloads) {
        return AiOrchestratorResponse.builder()
                .type("clarification")
                .message(message)
                .options(options)
                .optionPayloads(optionPayloads)
                .build();
    }

    private AiOrchestratorResponse clarificationWithContextRequest(
            String message,
            List<Integer> contextRequest) {
        return AiOrchestratorResponse.builder()
                .type("clarification")
                .message(message != null && !message.isBlank()
                        ? message
                        : "Preciso de mais contexto para continuar.")
                .contextRequest(contextRequest)
                .build();
    }

    private AiOrchestratorResponse info(String message) {
        return AiOrchestratorResponse.builder()
                .type("info")
                .message(message)
                .explanation(message)
                .build();
    }

    private AiOrchestratorResponse error(String message) {
        return AiOrchestratorResponse.builder()
                .type("error")
                .message(message)
                .build();
    }

    private AiOrchestratorResponse errorWithWarnings(String message, List<String> warnings) {
        return AiOrchestratorResponse.builder()
                .type("error")
                .message(message)
                .warnings(warnings)
                .build();
    }

    private AiOrchestratorResponse unknownComponentError(AiOrchestratorRequest request) {
        String componentId = request != null ? request.getComponentId() : null;
        String componentType = request != null ? request.getComponentType() : null;
        String message = componentId != null && !componentId.isBlank()
                ? "Componente desconhecido: " + componentId + "."
                : "Componente desconhecido.";
        return AiOrchestratorResponse.builder()
                .type("error")
                .code("UNKNOWN_COMPONENT")
                .message(message)
                .componentId(componentId)
                .componentType(componentType)
                .build();
    }

    private AiOrchestratorResponse invalidEnumValue(EnumValidationResult validation, List<String> warnings) {
        String path = validation != null ? validation.path : null;
        String message = path != null && !path.isBlank()
                ? "Valor inválido para " + path + "."
                : "Valor inválido para enum.";
        if (validation != null && validation.allowedValues != null && !validation.allowedValues.isEmpty()) {
            message = message + " Opções válidas: " + String.join(", ", validation.allowedValues) + ".";
        }
        return AiOrchestratorResponse.builder()
                .type("error")
                .code("INVALID_ENUM_VALUE")
                .message(message)
                .path(path)
                .providedValue(validation != null ? validation.providedValue : null)
                .allowedValues(validation != null ? validation.allowedValues : null)
                .warnings(warnings != null && !warnings.isEmpty() ? warnings : null)
                .build();
    }

    private EnumValidationResult validateEnumValues(
            JsonNode patch,
            JsonNode componentContext,
            List<AiCapability> capabilities) {
        if (patch == null || patch.isNull()) {
            return EnumValidationResult.ok();
        }
        Map<String, List<String>> enumOptions = resolveEnumOptions(componentContext, capabilities);
        if (enumOptions.isEmpty()) {
            return EnumValidationResult.ok();
        }
        normalizeEnumValues(patch, "", enumOptions);
        return validateEnumNode(patch, "", enumOptions);
    }

    private void normalizeEnumValues(
            JsonNode node,
            String path,
            Map<String, List<String>> enumOptions) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            String nextPath = path == null || path.isBlank() ? "[]" : path + "[]";
            for (JsonNode item : node) {
                normalizeEnumValues(item, nextPath, enumOptions);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) node;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String nextPath = path == null || path.isBlank()
                    ? entry.getKey()
                    : path + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value != null && value.isValueNode()) {
                EnumAllowed allowed = resolveEnumAllowed(nextPath, enumOptions);
                if (allowed != null && allowed.values != null && !allowed.values.isEmpty()) {
                    String provided = value.asText();
                    String normalized = normalizeEnumAlias(nextPath, provided, allowed.values);
                    if (normalized != null && !normalized.equals(provided)) {
                        obj.put(entry.getKey(), normalized);
                    }
                }
                continue;
            }
            normalizeEnumValues(value, nextPath, enumOptions);
        }
    }

    private String normalizeEnumAlias(String path, String provided, List<String> allowed) {
        if (provided == null || provided.isBlank() || allowed == null || allowed.isEmpty()) {
            return null;
        }
        String normalized = provided.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean isBadgeVariant = path != null && path.endsWith("badge.variant");
        boolean isChipVariant = path != null && path.endsWith("chip.variant");
        if (isBadgeVariant || isChipVariant) {
            if (lower.equals("solid") || lower.equals("pill") || lower.equals("rounded")) {
                return allowed.contains("filled") ? "filled" : null;
            }
            if (lower.equals("outline")) {
                return allowed.contains("outlined") ? "outlined" : null;
            }
        }
        return null;
    }

    private EnumValidationResult validateEnumNode(
            JsonNode node,
            String path,
            Map<String, List<String>> enumOptions) {
        if (node == null || node.isNull()) {
            return EnumValidationResult.ok();
        }
        if (node.isValueNode()) {
            if (path == null || path.isBlank()) {
                return EnumValidationResult.ok();
            }
            EnumAllowed allowed = resolveEnumAllowed(path, enumOptions);
            if (allowed != null && !allowed.values.isEmpty()) {
                String provided = node.asText();
                boolean match = false;
                for (String value : allowed.values) {
                    if (value.equals(provided)) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    return EnumValidationResult.invalid(allowed.path, node, allowed.values);
                }
            }
            return EnumValidationResult.ok();
        }
        if (node.isArray()) {
            EnumAllowed allowed = resolveEnumAllowed(path, enumOptions);
            String nextPath = path == null || path.isBlank() ? "[]" : path + "[]";
            for (JsonNode item : node) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isValueNode()) {
                    if (allowed != null && !allowed.values.isEmpty()) {
                        String provided = item.asText();
                        boolean match = false;
                        for (String value : allowed.values) {
                            if (value.equals(provided)) {
                                match = true;
                                break;
                            }
                        }
                        if (!match) {
                            return EnumValidationResult.invalid(allowed.path, item, allowed.values);
                        }
                        continue;
                    }
                    EnumValidationResult result = validateEnumNode(item, nextPath, enumOptions);
                    if (!result.valid) {
                        return result;
                    }
                    continue;
                }
                EnumValidationResult result = validateEnumNode(item, nextPath, enumOptions);
                if (!result.valid) {
                    return result;
                }
            }
            return EnumValidationResult.ok();
        }
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String nextPath = path == null || path.isBlank()
                        ? entry.getKey()
                        : path + "." + entry.getKey();
                EnumValidationResult result = validateEnumNode(entry.getValue(), nextPath, enumOptions);
                if (!result.valid) {
                    return result;
                }
            }
        }
        return EnumValidationResult.ok();
    }

    private EnumAllowed resolveEnumAllowed(String path, Map<String, List<String>> enumOptions) {
        if (path == null || path.isBlank() || enumOptions == null || enumOptions.isEmpty()) {
            return null;
        }
        List<String> direct = enumOptions.get(path);
        if (direct != null && !direct.isEmpty()) {
            return new EnumAllowed(path, direct);
        }
        if (path.endsWith("[]")) {
            String basePath = path.substring(0, path.length() - 2);
            List<String> base = enumOptions.get(basePath);
            if (base != null && !base.isEmpty()) {
                return new EnumAllowed(basePath, base);
            }
        } else {
            String arrayPath = path + "[]";
            List<String> array = enumOptions.get(arrayPath);
            if (array != null && !array.isEmpty()) {
                return new EnumAllowed(arrayPath, array);
            }
        }
        return null;
    }

    private Map<String, List<String>> resolveEnumOptions(
            JsonNode componentContext,
            List<AiCapability> capabilities) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (componentContext != null && componentContext.isObject()) {
            JsonNode optionsByPath = componentContext.get("optionsByPath");
            if (optionsByPath != null && optionsByPath.isObject()) {
                java.util.Iterator<Map.Entry<String, JsonNode>> entries = optionsByPath.fields();
                while (entries.hasNext()) {
                    Map.Entry<String, JsonNode> entry = entries.next();
                    String path = entry.getKey();
                    if (path == null || path.isBlank()) continue;
                    JsonNode entryNode = entry.getValue();
                    if (entryNode == null || entryNode.isNull() || !entryNode.isObject()) {
                        continue;
                    }
                    String mode = textOrNull(entryNode.get("mode"));
                    if (mode == null || !"enum".equalsIgnoreCase(mode)) {
                        continue;
                    }
                    JsonNode optionsNode = entryNode.get("options");
                    if (optionsNode == null || optionsNode.isNull()) {
                        optionsNode = entryNode;
                    }
                    List<String> allowed = buildAllowedValues(parseOptionsArray(optionsNode));
                    if (!allowed.isEmpty()) {
                        out.put(path, allowed);
                    }
                }
            }
        }
        if (capabilities != null && !capabilities.isEmpty()) {
            for (AiCapability cap : capabilities) {
                if (cap == null) continue;
                String path = cap.getPath();
                if (path == null || path.isBlank() || out.containsKey(path)) continue;
                if (cap.getAllowedValues() == null || cap.getAllowedValues().isEmpty()) {
                    continue;
                }
                String kind = cap.getValueKind();
                if (kind != null
                        && !"enum".equalsIgnoreCase(kind)
                        && !"array".equalsIgnoreCase(kind)) {
                    continue;
                }
                List<String> allowed = new ArrayList<>();
                for (Object value : cap.getAllowedValues()) {
                    if (value == null) continue;
                    String text = String.valueOf(value);
                    if (!text.isBlank() && !allowed.contains(text)) {
                        allowed.add(text);
                    }
                }
                if (!allowed.isEmpty()) {
                    out.put(path, allowed);
                }
            }
        }
        return out;
    }

    private List<String> buildAllowedValues(List<ContextOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (ContextOption option : options) {
            if (option == null) continue;
            String value = option.value != null ? option.value : option.label;
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private boolean requiresBadgeValues(List<String> missingContext) {
        if (missingContext == null || missingContext.isEmpty()) {
            return false;
        }
        for (String item : missingContext) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String normalized = item.trim().toLowerCase(Locale.ROOT);
            if (MISSING_VALUES_AND_COLORS.equals(normalized) || MISSING_BADGE_VALUES.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private BadgeValuesContext resolveBadgeValuesContext(AiOrchestratorRequest request, String field) {
        if (request == null || isBlank(field)) {
            return new BadgeValuesContext(List.of(), null, null);
        }
        JsonNode dataProfile = request.getDataProfile();
        List<String> values = extractBadgeValuesFromProfile(dataProfile, field);
        String inferredType = extractInferredType(dataProfile, field);
        String explicitType = extractSchemaType(request.getSchemaFields(), field);
        if (values.isEmpty()) {
            values = extractBadgeValuesFromSchema(request.getSchemaFields(), field);
        }
        values = limitBadgeValues(values);
        return new BadgeValuesContext(values, inferredType, explicitType);
    }

    private List<String> extractBadgeValuesFromProfile(JsonNode dataProfile, String field) {
        if (dataProfile == null || !dataProfile.isObject() || isBlank(field)) {
            return List.of();
        }
        JsonNode columns = dataProfile.get("columns");
        if (columns == null || !columns.isObject()) {
            return List.of();
        }
        JsonNode stats = columns.get(field);
        if (stats == null || !stats.isObject()) {
            return List.of();
        }
        JsonNode topValues = stats.get("topValues");
        if (topValues == null || !topValues.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode value : topValues) {
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText();
            if (isBlank(text)) {
                continue;
            }
            unique.add(text.trim());
            if (unique.size() >= BADGE_CARDINALITY_MAX) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private String extractInferredType(JsonNode dataProfile, String field) {
        if (dataProfile == null || !dataProfile.isObject() || isBlank(field)) {
            return null;
        }
        JsonNode columns = dataProfile.get("columns");
        if (columns == null || !columns.isObject()) {
            return null;
        }
        JsonNode stats = columns.get(field);
        if (stats == null || !stats.isObject()) {
            return null;
        }
        return textOrNull(stats.get("inferredType"));
    }

    private String extractSchemaType(JsonNode schemaFields, String field) {
        if (schemaFields == null || !schemaFields.isArray() || isBlank(field)) {
            return null;
        }
        for (JsonNode schemaField : schemaFields) {
            if (schemaField == null || !schemaField.isObject()) {
                continue;
            }
            String name = textOrNull(schemaField.get("name"));
            if (isBlank(name)) {
                name = textOrNull(schemaField.get("field"));
            }
            if (!field.equalsIgnoreCase(name)) {
                continue;
            }
            return textOrNull(schemaField.get("type"));
        }
        return null;
    }

    private List<String> extractBadgeValuesFromSchema(JsonNode schemaFields, String field) {
        if (schemaFields == null || !schemaFields.isArray() || isBlank(field)) {
            return List.of();
        }
        for (JsonNode schemaField : schemaFields) {
            if (schemaField == null || !schemaField.isObject()) {
                continue;
            }
            String name = textOrNull(schemaField.get("name"));
            if (isBlank(name)) {
                name = textOrNull(schemaField.get("field"));
            }
            if (!field.equalsIgnoreCase(name)) {
                continue;
            }
            List<String> values = extractFieldOptions(schemaField);
            if (!values.isEmpty()) {
                return values;
            }
        }
        return List.of();
    }

    private List<String> extractFieldOptions(JsonNode field) {
        if (field == null || !field.isObject()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        JsonNode optionsNode = field.get("options");
        if (optionsNode != null && optionsNode.isArray()) {
            for (JsonNode option : optionsNode) {
                if (option == null || option.isNull()) {
                    continue;
                }
                if (option.isTextual() || option.isNumber()) {
                    String value = option.asText();
                    if (!isBlank(value)) {
                        unique.add(value.trim());
                    }
                    continue;
                }
                if (!option.isObject()) {
                    continue;
                }
                String key = textOrNull(option.get("key"));
                String value = textOrNull(option.get("value"));
                if (!isBlank(key)) {
                    unique.add(key.trim());
                } else if (!isBlank(value)) {
                    unique.add(value.trim());
                }
            }
        }
        JsonNode enumNode = field.get("enumValues");
        if (enumNode != null && enumNode.isArray()) {
            for (JsonNode option : enumNode) {
                if (option == null || option.isNull()) {
                    continue;
                }
                if (option.isTextual() || option.isNumber()) {
                    String value = option.asText();
                    if (!isBlank(value)) {
                        unique.add(value.trim());
                    }
                    continue;
                }
                if (!option.isObject()) {
                    continue;
                }
                String value = textOrNull(option.get("value"));
                if (!isBlank(value)) {
                    unique.add(value.trim());
                }
            }
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(unique);
    }

    private List<String> limitBadgeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            unique.add(value.trim());
            if (unique.size() >= BADGE_CARDINALITY_MAX) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual() || node.isNumber()) {
            String value = node.asText();
            return isBlank(value) ? List.of() : List.of(value);
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText();
            if (!isBlank(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private Map<String, String> readStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode valueNode = entry.getValue();
            if (key == null || key.isBlank() || valueNode == null || valueNode.isNull()) {
                continue;
            }
            String value = valueNode.asText();
            if (!isBlank(value)) {
                out.put(key, value.trim());
            }
        }
        return out;
    }

    private Map<String, String> assignColors(List<String> values, List<String> palette) {
        if (values == null || values.isEmpty()) {
            return new LinkedHashMap<>();
        }
        List<String> colors = (palette != null && !palette.isEmpty())
                ? palette
                : DEFAULT_BADGE_PALETTE;
        if (colors.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new LinkedHashMap<>();
        int paletteSize = colors.size();
        int index = 0;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String color = colors.get(index % paletteSize);
            out.put(value, color);
            index += 1;
        }
        return out;
    }

    private JsonNode buildConditionalBadgePatch(
            String field,
            Map<String, String> colorMap,
            boolean quoteValues) {
        if (isBlank(field) || colorMap == null || colorMap.isEmpty()) {
            return null;
        }
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", field);
        ObjectNode renderer = column.putObject("renderer");
        renderer.put("type", "badge");
        ObjectNode badge = renderer.putObject("badge");
        badge.put("textField", field);
        badge.put("variant", "filled");

        ArrayNode conditional = column.putArray("conditionalRenderers");
        for (Map.Entry<String, String> entry : colorMap.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            ObjectNode rule = conditional.addObject();
            rule.put("condition", buildEqualityCondition(field, entry.getKey(), quoteValues));
            ObjectNode ruleRenderer = rule.putObject("renderer");
            ruleRenderer.put("type", "badge");
            ObjectNode ruleBadge = ruleRenderer.putObject("badge");
            ruleBadge.put("textField", field);
            ruleBadge.put("variant", "filled");
            if (!isBlank(entry.getValue())) {
                ruleBadge.put("color", entry.getValue());
            }
        }
        return patch;
    }

    private String buildEqualityCondition(String field, String value, boolean quoteValue) {
        String safeValue = escapeDslString(value);
        if (!quoteValue) {
            return field + " == " + safeValue;
        }
        return field + " == '" + safeValue + "'";
    }

    private String escapeDslString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "\\'");
    }

    private boolean looksLikeBooleanValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value == null) {
                return false;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!"true".equals(normalized) && !"false".equals(normalized)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBooleanType(String inferredType, String explicitType) {
        return "boolean".equalsIgnoreCase(inferredType) || "boolean".equalsIgnoreCase(explicitType);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String text = node.asText();
        return text != null && !text.isBlank() ? text : null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static final class CreateFlowState {
        private final String step;
        private final String widgetId;
        private final String resourcePath;
        private final boolean invalid;

        private CreateFlowState(String step, String widgetId, String resourcePath, boolean invalid) {
            this.step = step;
            this.widgetId = widgetId;
            this.resourcePath = resourcePath;
            this.invalid = invalid;
        }

        private static CreateFlowState invalid() {
            return new CreateFlowState(null, null, null, true);
        }
    }

    private static final class BadgeValuesContext {
        private final List<String> values;
        private final String inferredType;
        private final String explicitType;

        private BadgeValuesContext(List<String> values, String inferredType, String explicitType) {
            this.values = values != null ? values : List.of();
            this.inferredType = inferredType;
            this.explicitType = explicitType;
        }
    }

    private static final class ColumnDescriptor {
        private final String field;
        private final String header;

        private ColumnDescriptor(String field, String header) {
            this.field = field;
            this.header = header;
        }
    }

    private static final class ContextOption {
        private final String value;
        private final String label;
        private final String example;

        private ContextOption(String value, String label, String example) {
            this.value = value;
            this.label = label;
            this.example = example;
        }
    }

    private static final class SelectedFormatSelection {
        private final String targetField;
        private final String value;
        private final String mode;

        private SelectedFormatSelection(String targetField, String value, String mode) {
            this.targetField = targetField;
            this.value = value;
            this.mode = mode;
        }
    }

    private static final class ClarificationPayload {
        private final List<String> options;
        private final List<AiOption> payloads;

        private ClarificationPayload(List<String> options, List<AiOption> payloads) {
            this.options = options != null ? options : List.of();
            this.payloads = payloads != null ? payloads : List.of();
        }
    }

    private static final class ActionParam {
        private final String name;
        private final String type;
        private final List<String> options;
        private final String description;

        private ActionParam(String name, String type, List<String> options, String description) {
            this.name = name;
            this.type = type;
            this.options = options != null ? options : List.of();
            this.description = description;
        }
    }

    private static final class ComponentAction {
        private final String id;
        private final List<String> keywords;
        private final JsonNode patchTemplate;
        private final String scope; // GLOBAL, COLUMN, ROW
        private final String valueType; // BOOLEAN, ENUM, STRING, OBJECT
        private final JsonNode defaultValue;
        private final List<ActionParam> params;
        private final List<String> relatedConcepts;
        private final String operation;
        private final Boolean requiresExistingTarget;

        private ComponentAction(
                String id,
                List<String> keywords,
                JsonNode patchTemplate,
                String scope,
                String valueType,
                JsonNode defaultValue,
                List<ActionParam> params,
                List<String> relatedConcepts,
                String operation,
                Boolean requiresExistingTarget) {
            this.id = id;
            this.keywords = keywords;
            this.patchTemplate = patchTemplate;
            this.scope = scope;
            this.valueType = valueType;
            this.defaultValue = defaultValue;
            this.params = params != null ? params : List.of();
            this.relatedConcepts = relatedConcepts != null ? relatedConcepts : List.of();
            this.operation = operation;
            this.requiresExistingTarget = requiresExistingTarget;
        }
    }

    private static final class ActionPlanClarification {
        private final String message;
        private final List<String> options;

        private ActionPlanClarification(String message, List<String> options) {
            this.message = message;
            this.options = options != null ? options : List.of();
        }
    }

    private static final class ActionPlanCoverage {
        private final JsonNode patch;
        private final List<String> missingActions;

        private ActionPlanCoverage(JsonNode patch, List<String> missingActions) {
            this.patch = patch;
            this.missingActions = missingActions != null ? missingActions : List.of();
        }
    }

    private static final class ActionPlanPatchResult {
        private final JsonNode patch;
        private final List<String> missingActions;
        private final List<String> missingTokens;

        private ActionPlanPatchResult(
                JsonNode patch,
                List<String> missingActions,
                List<String> missingTokens) {
            this.patch = patch;
            this.missingActions = missingActions != null ? missingActions : List.of();
            this.missingTokens = missingTokens != null ? missingTokens : List.of();
        }
    }

    private static final class RenderedActionPatch {
        private final String actionType;
        private final JsonNode patch;
        private final List<String> missingTokens;

        private RenderedActionPatch(String actionType, JsonNode patch, List<String> missingTokens) {
            this.actionType = actionType;
            this.patch = patch;
            this.missingTokens = missingTokens != null ? missingTokens : List.of();
        }
    }

    private static final class PathToken {
        private final String name;
        private final boolean isArray;

        private PathToken(String name, boolean isArray) {
            this.name = name;
            this.isArray = isArray;
        }
    }

    private static final class ScoredColumn {
        private final String name;
        private final double score;

        private ScoredColumn(String name, double score) {
            this.name = name;
            this.score = score;
        }
    }

    private static final class EnumAllowed {
        private final String path;
        private final List<String> values;

        private EnumAllowed(String path, List<String> values) {
            this.path = path;
            this.values = values != null ? values : List.of();
        }
    }

    private static final class EnumValidationResult {
        private final boolean valid;
        private final String path;
        private final JsonNode providedValue;
        private final List<String> allowedValues;

        private EnumValidationResult(
                boolean valid,
                String path,
                JsonNode providedValue,
                List<String> allowedValues) {
            this.valid = valid;
            this.path = path;
            this.providedValue = providedValue;
            this.allowedValues = allowedValues != null ? allowedValues : List.of();
        }

        private static EnumValidationResult ok() {
            return new EnumValidationResult(true, null, null, List.of());
        }

        private static EnumValidationResult invalid(
                String path,
                JsonNode providedValue,
                List<String> allowedValues) {
            return new EnumValidationResult(false, path, providedValue, allowedValues);
        }
    }

    private static class SanitizeResult {
        private final JsonNode sanitized;
        private final List<String> warnings;

        private SanitizeResult(JsonNode sanitized, List<String> warnings) {
            this.sanitized = sanitized;
            this.warnings = warnings;
        }
    }

    private static class CompletenessResult {
        private final boolean complete;
        private final List<IntentAction> missingActions;
        private final List<String> questions;

        private CompletenessResult(boolean complete, List<IntentAction> missingActions, List<String> questions) {
            this.complete = complete;
            this.missingActions = missingActions != null ? missingActions : List.of();
            this.questions = questions != null ? questions : List.of();
        }

        private static CompletenessResult complete() {
            return new CompletenessResult(true, List.of(), List.of());
        }

        private static CompletenessResult incomplete(List<IntentAction> missingActions, List<String> questions) {
            return new CompletenessResult(false, missingActions, questions);
        }
    }

    private static class ColumnIdentity {
        private final String key;
        private final String value;

        private ColumnIdentity(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static class SemanticPatchCheck {
        private final boolean valid;
        private final boolean needsClarification;
        private final String message;
        private final List<String> options;
        private final JsonNode patch;

        private SemanticPatchCheck(
                boolean valid,
                boolean needsClarification,
                String message,
                List<String> options,
                JsonNode patch) {
            this.valid = valid;
            this.needsClarification = needsClarification;
            this.message = message;
            this.options = options;
            this.patch = patch;
        }

        private static SemanticPatchCheck ok(JsonNode patch) {
            return new SemanticPatchCheck(true, false, null, List.of(), patch);
        }

        private static SemanticPatchCheck invalid(
                String message,
                boolean needsClarification,
                List<String> options,
                JsonNode patch) {
            return new SemanticPatchCheck(false, needsClarification, message, options, patch);
        }
    }

    private static class SchemaResolution {
        private final AiSchemaContext schemaContext;
        private final JsonNode schema;
        private final AiOrchestratorResponse response;

        private SchemaResolution(AiSchemaContext schemaContext, JsonNode schema) {
            this.schemaContext = schemaContext;
            this.schema = schema;
            this.response = null;
        }

        private SchemaResolution(AiOrchestratorResponse response) {
            this.schemaContext = null;
            this.schema = null;
            this.response = response;
        }
    }

    private Set<String> identifyGlobalActions(List<ComponentAction> catalog) {
        if (catalog == null) return Set.of();
        Set<String> globals = new java.util.HashSet<>();
        for (ComponentAction action : catalog) {
            if (action == null || action.id == null) continue;
            // Prioritize explicit scope
            if ("GLOBAL".equalsIgnoreCase(action.scope)) {
                globals.add(normalizeActionKey(action.id));
                continue;
            }
            if ("COLUMN".equalsIgnoreCase(action.scope) || "ROW".equalsIgnoreCase(action.scope)) {
                continue;
            }
            // Fallback to template inspection
            if (!requiresTarget(action)) {
                globals.add(normalizeActionKey(action.id));
            }
        }
        return globals;
    }

    private boolean requiresTarget(ComponentAction action) {
        if (action == null || action.patchTemplate == null) return false;
        return action.patchTemplate.toString().contains("{{target}}");
    }

    private static class ContextRequest {
        private final List<Integer> codes;
        private final String message;

        private ContextRequest(List<Integer> codes, String message) {
            this.codes = codes != null ? codes : List.of();
            this.message = message;
        }

        private boolean hasCodes() {
            return codes != null && !codes.isEmpty();
        }
    }

    private static class TemplateSelection {
        private final AiRegistryTemplateRecord template;
        private final List<String> warnings;

        private TemplateSelection(AiRegistryTemplateRecord template, List<String> warnings) {
            this.template = template;
            this.warnings = warnings;
        }
    }
}
