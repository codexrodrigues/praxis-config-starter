package org.praxisplatform.config.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.praxisplatform.config.contract.DomainRuleChangeWorkspaceContract;
import org.praxisplatform.config.controller.GovernedRulesStudioSandboxController;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;

/**
 * {@link BeanPostProcessor} neutro que decora o {@link ActionDefinitionRegistry} opcional do {@code
 * praxis-metadata-starter} para publicar a ação {@code run-rules-studio-operational-test} no
 * catálogo semântico ({@code GET /schemas/actions?resource=...}) de todos os recursos operacionais
 * governados por {@link GovernedRuleFamilyRegistry}.
 *
 * <p>Preserva o {@code resourcePath} e o {@code group} de cada recurso operacional enquanto aponta a
 * execução HTTP para {@link GovernedRulesStudioSandboxController#OPERATIONAL_TEST_ACTION_PATH}, sem
 * duplicar definições já registradas pelo host e sem forçar instanciação antecipada do {@link
 * GovernedRuleFamilyRegistry} durante o registro de {@code BeanPostProcessor}s.
 */
public class GovernedRulesStudioActionRegistryPostProcessor implements BeanPostProcessor {

  private static final String DEFAULT_GROUP = "rules-studio";
  private static final String DEFAULT_BASE_PATH = "/api/praxis";
  private static final String OPERATIONAL_PROOF_TAG = "operational-proof";

  private final ObjectProvider<GovernedRuleFamilyRegistry> familyRegistryProvider;
  private final ApplicationContext applicationContext;
  private final String studioResourceKey;
  private final String operationalActionId;
  private final String operationalActionPath;
  private final Set<String> additionalResourceKeys;

  public GovernedRulesStudioActionRegistryPostProcessor(
      GovernedRuleFamilyRegistry familyRegistry, ApplicationContext applicationContext) {
    this(
        familyRegistry,
        applicationContext,
        GovernedRulesStudioSandboxController.RESOURCE_KEY,
        GovernedRulesStudioSandboxController.OPERATIONAL_TEST_ACTION_ID,
        GovernedRulesStudioSandboxController.OPERATIONAL_TEST_ACTION_PATH,
        Set.of());
  }

  public GovernedRulesStudioActionRegistryPostProcessor(
      GovernedRuleFamilyRegistry familyRegistry,
      ApplicationContext applicationContext,
      String studioResourceKey,
      String operationalActionId,
      String operationalActionPath,
      Collection<String> additionalResourceKeys) {
    this(
        singleProvider(Objects.requireNonNull(familyRegistry, "familyRegistry is required")),
        applicationContext,
        studioResourceKey,
        operationalActionId,
        operationalActionPath,
        additionalResourceKeys);
  }

  public GovernedRulesStudioActionRegistryPostProcessor(
      ObjectProvider<GovernedRuleFamilyRegistry> familyRegistryProvider,
      ApplicationContext applicationContext,
      String studioResourceKey,
      String operationalActionId,
      String operationalActionPath,
      Collection<String> additionalResourceKeys) {
    this.familyRegistryProvider =
        Objects.requireNonNull(familyRegistryProvider, "familyRegistryProvider is required");
    this.applicationContext =
        Objects.requireNonNull(applicationContext, "applicationContext is required");
    this.studioResourceKey =
        StringUtils.hasText(studioResourceKey)
            ? studioResourceKey.trim()
            : GovernedRulesStudioSandboxController.RESOURCE_KEY;
    this.operationalActionId =
        StringUtils.hasText(operationalActionId)
            ? operationalActionId.trim()
            : GovernedRulesStudioSandboxController.OPERATIONAL_TEST_ACTION_ID;
    this.operationalActionPath =
        StringUtils.hasText(operationalActionPath)
            ? operationalActionPath.trim()
            : GovernedRulesStudioSandboxController.OPERATIONAL_TEST_ACTION_PATH;

    Set<String> extra = new LinkedHashSet<>();
    if (additionalResourceKeys != null) {
      additionalResourceKeys.stream()
          .filter(StringUtils::hasText)
          .map(String::trim)
          .forEach(extra::add);
    }
    this.additionalResourceKeys = Set.copyOf(extra);
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    if (bean instanceof ActionDefinitionRegistry registry
        && !(bean instanceof GovernedActionDefinitionRegistry)) {
      return new GovernedActionDefinitionRegistry(
          registry,
          familyRegistryProvider,
          additionalResourceKeys,
          applicationContext,
          studioResourceKey,
          operationalActionId,
          operationalActionPath);
    }
    return bean;
  }

