package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleProductProfile;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.parameters.RuleParameterResolver;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.util.StringUtils;

/**
 * Seeder canônico de plataforma para o {@code praxis-policy-studio}, derivado exclusivamente de
 * {@link GovernedRuleFamilyRegistry}.
 *
 * <p>Elimina classes manuais {@code *RulesStudioDefinitionSeed} nos hosts consumidores ao publicar
 * de forma idempotente 100% dos slots e bindings de cada {@link GovernedRuleFamilyDescriptor} no
 * {@link DomainRuleService}, incluindo {@code factCatalog} tipado a partir do {@code RuleFactContract}
 * JSON Schema real, {@code parameterTemplate}, raízes ontológicas ({@code availableRoots},
 * {@code conceptRoots}, {@code conceptRootBindings}) e linhagem normativa ({@code legacyOccurrence}).
 */
public final class GovernedRulesStudioDefinitionSeeder implements ApplicationRunner {

    public static final String SEED_ACTOR = "system:praxis-rules-studio-seed";
    public static final String FACT_CATALOG_SCHEMA_VERSION = "praxis.domain-rule-fact-catalog.v1";

    private final GovernedRuleFamilyRegistry familyRegistry;
    private final DomainRuleService domainRuleService;
    private final ObjectMapper objectMapper;
    private final List<String> tenantIds;
    private final String environment;

