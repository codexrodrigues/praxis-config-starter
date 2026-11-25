package org.praxisplatform.config.service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.praxisplatform.uischema.analytics.ResolvedVerifiedAnalyticalRecipe;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeDescriptor;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeResolver;
import org.praxisplatform.uischema.annotation.AnalyticsProjection;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.UiAnalytics;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Implementação isolada de {@link UiLayoutKpiCatalogOracle} apoiada nos controladores
 * {@link AbstractResourceQueryController} e receitas {@link VerifiedAnalyticalRecipeResolver}
 * publicados por {@code praxis-metadata-starter}.
 */
public final class MetadataStarterUiLayoutKpiCatalogOracle implements UiLayoutKpiCatalogOracle {

    private final ObjectProvider<AbstractResourceQueryController<?, ?, ?>> controllersProvider;
    private final ObjectProvider<VerifiedAnalyticalRecipeResolver> recipeResolverProvider;
    private volatile CatalogSnapshot snapshot;

    public MetadataStarterUiLayoutKpiCatalogOracle(
            ObjectProvider<AbstractResourceQueryController<?, ?, ?>> controllersProvider,
            ObjectProvider<VerifiedAnalyticalRecipeResolver> recipeResolverProvider) {
        this.controllersProvider = controllersProvider;
        this.recipeResolverProvider = recipeResolverProvider;
    }

    @SuppressWarnings("unchecked")
    public static MetadataStarterUiLayoutKpiCatalogOracle fromApplicationContext(
            ApplicationContext applicationContext) {
        ObjectProvider<AbstractResourceQueryController<?, ?, ?>> controllers =
                (ObjectProvider<AbstractResourceQueryController<?, ?, ?>>)
                        (ObjectProvider<?>)
                                applicationContext.getBeanProvider(
                                        AbstractResourceQueryController.class);
        ObjectProvider<VerifiedAnalyticalRecipeResolver> recipes =
                applicationContext.getBeanProvider(VerifiedAnalyticalRecipeResolver.class);
        return new MetadataStarterUiLayoutKpiCatalogOracle(controllers, recipes);
    }

    @Override
    public boolean isResourcePathAllowed(String canonicalResourcePath) {
        return UiLayoutKpiCatalogOracle.normalizeCanonicalResourcePath(canonicalResourcePath)
                .map(path -> resolveSnapshot().allowedPaths().contains(path))
                .orElse(false);
    }

    @Override
    public Optional<VerifiedRecipeSpec> findVerifiedRecipe(
            String canonicalResourcePath, String recipeId) {
        if (!StringUtils.hasText(recipeId)) {
            return Optional.empty();
        }
        return UiLayoutKpiCatalogOracle.normalizeCanonicalResourcePath(canonicalResourcePath)
                .map(path -> resolveSnapshot().recipesByPath().get(path))
                .map(byId -> byId.get(recipeId.trim()));
    }

    @Override
    public Optional<ResourceStatsContract> findResourceStatsContract(String canonicalResourcePath) {
        return UiLayoutKpiCatalogOracle.normalizeCanonicalResourcePath(canonicalResourcePath)
                .map(path -> resolveSnapshot().statsByPath().get(path));
    }

    @Override
    public Optional<Set<String>> findAnalyticsProjectionIds(String canonicalResourcePath) {
        return UiLayoutKpiCatalogOracle.normalizeCanonicalResourcePath(canonicalResourcePath)
                .map(path -> resolveSnapshot().projectionsByPath().get(path));
    }