  private static ObjectProvider<GovernedRuleFamilyRegistry> singleProvider(
      GovernedRuleFamilyRegistry registry) {
    return new ObjectProvider<>() {
      @Override
      public GovernedRuleFamilyRegistry getObject(Object... args) {
        return registry;
      }

      @Override
      public GovernedRuleFamilyRegistry getIfAvailable() {
        return registry;
      }

      @Override
      public GovernedRuleFamilyRegistry getIfUnique() {
        return registry;
      }

      @Override
      public GovernedRuleFamilyRegistry getObject() {
        return registry;
      }
    };
  }

  static final class GovernedActionDefinitionRegistry implements ActionDefinitionRegistry {

    private record ResourceDescriptor(String resourcePath, String group) {}

    private record GovernedKeyIndex(
        Set<String> governedResourceKeys, Map<String, String> aliasToResourceKey) {}

    private static final SchemaReferenceResolver SCHEMA_REF_RESOLVER =
        new FilteredSchemaReferenceResolver();

    private final ActionDefinitionRegistry delegate;
    private final ObjectProvider<GovernedRuleFamilyRegistry> familyRegistryProvider;
    private final Set<String> additionalResourceKeys;
    private final ApplicationContext applicationContext;
    private final String studioResourceKey;
    private final String operationalActionId;
    private final String operationalActionPath;
    private volatile GovernedKeyIndex keyIndex;
    private volatile Map<String, ResourceDescriptor> discoveredResources;

    GovernedActionDefinitionRegistry(
        ActionDefinitionRegistry delegate,
        ObjectProvider<GovernedRuleFamilyRegistry> familyRegistryProvider,
        Set<String> additionalResourceKeys,
        ApplicationContext applicationContext,
        String studioResourceKey,
        String operationalActionId,
        String operationalActionPath) {
      this.delegate = Objects.requireNonNull(delegate, "delegate is required");
      this.familyRegistryProvider =
          Objects.requireNonNull(familyRegistryProvider, "familyRegistryProvider is required");
      this.additionalResourceKeys =
          Objects.requireNonNull(additionalResourceKeys, "additionalResourceKeys is required");
      this.applicationContext =
          Objects.requireNonNull(applicationContext, "applicationContext is required");
      this.studioResourceKey = studioResourceKey;
      this.operationalActionId = operationalActionId;
      this.operationalActionPath = operationalActionPath;
    }

    @Override
    public List<ActionDefinition> findByResourceKey(String resourceKey) {
      List<ActionDefinition> existing = delegate.findByResourceKey(resourceKey);
      GovernedKeyIndex index = resolveKeyIndex();
      if (resourceKey == null || !index.governedResourceKeys().contains(resourceKey)) {
        return existing;
      }
      if (studioResourceKey.equals(resourceKey)
          || GovernedRulesStudioSandboxController.RESOURCE_KEY.equals(resourceKey)
          || GovernedRulesStudioSandboxController.POLICY_STUDIO_RESOURCE_KEY.equals(resourceKey)) {
        if (existing.isEmpty()) {
          return List.of(canonicalizeOperationalAction(defaultOperationalTemplate(resourceKey)));
        }
        return existing.stream().map(this::canonicalizeOperationalAction).toList();
      }
      boolean alreadyPresent =
          existing.stream()
              .anyMatch(
                  def ->
                      operationalActionId.equals(def.id())
                          || (def.tags() != null && def.tags().contains(OPERATIONAL_PROOF_TAG)));
      if (alreadyPresent) {
        return existing;
      }
      ActionDefinition template = findCanonicalOperationalTemplate(resourceKey);
      ResourceDescriptor descriptor = resolveDescriptor(resourceKey, existing, template, index);

      ActionDefinition adapted =
          new ActionDefinition(
              template.id(),
              resourceKey,
              descriptor.resourcePath(),
              descriptor.group(),
              template.scope(),
              template.title(),
              template.description(),
              template.operation(),
              template.requestSchema(),
              template.responseSchema(),
              template.order(),
              template.successMessage(),
              template.requiredAuthorities(),
              template.allowedStates(),
              template.tags(),
              template.execution(),
              template.legalPurpose(),
              template.legalBasisRef(),
              template.icon(),
              template.tone(),
              template.modifiedFields(),
              template.disallowedStateReason());

      List<ActionDefinition> merged = new ArrayList<>(existing.size() + 1);
      merged.addAll(existing);
      merged.add(adapted);
      merged.sort(
          Comparator.comparingInt(ActionDefinition::order)
              .thenComparing(ActionDefinition::id, Comparator.nullsLast(String::compareTo)));
      return List.copyOf(merged);
    }