    public GovernedRulesStudioDefinitionSeeder(
            GovernedRuleFamilyRegistry familyRegistry,
            DomainRuleService domainRuleService,
            ObjectMapper objectMapper,
            List<String> tenantIds,
            String environment) {
        this.familyRegistry = Objects.requireNonNull(familyRegistry, "familyRegistry");
        this.domainRuleService = Objects.requireNonNull(domainRuleService, "domainRuleService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tenantIds = normalizeTenantIds(tenantIds);
        this.environment = requireText(environment, "environment");
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAll();
    }

    public int seedAll() {
        int totalCreated = 0;
        for (String tenantId : tenantIds) {
            totalCreated += seedScope(tenantId, environment);
        }
        return totalCreated;
    }

    public int seedScope(String tenantId, String targetEnvironment) {
        DomainRuleGovernancePrincipal seedPrincipal =
                new DomainRuleGovernancePrincipal(
                        requireText(tenantId, "tenantId"),
                        SEED_ACTOR,
                        requireText(targetEnvironment, "targetEnvironment"));
        int createdCount = 0;
        for (GovernedRuleFamilyDescriptor<?> family : familyRegistry.families()) {
            for (DomainRuleProductProfile profile : family.productProfiles()) {
                RuleSetDefinition ruleSet = profile.ruleSet();
                JsonNode factSchemaNode =
                        family.findOperationSpec(ruleSet.ref().operationKey())
                                .map(OperationSpec::factContract)
                                .map(contract -> contract.schema().schema())
                                .orElse(null);
                createdCount += seedRuleSet(family, ruleSet, factSchemaNode, seedPrincipal);
            }
        }
        return createdCount;
    }

    private int seedRuleSet(
            GovernedRuleFamilyDescriptor<?> family,
            RuleSetDefinition ruleSet,
            JsonNode factSchemaNode,
            DomainRuleGovernancePrincipal seedPrincipal) {
        int created = 0;
        Map<String, List<DecisionBinding>> bindingsBySlot =
                ruleSet.bindings().stream()
                        .filter(DecisionBinding::enabled)
                        .collect(Collectors.groupingBy(DecisionBinding::slotKey));
        Optional<RuleErrorCatalog> errorCatalog = family.reservedProductErrorCatalog();

        for (DecisionSlot slot : ruleSet.slots()) {
            List<DecisionBinding> slotBindings =
                    bindingsBySlot.getOrDefault(slot.slotKey(), List.of());
            List<DecisionBinding> bindingsToSeed =
                    slotBindings.isEmpty()
                            ? java.util.Collections.singletonList((DecisionBinding) null)
                            : slotBindings;

            for (DecisionBinding primaryBinding : bindingsToSeed) {
                String ruleKey =
                        primaryBinding != null ? primaryBinding.bindingKey() : slot.slotKey();
                RuleParameterTemplate template = slot.parameterTemplate();
                ObjectNode parameterValues = null;
                if (template != null) {
                    if (primaryBinding != null && primaryBinding.parameterization() != null) {
                        parameterValues = primaryBinding.parameterization().values().deepCopy();
                    } else {
                        parameterValues = objectMapper.createObjectNode();
                        for (var param : template.parameters()) {
                            if (param.defaultValue() != null) {
                                parameterValues.set(param.name(), param.defaultValue());
                            }
                        }
                    }
                }

                JsonNode condition;
                if (template != null && parameterValues != null) {
                    condition =
                            RuleParameterResolver.resolve(template, parameterValues)
                                    .executor()
                                    .expression();
                } else if (primaryBinding != null
                        && primaryBinding.executor() != null
                        && primaryBinding.executor().expression() != null) {
                    condition = primaryBinding.executor().expression();
                } else {
                    ObjectNode defaultExpr = objectMapper.createObjectNode();
                    defaultExpr.putArray("==").add(1).add(1);
                    condition = defaultExpr;
                }

                List<String> expressionFacts =
                        PraxisFactTreeBuilder.extractReferencedFactPaths(condition);
                List<String> slotFacts =
                        template != null
                                ? template.requiredFactPaths()
                                : (primaryBinding != null
                                        ? primaryBinding.requiredFactPaths()
                                        : List.of());
                Set<String> requiredSet = new LinkedHashSet<>(expressionFacts);
                requiredSet.addAll(slotFacts);
                if (requiredSet.isEmpty()) {
                    requiredSet.addAll(ruleSet.referencedFactPaths());
                }
                List<String> requiredFacts = List.copyOf(requiredSet);
                String falseReason =
                        resolveReasonCode(ruleSet, template, primaryBinding, slot, ruleKey);

                List<DomainRuleDefinitionResponse> existingDefinitions =
                        domainRuleService.definitions(
                                seedPrincipal.tenantId(),
                                seedPrincipal.environment(),
                                ruleSet.ref().ruleSetKey(),
                                null,
                                null,
                                ruleKey);
                if (existingDefinitions.stream()
                        .anyMatch(
                                existing ->
                                        hasCurrentDefinition(
                                                existing,
                                                slot,
                                                requiredFacts,
                                                falseReason,
                                                objectMapper))) {
                    continue;
                }

                int nextVersion =
                        existingDefinitions.stream()
                                        .mapToInt(DomainRuleDefinitionResponse::version)
                                        .max()
                                        .orElse(0)
                                + 1;

                List<String> catalogFactPaths;
                if (slot.overridePolicy() == OverridePolicy.REPLACEABLE
                        || slot.overridePolicy() == OverridePolicy.RESTRICT_ONLY
                        || slot.overridePolicy() == OverridePolicy.FORBIDDEN
                        || requiredFacts.isEmpty()) {
                    Set<String> mergedPaths = new LinkedHashSet<>(requiredFacts);
                    mergedPaths.addAll(ruleSet.referencedFactPaths());
                    catalogFactPaths = List.copyOf(mergedPaths);
                } else {
                    catalogFactPaths = requiredFacts;
                }

                String executorKind =
                        primaryBinding != null && primaryBinding.executor() != null
                                ? primaryBinding.executor().type().name()
                                : "JSON_LOGIC";
                String implementationKey =
                        primaryBinding != null && primaryBinding.executor() != null
                                ? primaryBinding.executor().implementationKey()
                                : null;
                String implementationVersion =
                        primaryBinding != null && primaryBinding.executor() != null
                                ? primaryBinding.executor().implementationVersion()
                                : null;

                RuleDecision falseDecision =
                        template != null
                                ? template.falseDecision()
                                : (primaryBinding != null && primaryBinding.falseDecision() != null
                                        ? primaryBinding.falseDecision()
                                        : RuleDecision.DENY);

                Optional<RuleErrorDescriptor> errorOpt =
                        resolveErrorDescriptor(ruleSet, errorCatalog, falseReason, ruleKey);
                String messagePtBr =
                        errorOpt.map(RuleErrorDescriptor::messagePtBr)
                                .orElse("Regra governada do slot " + slot.slotKey() + ".");

                ObjectNode definition = objectMapper.createObjectNode();
                definition.put("ruleSetKey", ruleSet.ref().ruleSetKey());
                definition.put("operationalResourceKey", family.resolvedResourceKey());
                definition.put("slotKey", slot.slotKey());
                definition.put("presentationLabel", falseReason + " — " + messagePtBr);
                definition.put("description", messagePtBr);
                definition.put("decisionStage", slot.stage().name());
                definition.put("cardinality", slot.cardinality().name());
                definition.put("overridePolicy", slot.overridePolicy().name());
                definition.put("aggregationPolicy", slot.aggregationPolicy().name());
                definition.put("executorKind", executorKind);
                if (StringUtils.hasText(implementationKey)) {
                    definition.put("implementationKey", implementationKey);
                }
                if (implementationVersion != null) {
                    definition.put("implementationVersion", implementationVersion);
                }
                definition.put(
                        "bindingSource",
                        primaryBinding != null
                                ? primaryBinding.source().name()
                                : DecisionSource.PRODUCT.name());
                definition.put("falseDecision", falseDecision.name());
                definition.put("falseReasonCode", falseReason);
                definition.put("messagePtBr", messagePtBr);
                definition.set("requiredFactPaths", objectMapper.valueToTree(requiredFacts));
                definition.set("availableRoots", objectMapper.valueToTree(ruleSet.availableRoots()));
                definition.set("conceptRoots", objectMapper.valueToTree(ruleSet.conceptRoots()));
                definition.set(
                        "conceptRootBindings",
                        objectMapper.valueToTree(family.conceptRootSpecs()));
                definition.set(
                        "factCatalog",
                        buildFactCatalog(family, ruleSet, factSchemaNode, catalogFactPaths));
                if (template != null) {
                    definition.set("parameterTemplate", objectMapper.valueToTree(template));
                }
                errorOpt.ifPresent(
                        err -> {
                            ObjectNode legacy = definition.putObject("legacyOccurrence");
                            legacy.put("code", err.code());
                            if (err.targetField() != null) {
                                legacy.put("targetField", err.targetField());
                            }
                            if (err.category() != null) {
                                legacy.put("plsqlOrigin", err.category());
                            }
                            if (err.lineage() != null) {
                                legacy.put("lineage", err.lineage());
                            }
                        });

                ObjectNode parameters = objectMapper.createObjectNode();
                parameters.put("nullSemantics", "FAIL_CLOSED");
                parameters.set(
                        "operationKeys",
                        objectMapper.valueToTree(List.of(ruleSet.ref().operationKey())));
                parameters.put("hostContractVersion", family.hostContractVersion());
                parameters.put("bindingOrder", primaryBinding != null ? primaryBinding.order() : 100);
                if (parameterValues != null) {
                    parameters.set("parameterValues", parameterValues);
                }

                ObjectNode governance = objectMapper.createObjectNode();
                governance.put("lifecycleBoundary", "REFERENCE_DRAFT_ONLY");
                governance.put("sourceKind", "PRAXIS_RULES_STUDIO_SEED");
                governance.put("sourceRuleSetVersion", ruleSet.ref().version());
                governance.set("requiredApprovals", objectMapper.valueToTree(List.of("policy-owner")));
                governance.set("authorizedApprovers", objectMapper.valueToTree(List.of("policy-owner")));
                governance.put("authorityChangeAllowed", false);

                domainRuleService.createDefinition(
                        new DomainRuleDefinitionRequest(
                                ruleKey,
                                nextVersion,
                                "selection_eligibility",
                                "draft",
                                ruleSet.ref().boundedContextKey(),
                                ruleSet.ref().ruleSetKey(),
                                family.ownerServiceKey(),
                                "praxis-rules-engine",
                                "praxis-rules-studio",
                                null,
                                null,
                                definition,
                                parameters,
                                condition,
                                governance,
                                null),
                        seedPrincipal);
                created++;
            }
        }
        return created;
    }

    private ObjectNode buildFactCatalog(
            GovernedRuleFamilyDescriptor<?> family,
            RuleSetDefinition ruleSet,
            JsonNode factSchemaNode,
            List<String> factPaths) {
        if (family.factCatalogDocument().isPresent()) {
            JsonNode explicit = objectMapper.valueToTree(family.factCatalogDocument().get());
            if (explicit.isObject() && explicit.path("facts").isArray() && !explicit.path("facts").isEmpty()) {
                return (ObjectNode) explicit;
            }
        }
        ObjectNode catalog = objectMapper.createObjectNode();
        catalog.put("schemaVersion", FACT_CATALOG_SCHEMA_VERSION);
        ArrayNode facts = catalog.putArray("facts");
        List<String> effectivePaths =
                factPaths.isEmpty() ? List.of("context.tenantId") : factPaths;
        for (String path : effectivePaths) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("path", path);
            node.put("valueType", resolveSchemaValueType(factSchemaNode, path));
            node.put("nullable", true);
            int dot = path.lastIndexOf('.');
            String root = dot >= 0 ? path.substring(0, dot) : "fato";
            String leaf = dot >= 0 ? path.substring(dot + 1) : path;
            node.putObject("labels")
                    .put("pt-BR", root + ": " + leaf)
                    .put("en-US", path.replace('.', ' '));
            node.putObject("descriptions")
                    .put(
                            "pt-BR",
                            "Fato governado do RuleSet "
                                    + ruleSet.ref().ruleSetKey()
                                    + " ("
                                    + path
                                    + ").")
                    .put(
                            "en-US",
                            "Governed evaluation fact for RuleSet "
                                    + ruleSet.ref().ruleSetKey()
                                    + " ("
                                    + path
                                    + ").");
            node.put("providerRef", family.ownerServiceKey() + "." + path);
            node.putArray("evidenceRefs").add(ruleSet.ref().ruleSetKey());
            boolean personal =
                    path.toLowerCase(Locale.ROOT).contains("cpf")
                            || path.toLowerCase(Locale.ROOT).contains("taxid");
            node.put("sensitivity", personal ? "PERSONAL" : "NON_SENSITIVE");
            node.put("redaction", personal ? "MASK" : "NONE");
            facts.add(node);
        }
        return catalog;
    }