    private CatalogSnapshot resolveSnapshot() {
        CatalogSnapshot current = this.snapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.snapshot == null) {
                this.snapshot = buildSnapshot();
            }
            return this.snapshot;
        }
    }

    private CatalogSnapshot buildSnapshot() {
        Set<String> allowedPaths = new LinkedHashSet<>();
        Map<String, Map<String, VerifiedRecipeSpec>> recipesByPath = new LinkedHashMap<>();
        Map<String, ResourceStatsContract> statsByPath = new LinkedHashMap<>();
        Map<String, Set<String>> projectionsByPath = new LinkedHashMap<>();

        List<AbstractResourceQueryController<?, ?, ?>> controllers = new ArrayList<>();
        if (controllersProvider != null) {
            controllersProvider.orderedStream().forEach(controllers::add);
        }
        VerifiedAnalyticalRecipeResolver recipeResolver =
                recipeResolverProvider == null ? null : recipeResolverProvider.getIfAvailable();

        for (AbstractResourceQueryController<?, ?, ?> controller : controllers) {
            if (controller == null) {
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(controller);
            BaseResourceQueryService<?, ?, ?> service =
                    invokeProtected(controller, "getService", BaseResourceQueryService.class);

            Set<String> candidatePaths = resolveControllerCanonicalPaths(controller, targetClass);
            if (candidatePaths.isEmpty()) {
                continue;
            }
            allowedPaths.addAll(candidatePaths);

            Set<String> filterDtoFields = extractFilterDtoFields(targetClass);
            Map<String, StatsFieldCapabilities> fieldCapabilities = extractStatsFieldCapabilities(service);
            if (!fieldCapabilities.isEmpty()) {
                ResourceStatsContract contract =
                        new ResourceStatsContract(
                                fieldCapabilities.keySet(), filterDtoFields, fieldCapabilities);
                for (String path : candidatePaths) {
                    statsByPath.put(path, contract);
                }
            }

            Set<String> projectionIds = extractAnalyticsProjectionIds(targetClass);
            for (String path : candidatePaths) {
                projectionsByPath.put(path, projectionIds);
            }

            if (recipeResolver != null) {
                List<ResolvedVerifiedAnalyticalRecipe> recipes =
                        resolveRecipesForController(recipeResolver, targetClass, controller, candidatePaths);
                if (!recipes.isEmpty()) {
                    Map<String, VerifiedRecipeSpec> specs = new LinkedHashMap<>();
                    for (ResolvedVerifiedAnalyticalRecipe recipe : recipes) {
                        if (recipe == null || !StringUtils.hasText(recipe.id())) {
                            continue;
                        }
                        Set<String> adjustable = new LinkedHashSet<>();
                        if (recipe.adjustableParams() != null) {
                            for (VerifiedAnalyticalRecipeDescriptor.ParameterSpec param :
                                    recipe.adjustableParams()) {
                                if (param != null && StringUtils.hasText(param.name())) {
                                    adjustable.add(param.name().trim());
                                }
                            }
                        }
                        specs.put(
                                recipe.id().trim(),
                                new VerifiedRecipeSpec(recipe.id().trim(), adjustable));
                    }
                    if (!specs.isEmpty()) {
                        Map<String, VerifiedRecipeSpec> immutableSpecs = Map.copyOf(specs);
                        for (String path : candidatePaths) {
                            recipesByPath.put(path, immutableSpecs);
                        }
                    }
                }
            }
        }

        Map<String, Map<String, VerifiedRecipeSpec>> immutableRecipes = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, VerifiedRecipeSpec>> entry : recipesByPath.entrySet()) {
            immutableRecipes.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return new CatalogSnapshot(
                Set.copyOf(allowedPaths),
                Map.copyOf(immutableRecipes),
                Map.copyOf(statsByPath),
                Map.copyOf(projectionsByPath));
    }

    private static Set<String> resolveControllerCanonicalPaths(
            AbstractResourceQueryController<?, ?, ?> controller, Class<?> targetClass) {
        List<String> rawCandidates = new ArrayList<>();
        String basePath = invokeProtected(controller, "getBasePath", String.class);
        if (StringUtils.hasText(basePath)) {
            rawCandidates.add(basePath);
        }
        ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(targetClass, ApiResource.class);
        if (apiResource != null) {
            for (String value : apiResource.value()) {
                rawCandidates.add(value);
            }
            for (String path : apiResource.path()) {
                rawCandidates.add(path);
            }
        }
        RequestMapping reqMapping = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);
        if (reqMapping != null) {
            for (String value : reqMapping.value()) {
                rawCandidates.add(value);
            }
            for (String path : reqMapping.path()) {
                rawCandidates.add(path);
            }
        }

        Set<String> canonicalPaths = new LinkedHashSet<>();
        for (String raw : rawCandidates) {
            UiLayoutKpiCatalogOracle.normalizeCanonicalResourcePath(raw)
                    .ifPresent(
                            canonical -> {
                                canonicalPaths.add(canonical);
                                if (canonical.startsWith("api/") && canonical.length() > 4) {
                                    UiLayoutKpiCatalogOracle.normalizeCanonicalResourcePath(
                                                    canonical.substring(4))
                                            .ifPresent(canonicalPaths::add);
                                }
                            });
        }
        return canonicalPaths;
    }

    private static Set<String> extractFilterDtoFields(Class<?> controllerClass) {
        ResolvableType resolved =
                ResolvableType.forClass(controllerClass).as(AbstractResourceQueryController.class);
        Class<?> filterDtoType = resolved.getGeneric(2).resolve(GenericFilterDTO.class);
        if (filterDtoType == null) {
            return Set.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        for (Class<?> current = filterDtoType;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                fields.add(field.getName());
            }
        }
        return Set.copyOf(fields);
    }

    private static Map<String, StatsFieldCapabilities> extractStatsFieldCapabilities(
            BaseResourceQueryService<?, ?, ?> service) {
        if (service == null) {
            return Map.of();
        }
        StatsFieldRegistry registry =
                invokeProtected(
                        service, "resolveEffectiveStatsFieldRegistry", StatsFieldRegistry.class);
        if (registry == null || registry.isEmpty()) {
            registry = service.getStatsFieldRegistry();
        }
        if (registry == null || registry.isEmpty()) {
            return Map.of();
        }
        Map<String, StatsFieldCapabilities> capabilities = new LinkedHashMap<>();
        for (StatsFieldDescriptor descriptor : registry.descriptors()) {
            if (descriptor == null || !StringUtils.hasText(descriptor.field())) {
                continue;
            }
            String fieldName = descriptor.field().trim();
            Set<String> allowedMetrics =
                    descriptor.metrics() == null
                            ? Set.of()
                            : descriptor.metrics().stream()
                                    .filter(Objects::nonNull)
                                    .map(Enum::name)
                                    .collect(Collectors.toUnmodifiableSet());
            capabilities.put(
                    fieldName,
                    new StatsFieldCapabilities(
                            fieldName,
                            allowedMetrics,
                            descriptor.groupByEligible(),
                            descriptor.timeSeriesEligible(),
                            descriptor.distributionTermsEligible(),
                            descriptor.distributionHistogramEligible(),
                            descriptor.metricFieldEligible()));
        }
        return capabilities.isEmpty() ? Map.of() : Map.copyOf(capabilities);
    }

    private static Set<String> extractAnalyticsProjectionIds(Class<?> targetClass) {
        if (targetClass == null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        UiAnalytics classAnalytics =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, UiAnalytics.class);
        collectProjectionIds(classAnalytics, ids);
        for (Method method : ReflectionUtils.getAllDeclaredMethods(targetClass)) {
            UiAnalytics methodAnalytics =
                    AnnotatedElementUtils.findMergedAnnotation(method, UiAnalytics.class);
            collectProjectionIds(methodAnalytics, ids);
        }
        return ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    private static void collectProjectionIds(UiAnalytics analytics, Set<String> out) {
        if (analytics == null || analytics.projections() == null) {
            return;
        }
        for (AnalyticsProjection projection : analytics.projections()) {
            if (projection != null && StringUtils.hasText(projection.id())) {
                out.add(projection.id().trim());
            }
        }
    }

    private static List<ResolvedVerifiedAnalyticalRecipe> resolveRecipesForController(
            VerifiedAnalyticalRecipeResolver resolver,
            Class<?> targetClass,
            Object controller,
            Set<String> canonicalPaths) {
        for (String canonical : canonicalPaths) {
            List<String> lookups = List.of("/" + canonical, canonical, "/api/" + canonical);
            for (String lookup : lookups) {
                List<ResolvedVerifiedAnalyticalRecipe> found = resolver.resolveByResourcePath(lookup);
                if (found != null && !found.isEmpty()) {
                    return found;
                }
            }
        }
        List<ResolvedVerifiedAnalyticalRecipe> byController =
                resolver.resolveByController(targetClass, controller);
        return byController == null ? List.of() : byController;
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeProtected(Object target, String methodName, Class<T> returnType) {
        Method method = ReflectionUtils.findMethod(target.getClass(), methodName);
        if (method == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(method);
        Object value = ReflectionUtils.invokeMethod(method, target);
        return returnType.isInstance(value) ? (T) value : null;
    }

    private record CatalogSnapshot(
            Set<String> allowedPaths,
            Map<String, Map<String, VerifiedRecipeSpec>> recipesByPath,
            Map<String, ResourceStatsContract> statsByPath,
            Map<String, Set<String>> projectionsByPath) {}
}
