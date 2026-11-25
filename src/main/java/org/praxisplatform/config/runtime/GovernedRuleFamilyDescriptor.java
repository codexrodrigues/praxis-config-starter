package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.TenantEnvironmentScope;
import org.praxisplatform.config.service.DomainRuleProductProfile;
import org.praxisplatform.config.service.RuleLegalBasisUrlValidator;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.facts.RuleFactCatalogDocument;
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.snapshot.TypedCompiledRuleSnapshot;
import org.praxisplatform.rules.violation.RuleErrorCatalog;

/**
 * Descritor declarativo auto-registrável ({@code PLAT-EVO-05}) de uma família de regras governadas
 * no host.
 *
 * <p>Permite que cada módulo ou vertical de negócio publique sua própria família de regras como um
 * bean Spring independente ({@code @Bean GovernedRuleFamilyDescriptor<MyOperation>}), eliminando
 * registros manuais e cadeias centralizadas de {@code if/else} em classes de preload/configuração,
 * além de canonizar os {@link DomainRuleProductProfile}s via {@link PraxisRulePlanCompiler}
 * (garantindo paridade com {@code DomainRuleSnapshotService.prepareComposition}) e projetar
 * metadados semânticos para o {@code DomainRuleSetCatalogProvider} e o {@code praxis-policy-studio}.
 *
 * @param <O> enum de operações governadas da família
 */
public final class GovernedRuleFamilyDescriptor<O extends Enum<O>> {

    /**
     * Identidade semântica de uma raiz ontológica ({@code conceptRoot}) referenciada por um
     * {@code RuleSet}, desacoplada de dependência direta em {@code praxis-metadata-starter}.
     */
    public record GovernedConceptRootSpec(
            String role, String serviceKey, String contextKey, String resourceKey) {
        public GovernedConceptRootSpec {
            role = requireText(role, "role");
            serviceKey = requireText(serviceKey, "serviceKey");
            contextKey = requireText(contextKey, "contextKey");
            resourceKey = requireText(resourceKey, "resourceKey");
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }
    }

    /**
     * Amparo normativo estruturado associado a um slot, regra ou família governada, desacoplado de
     * dependência direta em {@code praxis-metadata-starter}.
     */
    public record GovernedRuleLegalBasisSpec(
            String code,
            String title,
            String article,
            String jurisdiction,
            String summary,
            String url) {
        public GovernedRuleLegalBasisSpec {
            code = normalizeNullable(code);
            title = normalizeNullable(title);
            article = normalizeNullable(article);
            jurisdiction = normalizeNullable(jurisdiction);
            summary = normalizeNullable(summary);
            url = RuleLegalBasisUrlValidator.validateOptionalHttpUrl(url, "url");
            if (code == null && title == null && article == null && summary == null) {
                throw new IllegalArgumentException(
                        "GovernedRuleLegalBasisSpec requires at least one identifying field");
            }
        }

        public static GovernedRuleLegalBasisSpec of(String code, String title, String summary) {
            return new GovernedRuleLegalBasisSpec(code, title, null, null, summary, null);
        }

        public static GovernedRuleLegalBasisSpec of(
                String code, String title, String article, String jurisdiction, String summary) {
            return new GovernedRuleLegalBasisSpec(code, title, article, jurisdiction, summary, null);
        }

        public static GovernedRuleLegalBasisSpec of(
                String code,
                String title,
                String article,
                String jurisdiction,
                String summary,
                String url) {
            return new GovernedRuleLegalBasisSpec(code, title, article, jurisdiction, summary, url);
        }
    }