    private static String resolveSchemaValueType(JsonNode schemaRoot, String path) {
        if (schemaRoot != null && schemaRoot.isObject()) {
            JsonNode current = schemaRoot;
            for (String segment : path.split("\\.")) {
                if (current == null || !current.isObject()) {
                    current = null;
                    break;
                }
                current = current.path("properties").get(segment);
            }
            if (current != null && current.isObject()) {
                String primaryType = extractPrimaryJsonSchemaType(current.get("type"));
                String format = current.path("format").asText("");
                if ("boolean".equals(primaryType)) {
                    return "boolean";
                }
                if ("integer".equals(primaryType) || "number".equals(primaryType)) {
                    return "number";
                }
                if ("array".equals(primaryType)) {
                    String itemType =
                            extractPrimaryJsonSchemaType(current.path("items").get("type"));
                    if ("integer".equals(itemType) || "number".equals(itemType)) {
                        return "number-array";
                    }
                    return "string-array";
                }
                if ("string".equals(primaryType)
                        && ("date".equals(format) || "date-time".equals(format))) {
                    return "date";
                }
                if ("string".equals(primaryType)) {
                    return "string";
                }
            }
        }
        return "string";
    }

    private static String extractPrimaryJsonSchemaType(JsonNode typeNode) {
        if (typeNode == null || typeNode.isNull()) {
            return "";
        }
        if (typeNode.isTextual()) {
            return typeNode.asText("");
        }
        if (typeNode.isArray()) {
            for (JsonNode item : typeNode) {
                if (item.isTextual() && !"null".equals(item.asText())) {
                    return item.asText();
                }
            }
        }
        return "";
    }