    @Override
    public List<ActionDefinition> findByGroup(String group) {
      return delegate.findByGroup(group).stream().map(this::canonicalizeOperationalAction).toList();
    }

    private ActionDefinition findCanonicalOperationalTemplate(String targetResourceKey) {
      for (String candidateKey :
          List.of(
              studioResourceKey,
              GovernedRulesStudioSandboxController.RESOURCE_KEY,
              GovernedRulesStudioSandboxController.POLICY_STUDIO_RESOURCE_KEY)) {
        List<ActionDefinition> direct = delegate.findByResourceKey(candidateKey);
        ActionDefinition found =
            direct.stream()
                .filter(def -> operationalActionId.equals(def.id()))
                .map(this::canonicalizeOperationalAction)
                .findFirst()
                .orElse(null);
        if (found != null) {
          return found;
        }
      }
      return defaultOperationalTemplate(targetResourceKey);
    }

    private ActionDefinition defaultOperationalTemplate(String resourceKey) {
      CanonicalOperationRef operation =
          new CanonicalOperationRef(
              DEFAULT_GROUP,
              "runRulesStudioOperationalTest",
              operationalActionPath,
              "POST");
      return new ActionDefinition(
          operationalActionId,
          resourceKey,
          GovernedRulesStudioSandboxController.BASE_PATH,
          DEFAULT_GROUP,
          ActionScope.COLLECTION,
          "Executar prova operacional do Policy Studio",
          "Executa cenários CREATE/UPDATE governados no sandbox e registra somente evidência sanitizada com concorrência otimista ("
              + DomainRuleChangeWorkspaceContract.RESOURCE_KEY
              + ").",
          operation,
          SCHEMA_REF_RESOLVER.requestSchema(operation),
          SCHEMA_REF_RESOLVER.responseSchema(operation),
          40,
          "Prova operacional registrada",
          List.of("RULE_DEFINITION_AUTHOR"),
          List.of(),
          List.of(
              "rules-studio",
              "policy-studio",
              OPERATIONAL_PROOF_TAG,
              "idempotent",
              "optimistic-concurrency"),
          ActionExecutionContract.defaults(ActionScope.COLLECTION));
    }

    private ActionDefinition canonicalizeOperationalAction(ActionDefinition def) {
      if (def == null
          || !operationalActionId.equals(def.id())
          || def.operation() == null
          || operationalActionPath.equals(def.operation().path())) {
        return def;
      }
      CanonicalOperationRef canonicalOp =
          new CanonicalOperationRef(
              def.operation().group(),
              def.operation().operationId(),
              operationalActionPath,
              def.operation().method());
      return new ActionDefinition(
          def.id(),
          def.resourceKey(),
          GovernedRulesStudioSandboxController.BASE_PATH,
          def.group(),
          def.scope(),
          def.title(),
          def.description(),
          canonicalOp,
          SCHEMA_REF_RESOLVER.requestSchema(canonicalOp),
          SCHEMA_REF_RESOLVER.responseSchema(canonicalOp),
          def.order(),
          def.successMessage(),
          def.requiredAuthorities(),
          def.allowedStates(),
          def.tags(),
          def.execution(),
          def.legalPurpose(),
          def.legalBasisRef(),
          def.icon(),
          def.tone(),
          def.modifiedFields(),
          def.disallowedStateReason());
    }