    /**
     * Documentação didática humana ("de humano para humano") de uma família, slot ou regra governada,
     * desacoplada de dependência direta em {@code praxis-metadata-starter}.
     */
    public record GovernedRuleDocumentationSpec(
            String title,
            String summary,
            String whenApplies,
            String rationale,
            List<GovernedRuleLegalBasisSpec> legalBasis,
            String customizationGuidance) {
        public GovernedRuleDocumentationSpec {
            title = normalizeNullable(title);
            summary = normalizeNullable(summary);
            whenApplies = normalizeNullable(whenApplies);
            rationale = normalizeNullable(rationale);
            legalBasis =
                    legalBasis == null || legalBasis.isEmpty()
                            ? List.of()
                            : legalBasis.stream().filter(Objects::nonNull).toList();
            customizationGuidance = normalizeNullable(customizationGuidance);
        }

        @JsonIgnore
        public boolean isEmpty() {
            return title == null
                    && summary == null
                    && whenApplies == null
                    && rationale == null
                    && legalBasis.isEmpty()
                    && customizationGuidance == null;
        }

        public static GovernedRuleDocumentationSpec of(
                String title,
                String summary,
                String whenApplies,
                String rationale,
                List<GovernedRuleLegalBasisSpec> legalBasis,
                String customizationGuidance) {
            return new GovernedRuleDocumentationSpec(
                    title, summary, whenApplies, rationale, legalBasis, customizationGuidance);
        }
    }

    private final GovernedHostRuleBundleRuntime<O> runtime;
    private final RuleFactCatalogDocument factCatalogDocument;
    private final Map<String, Class<?>> rootDtoBindings;
    private final String resourceKey;
    private final String title;
    private final String description;
    private final Map<String, GovernedConceptRootSpec> conceptRootSpecs;
    private final GovernedRuleDocumentationSpec ruleSetDocumentation;
    private final Map<String, GovernedRuleDocumentationSpec> slotDocumentation;
    private final Map<String, GovernedRuleDocumentationSpec> ruleDocumentation;
    private final List<DomainRuleProductProfile> productProfiles;

    public GovernedRuleFamilyDescriptor(GovernedHostRuleBundleRuntime<O> runtime) {
        this(runtime, null, Map.of(), null, null, null, Map.of(), null, Map.of(), Map.of());
    }

    public GovernedRuleFamilyDescriptor(
            GovernedHostRuleBundleRuntime<O> runtime, RuleFactCatalogDocument factCatalogDocument) {
        this(runtime, factCatalogDocument, Map.of(), null, null, null, Map.of(), null, Map.of(), Map.of());
    }

    public GovernedRuleFamilyDescriptor(
            GovernedHostRuleBundleRuntime<O> runtime,
            RuleFactCatalogDocument factCatalogDocument,
            Map<String, Class<?>> rootDtoBindings) {
        this(runtime, factCatalogDocument, rootDtoBindings, null, null, null, Map.of(), null, Map.of(), Map.of());
    }

    public GovernedRuleFamilyDescriptor(
            GovernedHostRuleBundleRuntime<O> runtime,
            RuleFactCatalogDocument factCatalogDocument,
            Map<String, Class<?>> rootDtoBindings,
            String resourceKey,
            String title,
            String description,
            Map<String, GovernedConceptRootSpec> conceptRootSpecs) {
        this(
                runtime,
                factCatalogDocument,
                rootDtoBindings,
                resourceKey,
                title,
                description,
                conceptRootSpecs,
                null,
                Map.of(),
                Map.of());
    }