    private static boolean hasCurrentDefinition(
            DomainRuleDefinitionResponse definition,
            DecisionSlot slot,
            List<String> expectedRequiredFacts,
            String expectedFalseReason,
            ObjectMapper mapper) {
        if (definition.definition() == null
                || !definition.definition().hasNonNull("ruleSetKey")
                || !definition.definition().hasNonNull("executorKind")
                || !expectedFalseReason.equals(
                        definition.definition().path("falseReasonCode").asText())
                || !FACT_CATALOG_SCHEMA_VERSION.equals(
                        definition.definition().path("factCatalog").path("schemaVersion").asText())
                || definition.definition().path("factCatalog").path("facts").size() <= 0) {
            return false;
        }
        Set<String> existingRequiredFacts = new LinkedHashSet<>();
        definition
                .definition()
                .path("requiredFactPaths")
                .forEach(
                        node -> {
                            if (node.isTextual()) {
                                existingRequiredFacts.add(node.asText());
                            }
                        });
        if (!existingRequiredFacts.containsAll(expectedRequiredFacts)) {
            return false;
        }
        return slot.parameterTemplate() == null
                || (definition.definition().get("parameterTemplate") != null
                        && PraxisCanonicalJson.sha256(mapper.valueToTree(slot.parameterTemplate()))
                                .equals(
                                        PraxisCanonicalJson.sha256(
                                                definition.definition().get("parameterTemplate"))));
    }

