package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Implementação padrão de {@link PublishedUiLayoutBaselineProvider} apoiada no
 * {@link UiLayoutCompositionReleaseReader} ativo de {@code ui-layouts}.
 */
public final class ReleaseReaderPublishedUiLayoutBaselineProvider
        implements PublishedUiLayoutBaselineProvider {

    private static final String DEFAULT_PUBLISHED_ENVIRONMENT = "prod";
    private static final String CONTEXT_VERSION_HEADER = "X-Praxis-Context-Version";
    private static final Set<String> SUPPORTED_COMPONENT_TYPES =
            Set.of("praxis-dynamic-page", "praxis-kpi-strip", "praxis-kpi-card");
    private static final List<String> KNOWN_ID_PREFIXES =
            List.of(
                    "praxis-dynamic-page:",
                    "dynamic-page:",
                    "praxis-page:",
                    "page:",
                    "praxis-kpi-strip:",
                    "kpi-strip:",
                    "praxis-kpi-card:",
                    "kpi-card:");

    private final UiLayoutCompositionReleaseReader releaseReader;
    private final ObjectMapper objectMapper;
    private final EffectiveUiAudienceProvider audienceProvider;
    private final Supplier<RequestAuthContext> authContextSupplier;

    public ReleaseReaderPublishedUiLayoutBaselineProvider(
            UiLayoutCompositionReleaseReader releaseReader, ObjectMapper objectMapper) {
        this(releaseReader, objectMapper, null, ReleaseReaderPublishedUiLayoutBaselineProvider::currentRequestAuthContext);
    }

    public ReleaseReaderPublishedUiLayoutBaselineProvider(
            UiLayoutCompositionReleaseReader releaseReader,
            ObjectMapper objectMapper,
            EffectiveUiAudienceProvider audienceProvider) {
        this(
                releaseReader,
                objectMapper,
                audienceProvider,
                ReleaseReaderPublishedUiLayoutBaselineProvider::currentRequestAuthContext);
    }

    ReleaseReaderPublishedUiLayoutBaselineProvider(
            UiLayoutCompositionReleaseReader releaseReader,
            ObjectMapper objectMapper,
            EffectiveUiAudienceProvider audienceProvider,
            Supplier<RequestAuthContext> authContextSupplier) {
        this.releaseReader =
                Objects.requireNonNull(releaseReader, "releaseReader must not be null");
        this.objectMapper =
                objectMapper != null
                        ? objectMapper
                        : new ObjectMapper().findAndRegisterModules();
        this.audienceProvider = audienceProvider;
        this.authContextSupplier =
                authContextSupplier != null
                        ? authContextSupplier
                        : ReleaseReaderPublishedUiLayoutBaselineProvider::currentRequestAuthContext;
    }

    @Override
    public Optional<JsonNode> findActivePublishedDocument(
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment) {
        return findActivePublishedBaseline(tenantId, userId, componentType, componentId, environment)
                .map(PublishedBaseline::document);
    }

    @Override
    public Optional<PublishedBaseline> findActivePublishedBaseline(
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment) {
        if (!StringUtils.hasText(tenantId)
                || !StringUtils.hasText(componentType)
                || !StringUtils.hasText(componentId)) {
            return Optional.empty();
        }
        String normalizedType = componentType.trim();
        if (!SUPPORTED_COMPONENT_TYPES.contains(normalizedType)) {
            return Optional.empty();
        }
        String normalizedTenant = tenantId.trim();
        String normalizedUser = StringUtils.hasText(userId) ? userId.trim() : null;
        String effectiveEnvironment =
                StringUtils.hasText(environment)
                        ? environment.trim()
                        : DEFAULT_PUBLISHED_ENVIRONMENT;

        EffectiveUiAudience resolvedAudience =
                resolveTrustedAudience(normalizedTenant, normalizedUser);

        for (String candidateId : candidateIds(componentId.trim())) {
            UiLayoutTarget target = new UiLayoutTarget(normalizedType, candidateId);
            UiLayoutCompositionReleaseSnapshot snapshot;
            try {
                snapshot = releaseReader.read(normalizedTenant, effectiveEnvironment, target);
            } catch (UiLayoutResolutionException ex) {
                if (ex.code() == UiLayoutResolutionException.Code.LAYOUT_SOURCE_UNAVAILABLE) {
                    continue;
                }
                throw ex;
            }
            if (snapshot == null || snapshot.releaseRef() == null || snapshot.contributions().isEmpty()) {
                continue;
            }
            List<UiLayoutResolutionCandidate> matching = new ArrayList<>();
            for (UiLayoutCompositionContribution contribution : snapshot.contributions()) {
                if (contribution == null || contribution.candidate() == null) {
                    continue;
                }
                UiLayoutResolutionCandidate candidate = contribution.candidate();
                if (!target.equals(candidate.target())) {
                    continue;
                }
                UiLayoutAudienceSelector selector = candidate.selector();
                if (selector == null || !normalizedTenant.equals(selector.tenant())) {
                    continue;
                }
                if (resolvedAudience != null) {
                    if (!selector.matches(resolvedAudience)) {
                        continue;
                    }
                } else if (!matchesUnqualifiedLayer(candidate.layerClass(), selector, normalizedUser)) {
                    continue;
                }
                matching.add(candidate);
            }
            if (matching.isEmpty()) {
                continue;
            }
            matching.sort(
                    Comparator.comparing(UiLayoutResolutionCandidate::layerClass)
                            .thenComparingInt(UiLayoutResolutionCandidate::priority)
                            .thenComparing(UiLayoutResolutionCandidate::revisionRef));

            ObjectNode merged = objectMapper.createObjectNode();
            for (UiLayoutResolutionCandidate candidate : matching) {
                JsonNode patch = candidate.patch();
                if (patch instanceof ObjectNode patchObj) {
                    UiLayoutMergePatch.apply(merged, patchObj);
                }
            }
            return Optional.of(new PublishedBaseline(computeVersionToken(snapshot.releaseRef(), matching), merged));
        }
        return Optional.empty();
    }

    private EffectiveUiAudience resolveTrustedAudience(
            String normalizedTenant, String normalizedUser) {
        if (audienceProvider == null) {
            return null;
        }
        RequestAuthContext auth = authContextSupplier.get();
        if (auth == null || auth.principal() == null || !StringUtils.hasText(auth.principal().getName())) {
            return null;
        }
        try {
            EffectiveUiAudience audience =
                    audienceProvider.resolve(auth.principal(), auth.contextVersion());
            if (audience == null || !normalizedTenant.equals(audience.tenant())) {
                return null;
            }
            if (normalizedUser != null && !normalizedUser.equals(audience.user())) {
                return null;
            }
            return audience;
        } catch (UiLayoutResolutionException | IllegalArgumentException | IllegalStateException ex) {
            // Host fallback audience provider or missing context version -> fail safe to TENANT (+ exact USER without scoped layers)
            return null;
        }
    }

    private static boolean matchesUnqualifiedLayer(
            UiLayoutResolutionCandidate.LayerClass layerClass,
            UiLayoutAudienceSelector selector,
            String normalizedUser) {
        if (layerClass == UiLayoutResolutionCandidate.LayerClass.TENANT) {
            return selector.organization() == null
                    && selector.sector() == null
                    && selector.group() == null
                    && selector.profile() == null
                    && selector.user() == null;
        }
        if (layerClass == UiLayoutResolutionCandidate.LayerClass.USER) {
            return normalizedUser != null
                    && normalizedUser.equals(selector.user())
                    && selector.organization() == null
                    && selector.sector() == null
                    && selector.group() == null
                    && selector.profile() == null;
        }
        // Without a verified EffectiveUiAudience, never cross-contaminate ORGANIZATION/SECTOR/GROUP/PROFILE layers
        return false;
    }

    private static String computeVersionToken(
            String releaseRef, List<UiLayoutResolutionCandidate> matching) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((releaseRef + "|").getBytes(StandardCharsets.UTF_8));
            for (UiLayoutResolutionCandidate candidate : matching) {
                digest.update(
                        (candidate.revisionRef() + ":" + candidate.contentHash() + "|")
                                .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to digest baseline version token.", ex);
        }
    }

    private static RequestAuthContext currentRequestAuthContext() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            var req = servletAttrs.getRequest();
            String contextVersion = req.getHeader(CONTEXT_VERSION_HEADER);
            return new RequestAuthContext(
                    req.getUserPrincipal(),
                    StringUtils.hasText(contextVersion) ? contextVersion.trim() : null);
        }
        return null;
    }

    record RequestAuthContext(Principal principal, String contextVersion) {}

    private static List<String> candidateIds(String rawComponentId) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(rawComponentId);
        for (String prefix : KNOWN_ID_PREFIXES) {
            if (rawComponentId.startsWith(prefix) && rawComponentId.length() > prefix.length()) {
                ids.add(rawComponentId.substring(prefix.length()).trim());
            }
        }
        return List.copyOf(ids);
    }
}