    public GovernedRuleFamilyDescriptor(
            GovernedHostRuleBundleRuntime<O> runtime,
            RuleFactCatalogDocument factCatalogDocument,
            Map<String, Class<?>> rootDtoBindings,
            String resourceKey,
            String title,
            String description,
            Map<String, GovernedConceptRootSpec> conceptRootSpecs,
            GovernedRuleDocumentationSpec ruleSetDocumentation,
            Map<String, GovernedRuleDocumentationSpec> slotDocumentation,
            Map<String, GovernedRuleDocumentationSpec> ruleDocumentation) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.factCatalogDocument = factCatalogDocument;
        this.rootDtoBindings =
                rootDtoBindings == null
                        ? Map.of()
                        : Map.copyOf(new LinkedHashMap<>(rootDtoBindings));
        this.resourceKey = normalizeNullable(resourceKey);
        this.title = normalizeNullable(title);
        this.description = normalizeNullable(description);
        this.conceptRootSpecs =
                conceptRootSpecs == null
                        ? Map.of()
                        : Map.copyOf(new LinkedHashMap<>(conceptRootSpecs));
        this.ruleSetDocumentation =
                ruleSetDocumentation != null && !ruleSetDocumentation.isEmpty()
                        ? ruleSetDocumentation
                        : null;
        this.slotDocumentation = immutableDocumentationMap(slotDocumentation);
        this.ruleDocumentation = immutableDocumentationMap(ruleDocumentation);
        PraxisRulePlanCompiler planCompiler =
                new PraxisRulePlanCompiler(runtime.executorRegistry());
        this.productProfiles =
                runtime.operationSpecs().values().stream()
                        .map(
                                spec -> {
                                    RuleSetDefinition canonicalRuleSet =
                                            planCompiler.compile(spec.productRuleSet()).definition();
                                    return new DomainRuleProductProfile(
                                            runtime.ownerServiceKey(),
                                            runtime.hostContractVersion(),
                                            canonicalRuleSet);
                                })
                        .toList();
    }

    /**
     * Retorna uma nova instância enriquecida com metadados semânticos de catálogo e vínculos de
     * raízes ontológicas ({@code conceptRootSpecs}), preservando a documentação didática atual.
     */
    public GovernedRuleFamilyDescriptor<O> withFactMetadata(
            RuleFactCatalogDocument factCatalogDocument, Map<String, Class<?>> rootDtoBindings) {
        return new GovernedRuleFamilyDescriptor<>(
                this.runtime,
                factCatalogDocument,
                rootDtoBindings != null ? rootDtoBindings : Map.of(),
                this.resourceKey,
                this.title,
                this.description,
                this.conceptRootSpecs,
                this.ruleSetDocumentation,
                this.slotDocumentation,
                this.ruleDocumentation);
    }

    public GovernedRuleFamilyDescriptor<O> withRootDtoBindings(
            Map<String, Class<?>> rootDtoBindings) {
        return withFactMetadata(this.factCatalogDocument, rootDtoBindings);
    }

    public GovernedRuleFamilyDescriptor<O> withMetadata(
            String resourceKey,
            String title,
            String description,
            Map<String, GovernedConceptRootSpec> conceptRootSpecs) {
        return new GovernedRuleFamilyDescriptor<>(
                this.runtime,
                this.factCatalogDocument,
                this.rootDtoBindings,
                resourceKey,
                title,
                description,
                conceptRootSpecs,
                this.ruleSetDocumentation,
                this.slotDocumentation,
                this.ruleDocumentation);
    }

    /**
     * Retorna uma nova instância enriquecida com metadados semânticos, vínculos de raízes e
     * documentação didática de família, slots e regras.
     */
    public GovernedRuleFamilyDescriptor<O> withMetadata(
            String resourceKey,
            String title,
            String description,
            Map<String, GovernedConceptRootSpec> conceptRootSpecs,
            GovernedRuleDocumentationSpec ruleSetDocumentation,
            Map<String, GovernedRuleDocumentationSpec> slotDocumentation,
            Map<String, GovernedRuleDocumentationSpec> ruleDocumentation) {
        return new GovernedRuleFamilyDescriptor<>(
                this.runtime,
                this.factCatalogDocument,
                this.rootDtoBindings,
                resourceKey,
                title,
                description,
                conceptRootSpecs,
                ruleSetDocumentation,
                slotDocumentation,
                ruleDocumentation);
    }

    public GovernedRuleFamilyDescriptor<O> withDocumentation(
            GovernedRuleDocumentationSpec ruleSetDocumentation,
            Map<String, GovernedRuleDocumentationSpec> slotDocumentation,
            Map<String, GovernedRuleDocumentationSpec> ruleDocumentation) {
        return new GovernedRuleFamilyDescriptor<>(
                this.runtime,
                this.factCatalogDocument,
                this.rootDtoBindings,
                this.resourceKey,
                this.title,
                this.description,
                this.conceptRootSpecs,
                ruleSetDocumentation,
                slotDocumentation,
                ruleDocumentation);
    }

    public GovernedRuleFamilyDescriptor<O> withRuleSetDocumentation(
            GovernedRuleDocumentationSpec ruleSetDocumentation) {
        return withDocumentation(
                ruleSetDocumentation, this.slotDocumentation, this.ruleDocumentation);
    }

    public GovernedRuleFamilyDescriptor<O> withSlotDocumentation(
            Map<String, GovernedRuleDocumentationSpec> slotDocumentation) {
        return withDocumentation(
                this.ruleSetDocumentation, slotDocumentation, this.ruleDocumentation);
    }

    public GovernedRuleFamilyDescriptor<O> withRuleDocumentation(
            Map<String, GovernedRuleDocumentationSpec> ruleDocumentation) {
        return withDocumentation(
                this.ruleSetDocumentation, this.slotDocumentation, ruleDocumentation);
    }

    public String familyKey() {
        return runtime.ruleSetKey();
    }

    public String ownerServiceKey() {
        return runtime.ownerServiceKey();
    }

    public String hostContractVersion() {
        return runtime.hostContractVersion();
    }

    public String factProviderVersion() {
        return runtime.factProviderVersion();
    }

    public Class<O> operationType() {
        return runtime.operationType();
    }

    public Map<O, OperationSpec<O>> operationSpecs() {
        return runtime.operationSpecs();
    }

    public Optional<OperationSpec<O>> findOperationSpec(String operationKey) {
        if (operationKey == null || operationKey.isBlank()) {
            return Optional.empty();
        }
        String normalized = operationKey.trim();
        return runtime.operationSpecs().values().stream()
                .filter(spec -> spec.operationKey().equals(normalized))
                .findFirst();
    }

    public List<String> operationKeys() {
        return runtime.operationKeys();
    }

    public GovernedHostRuleBundleRuntime<O> runtime() {
        return runtime;
    }

    public Optional<RuleErrorCatalog> reservedProductErrorCatalog() {
        return Optional.ofNullable(runtime.reservedProductErrorCatalog());
    }

    public Optional<RuleFactCatalogDocument> factCatalogDocument() {
        return Optional.ofNullable(factCatalogDocument);
    }

    /**
     * Retorna os vínculos opcionais entre raízes ontológicas (ex.: {@code "draft"},
     * {@code "current"}, {@code "person"}) e as classes de DTO correspondentes da família.
     */
    public Map<String, Class<?>> rootDtoBindings() {
        return rootDtoBindings;
    }

    /**
     * Retorna a chave canônica do recurso governado, inferindo a partir de {@link RuleSetRef}
     * quando não declarada explicitamente.
     */
    public String resolvedResourceKey() {
        if (resourceKey != null) {
            return resourceKey;
        }
        return productProfiles.stream()
                .findFirst()
                .map(profile -> inferResourceKey(profile.ruleSet().ref()))
                .orElse(familyKey());
    }

    public Optional<String> resourceKey() {
        return Optional.ofNullable(resourceKey);
    }

    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public Map<String, GovernedConceptRootSpec> conceptRootSpecs() {
        return conceptRootSpecs;
    }

    public Optional<GovernedRuleDocumentationSpec> ruleSetDocumentation() {
        return Optional.ofNullable(ruleSetDocumentation);
    }

    public Map<String, GovernedRuleDocumentationSpec> slotDocumentation() {
        return slotDocumentation;
    }

    public Map<String, GovernedRuleDocumentationSpec> ruleDocumentation() {
        return ruleDocumentation;
    }

    public List<DomainRuleProductProfile> productProfiles() {
        return productProfiles;
    }

    public boolean supportsOperation(String operationKey) {
        if (operationKey == null || operationKey.isBlank()) {
            return false;
        }
        return runtime.operationKeys().contains(operationKey.trim());
    }

    /**
     * Operação unitária padrão para famílias de regras declaradas diretamente por um único
     * {@link RuleSetDefinition} sem enum específico de suboperações.
     */
    public enum SingleRuleOperation {
        DEFAULT
    }

    /**
     * Cria um {@link GovernedRuleFamilyDescriptor} zero-boilerplate diretamente a partir de um
     * {@link RuleSetDefinition} de produto (sem exigir {@code RuleFactContract} manual).
     */
    public static GovernedRuleFamilyDescriptor<SingleRuleOperation> ofSingleRuleSet(
            String ownerServiceKey,
            String hostContractVersion,
            RuleSetDefinition productRuleSet,
            org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetReader reader,
            java.time.Clock clock,
            java.time.ZoneId zone,
            org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry executorRegistry,
            RuleErrorCatalog reservedProductErrorCatalog) {
        return ofSingleRuleSet(
                ownerServiceKey,
                hostContractVersion,
                productRuleSet,
                reader,
                clock,
                zone,
                executorRegistry,
                reservedProductErrorCatalog,
                null);
    }

    /**
     * Cria um {@link GovernedRuleFamilyDescriptor} zero-boilerplate diretamente a partir de um
     * {@link RuleSetDefinition} de produto com fábrica customizada de exceção de violação do host.
     */
    public static GovernedRuleFamilyDescriptor<SingleRuleOperation> ofSingleRuleSet(
            String ownerServiceKey,
            String hostContractVersion,
            RuleSetDefinition productRuleSet,
            org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetReader reader,
            java.time.Clock clock,
            java.time.ZoneId zone,
            org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry executorRegistry,
            RuleErrorCatalog reservedProductErrorCatalog,
            java.util.function.BiFunction<
                            org.praxisplatform.rules.plan.RuleDecisionPlan,
                            org.praxisplatform.rules.facts.TypedRuleEvaluationResult,
                            RuntimeException>
                    violationExceptionFactory) {
        Objects.requireNonNull(productRuleSet, "productRuleSet");
        RuleSetRef ref = productRuleSet.ref();
        List<OperationSpec<SingleRuleOperation>> specs =
                List.of(
                        OperationSpec.ofUntyped(
                                SingleRuleOperation.DEFAULT, ref.operationKey(), productRuleSet));
        String factProviderVersion = ownerServiceKey + ":" + ref.ruleSetKey() + "/" + ref.version();
        GovernedHostRuleBundleRuntime<SingleRuleOperation> runtime =
                violationExceptionFactory == null
                        ? new GovernedHostRuleBundleRuntime<>(
                                SingleRuleOperation.class,
                                ownerServiceKey,
                                ref.ruleSetKey(),
                                hostContractVersion,
                                factProviderVersion,
                                specs,
                                reader,
                                clock,
                                zone,
                                executorRegistry,
                                reservedProductErrorCatalog)
                        : new GovernedHostRuleBundleRuntime<>(
                                SingleRuleOperation.class,
                                ownerServiceKey,
                                ref.ruleSetKey(),
                                hostContractVersion,
                                factProviderVersion,
                                specs,
                                reader,
                                clock,
                                zone,
                                executorRegistry,
                                reservedProductErrorCatalog,
                                violationExceptionFactory);
        return new GovernedRuleFamilyDescriptor<>(runtime);
    }

    public TypedCompiledRuleSnapshot admitCandidate(
            TenantEnvironmentScope scope, PublishedRuleSnapshot snapshot) {
        return runtime.admitCandidate(scope, snapshot);
    }

    public org.praxisplatform.rules.snapshot.CompiledRuleSnapshot admitCandidateSnapshot(
            TenantEnvironmentScope scope, PublishedRuleSnapshot snapshot) {
        return runtime.admitSnapshot(scope, snapshot);
    }

    private static String inferResourceKey(RuleSetRef ref) {
        String boundedContext = ref.boundedContextKey();
        if (boundedContext.contains(".")) {
            return boundedContext;
        }
        String ruleSetKey = ref.ruleSetKey();
        String marker = "." + boundedContext + ".";
        int idx = ruleSetKey.indexOf(marker);
        if (idx > 0) {
            return ruleSetKey.substring(0, idx + 1 + boundedContext.length());
        }
        return ref.domainKey() + "." + boundedContext;
    }

    private static Map<String, GovernedRuleDocumentationSpec> immutableDocumentationMap(
            Map<String, GovernedRuleDocumentationSpec> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, GovernedRuleDocumentationSpec> copy = new LinkedHashMap<>();
        input.forEach(
                (key, value) -> {
                    if (key != null && !key.isBlank() && value != null && !value.isEmpty()) {
                        copy.put(key.trim(), value);
                    }
                });
        return copy.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(copy);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