    private GovernedKeyIndex resolveKeyIndex() {
      GovernedKeyIndex cached = this.keyIndex;
      if (cached != null) {
        return cached;
      }
      Set<String> keys = new LinkedHashSet<>();
      Map<String, String> aliases = new LinkedHashMap<>();
      keys.add(studioResourceKey);
      keys.add(GovernedRulesStudioSandboxController.RESOURCE_KEY);
      keys.add(GovernedRulesStudioSandboxController.POLICY_STUDIO_RESOURCE_KEY);
      keys.addAll(additionalResourceKeys);

      GovernedRuleFamilyRegistry registry = familyRegistryProvider.getIfAvailable();
      if (registry != null) {
        for (GovernedRuleFamilyDescriptor<?> family : registry.families()) {
          String canonicalKey = family.resolvedResourceKey();
          if (StringUtils.hasText(canonicalKey)) {
            keys.add(canonicalKey.trim());
          }
          if (StringUtils.hasText(family.familyKey())) {
            String familyKey = family.familyKey().trim();
            keys.add(familyKey);
            if (StringUtils.hasText(canonicalKey) && !familyKey.equals(canonicalKey.trim())) {
              aliases.put(familyKey, canonicalKey.trim());
            }
          }
          family
              .productProfiles()
              .forEach(
                  profile -> {
                    var ref = profile.ruleSet().ref();
                    String ruleSetKey = ref.ruleSetKey();
                    String bounded = ref.boundedContextKey();
                    if (StringUtils.hasText(ruleSetKey)) {
                      keys.add(ruleSetKey.trim());
                    }
                    if (StringUtils.hasText(bounded)) {
                      keys.add(bounded.trim());
                    }
                    String resolved =
                        StringUtils.hasText(canonicalKey)
                            ? canonicalKey.trim()
                            : (StringUtils.hasText(bounded) ? bounded.trim() : ruleSetKey);
                    if (StringUtils.hasText(resolved)) {
                      if (StringUtils.hasText(ruleSetKey) && !ruleSetKey.equals(resolved)) {
                        aliases.putIfAbsent(ruleSetKey.trim(), resolved);
                      }
                      if (StringUtils.hasText(bounded) && !bounded.equals(resolved)) {
                        aliases.putIfAbsent(bounded.trim(), resolved);
                      }
                    }
                  });
        }
      }
      GovernedKeyIndex computed =
          new GovernedKeyIndex(Set.copyOf(keys), Map.copyOf(aliases));
      this.keyIndex = computed;
      return computed;
    }

    private ResourceDescriptor resolveDescriptor(
        String resourceKey,
        List<ActionDefinition> existing,
        ActionDefinition template,
        GovernedKeyIndex index) {
      if (!existing.isEmpty()) {
        return new ResourceDescriptor(
            existing.getFirst().resourcePath(), existing.getFirst().group());
      }
      if (studioResourceKey.equals(resourceKey)
          || GovernedRulesStudioSandboxController.RESOURCE_KEY.equals(resourceKey)) {
        return new ResourceDescriptor(template.resourcePath(), template.group());
      }
      Map<String, ResourceDescriptor> discovered = indexApiResources();
      ResourceDescriptor direct = discovered.get(resourceKey);
      if (direct != null) {
        return direct;
      }
      String mappedKey = index.aliasToResourceKey().get(resourceKey);
      if (mappedKey != null) {
        List<ActionDefinition> mappedActions = delegate.findByResourceKey(mappedKey);
        if (!mappedActions.isEmpty()) {
          return new ResourceDescriptor(
              mappedActions.getFirst().resourcePath(), mappedActions.getFirst().group());
        }
        ResourceDescriptor mappedDescriptor = discovered.get(mappedKey);
        if (mappedDescriptor != null) {
          return mappedDescriptor;
        }
      }
      String effectiveKey = mappedKey != null ? mappedKey : resourceKey;
      return new ResourceDescriptor(
          DEFAULT_BASE_PATH + "/" + effectiveKey.replace('.', '/'), template.group());
    }

    private Map<String, ResourceDescriptor> indexApiResources() {
      Map<String, ResourceDescriptor> cached = this.discoveredResources;
      if (cached != null) {
        return cached;
      }
      Map<String, ResourceDescriptor> discovered = new LinkedHashMap<>();
      try {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(ApiResource.class);
        for (Object bean : beans.values()) {
          Class<?> targetClass = AopUtils.getTargetClass(bean);
          ApiResource apiResource = AnnotationUtils.findAnnotation(targetClass, ApiResource.class);
          if (apiResource == null || apiResource.resourceKey().isBlank()) {
            continue;
          }
          String[] paths =
              apiResource.value().length > 0 ? apiResource.value() : apiResource.path();
          if (paths.length == 0 || paths[0].isBlank()) {
            continue;
          }
          ApiGroup apiGroup = AnnotationUtils.findAnnotation(targetClass, ApiGroup.class);
          String group =
              apiGroup != null && !apiGroup.value().isBlank() ? apiGroup.value() : DEFAULT_GROUP;
          discovered.putIfAbsent(
              apiResource.resourceKey().trim(), new ResourceDescriptor(paths[0].trim(), group));
        }
      } catch (RuntimeException ignored) {
        // Fallback gracioso em contextos parciais de teste
      }
      this.discoveredResources = Map.copyOf(discovered);
      return this.discoveredResources;
    }
  }
}