    private static String resolveReasonCode(
            RuleSetDefinition ruleSet,
            RuleParameterTemplate template,
            DecisionBinding primaryBinding,
            DecisionSlot slot,
            String ruleKey) {
        if (template != null && StringUtils.hasText(template.falseReasonCode())) {
            return template.falseReasonCode().trim();
        }
        if (primaryBinding != null && StringUtils.hasText(primaryBinding.falseReasonCode())) {
            return primaryBinding.falseReasonCode().trim();
        }
        if (ruleSet != null && ruleSet.errorDescriptors() != null) {
            RuleErrorDescriptor desc = ruleSet.errorDescriptors().get(ruleKey);
            if (desc != null && StringUtils.hasText(desc.code())) {
                return desc.code().trim();
            }
        }
        return slot.slotKey().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static Optional<RuleErrorDescriptor> resolveErrorDescriptor(
            RuleSetDefinition ruleSet,
            Optional<RuleErrorCatalog> errorCatalog,
            String reasonCode,
            String ruleKey) {
        if (ruleSet.errorDescriptors() != null) {
            RuleErrorDescriptor local = ruleSet.errorDescriptors().get(reasonCode);
            if (local != null) {
                return Optional.of(local);
            }
            RuleErrorDescriptor byBinding = ruleSet.errorDescriptors().get(ruleKey);
            if (byBinding != null) {
                return Optional.of(byBinding);
            }
        }
        return errorCatalog.flatMap(catalog -> catalog.find(reasonCode));
    }

    private static List<String> normalizeTenantIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of("1");
        }
        List<String> cleaned =
                raw.stream()
                        .filter(Objects::nonNull)
                        .flatMap(item -> Arrays.stream(item.split(",")))
                        .map(String::trim)
                        .filter(item -> !item.isEmpty())
                        .distinct()
                        .toList();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("At least one seed tenantId is required");
        }
        return cleaned;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
