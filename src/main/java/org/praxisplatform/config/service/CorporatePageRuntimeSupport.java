package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;
import org.praxisplatform.config.dto.PageSourceReference;
import org.praxisplatform.config.dto.UiLayoutPageChildSource;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.CorporatePageCatalogContributor.NativePageSeed;
import org.praxisplatform.config.service.CorporatePageCatalogContributor.NavigationGroupSeed;
import org.praxisplatform.config.service.CorporatePageCatalogContributor.ResolvedActorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coordenador coeso dos 10 SPIs de páginas corporativas alimentado por um
 * {@link CorporatePageCatalogContributor}.
 */
public final class CorporatePageRuntimeSupport {

    static final UiLayoutPatchDocumentDescriptor CANONICAL_PATCH_DESCRIPTOR =
            new UiLayoutPatchDocumentDescriptor("praxis:corporate-page:patch", "praxis.ui-layout/v1");
    public static final String COMPOSED_WIDGET_SOURCE_PREFIX = "composed-widget:";
    public static final String COMPOSED_WIDGET_SOURCE_REVISION = "1";
    private static final String DEFAULT_TENANT = "default";
    private static final String DEFAULT_ENV = "prod";
    private static final String DEFAULT_UNIT = "corporate";
    private static final String PRODUCER_REF = "praxis-config-starter:corporate-pages";
    private static final String PUBLICATION_REF = "corporate-pages:v1";

    private static final Set<String> CANONICAL_ICONS = Set.of(
            "home", "dashboard", "analytics", "insights", "monitoring", "query_stats",
            "table_chart", "view_quilt", "grid_view", "list", "list_alt", "fact_check",
            "checklist", "assignment", "badge", "groups", "person", "supervisor_account",
            "manage_accounts", "account_tree", "work", "work_outline", "business",
            "corporate_fare", "apartment", "account_balance", "payments", "attach_money",
            "receipt_long", "event", "event_note", "calendar_month", "schedule", "history",
            "timeline", "trending_up", "stairs", "folder", "folder_open", "description",
            "article", "policy", "gavel", "rule", "verified", "security", "shield", "lock",
            "tune", "settings", "hub", "upload_file", "cloud_upload", "web", "star",
            "campaign", "info", "warning", "check_circle", "error", "label", "category",
            "layers", "widgets", "extension", "auto_awesome");

    private final CorporatePageCatalogContributor contributor;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ObjectProvider<PageRuntimeReadService> pageRuntimeReadServiceProvider;
    private final Set<String> allowedIcons;
    private final Set<String> admittedComposedWidgetTypes;
    private final Map<String, NativePageSeed> nativeBySourceRevision = new ConcurrentHashMap<>();
    private final Map<UUID, NativePageSeed> nativeByPageId = new ConcurrentHashMap<>();

    public CorporatePageRuntimeSupport(
            CorporatePageCatalogContributor contributor,
            ObjectMapper mapper,
            Clock clock,
            ObjectProvider<PageRuntimeReadService> pageRuntimeReadServiceProvider) {
        this.contributor = Objects.requireNonNull(contributor, "contributor is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.pageRuntimeReadServiceProvider = pageRuntimeReadServiceProvider;

        Set<String> icons = new LinkedHashSet<>(CANONICAL_ICONS);
        if (contributor.additionalAllowedIcons() != null) {
            for (String icon : contributor.additionalAllowedIcons()) {
                if (StringUtils.hasText(icon)) {
                    icons.add(icon.trim());
                }
            }
        }
        this.allowedIcons = Set.copyOf(icons);

        Set<String> composedTypes = new LinkedHashSet<>();
        if (contributor.admittedComposedWidgetTypes() != null) {
            for (String type : contributor.admittedComposedWidgetTypes()) {
                if (StringUtils.hasText(type)) {
                    composedTypes.add(type.trim());
                }
            }
        }
        this.admittedComposedWidgetTypes = Set.copyOf(composedTypes);

        for (NativePageSeed seed : contributor.nativePages()) {
            if (seed == null || !StringUtils.hasText(seed.sourceId()) || !StringUtils.hasText(seed.revision())) {
                continue;
            }
            nativeBySourceRevision.put(sourceKey(seed.sourceId().trim(), seed.revision().trim()), seed);
            if (seed.pageId() != null) {
                nativeByPageId.put(seed.pageId(), seed);
            }
        }
    }

    public UiLayoutLifecycleInvocationProvider invocationProvider() {
        return (principal, contextVersion, requestedRoot) -> {
            ResolvedActorContext actor = resolveAuthenticatedActor(principal, contextVersion);
            if (requestedRoot == null || !StringUtils.hasText(requestedRoot.componentType())
                    || !StringUtils.hasText(requestedRoot.componentId())) {
                throw new UiLayoutLifecycleException(
                        UiLayoutLifecycleException.Code.INVALID_REQUEST,
                        "Requested layout root is required.");
            }
            if (UiLayoutNavigationGroupsRevisionReproduction.TYPE.equals(requestedRoot.componentType())) {
                UiLayoutTarget canonicalRoot = groupsRootFor(actor.tenantId(), actor.environment());
                if (!canonicalRoot.equals(requestedRoot)) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.DENIED,
                            "Requested navigation-groups root does not match the host mapping.");
                }
            }
            return new UiLayoutLifecycleInvocation(
                    actor.actorRef(),
                    actor.tenantId(),
                    actor.administrativeUnit(),
                    actor.environment(),
                    actor.contextVersion(),
                    new UiLayoutCompositionRegistration(requestedRoot, List.of(requestedRoot)));
        };
    }

    public PageLifecycleAdmission pageLifecycleAdmission() {
        return new PageLifecycleAdmission() {
            @Override
            public void require(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {
                requireValidInvocation(invocation);
                contributor.checkLifecycleOperation(operation, invocation);
            }

            @Override
            public PageCreationContext requirePage(
                    UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {
                require(operation, invocation);
                return resolveLifecycleContext(invocation);
            }
        };
    }

    public EffectiveUiAudienceProvider audienceProvider() {
        return (principal, expectedContextVersion) -> {
            ResolvedActorContext actor = resolveAuthenticatedActor(principal, expectedContextVersion);
            return new EffectiveUiAudience(
                    actor.tenantId(),
                    actor.environment(),
                    actor.actorRef(),
                    actor.organization(),
                    actor.sector(),
                    actor.profile(),
                    actor.groups(),
                    actor.contextVersion());
        };
    }

    public EnterpriseRuntimeNavigationProvider navigationProvider() {
        return (EnterpriseRuntimeContextRequest request,
                String expectedContextVersion,
                String parentRef,
                String cursor,
                int pageSize) -> {
            PageRuntimeReadService runtime =
                    pageRuntimeReadServiceProvider == null
                            ? null
                            : pageRuntimeReadServiceProvider.getIfAvailable();
            if (runtime == null) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Enterprise runtime navigation requires an active published-page runtime.");
            }
            return runtime.navigation(request, expectedContextVersion, parentRef, cursor, pageSize);
        };
    }

    public UiLayoutDraftWorkspaceSource draftWorkspaceSource() {
        return new UiLayoutDraftWorkspaceSource() {
            @Override
            public UiLayoutDraftWorkspaceSeed capture(
                    UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation) {
                validation.require(invocation);
                UiLayoutTarget root = invocation.rootTarget();
                if (UiLayoutNavigationGroupsRevisionReproduction.TYPE.equals(root.componentType())) {
                    UiLayoutTarget expected = groupsRootFor(invocation.tenantId(), invocation.environment());
                    if (!expected.equals(root)) {
                        throw new UiLayoutLifecycleException(
                                UiLayoutLifecycleException.Code.DENIED,
                                "Navigation groups root mismatch.");
                    }
                    JsonNode groupsDoc = buildGroupsDocument(contributor.navigationGroups());
                    UiLayoutAuthoringDocumentDescriptor descriptor =
                            new UiLayoutAuthoringDocumentDescriptor(
                                    UiLayoutNavigationGroupsRevisionReproduction.KIND,
                                    contributor.serviceKey() + ":navigation-groups",
                                    "1");
                    String docRef = "corporate-groups:" + root.componentId();
                    UiLayoutBaselineMetadataSeed metadata =
                            nativeMetadata(invocation, docRef, "1", groupsDoc);
                    return new UiLayoutDraftWorkspaceSeed(List.of(
                            new UiLayoutDraftWorkspaceSeed.TargetSeed(
                                    root,
                                    descriptor,
                                    CANONICAL_PATCH_DESCRIPTOR,
                                    docRef,
                                    groupsDoc,
                                    metadata)));
                }
                if (UiLayoutPageRevisionReproduction.TYPE.equals(root.componentType())) {
                    UUID pageId = parseUuid(root.componentId());
                    NativePageSeed registered = nativeByPageId.get(pageId);
                    if (registered == null) {
                        throw new UiLayoutLifecycleException(
                                UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                                "Native page baseline is not registered for target: " + root.componentId());
                    }
                    return new UiLayoutDraftWorkspaceSeed(
                            List.of(buildPageTargetSeed(invocation, root, registered)));
                }
                throw new UiLayoutLifecycleException(
                        UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                        "Unsupported corporate draft target: " + root.componentType());
            }

            @Override
            public UiLayoutPageChildSeed capturePageChild(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget page,
                    UiLayoutPageChildSource request,
                    UiLayoutValidationContext validation) {
                validation.require(invocation);
                if (request == null || !(request.source() instanceof UiLayoutPageChildSource.NativeSource nativeSrc)) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                            "Only registered-native corporate page child sources are supported.");
                }
                if (isComposedWidgetSource(nativeSrc)) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                            "Composed page child capture requires a validated candidate document.");
                }
                NativePageSeed parent = findNativePageForChild(page, nativeSrc);
                if (parent == null) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                            "Corporate page child source is not registered.");
                }
                Map<String, ObjectNode> children = UiLayoutPageRevisionReproduction.children(parent.document());
                ObjectNode childDoc = children.get(request.widgetKey());
                if (childDoc == null) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.NOT_FOUND,
                            "Requested widget key does not belong to the registered native source.");
                }
                return new UiLayoutPageChildSeed(
                        request,
                        new UiLayoutAuthoringDocumentDescriptor(
                                childDoc.path("kind").asText(),
                                contributor.serviceKey() + ":page-child",
                                "1"),
                        CANONICAL_PATCH_DESCRIPTOR,
                        "corporate-child:" + request.widgetKey(),
                        childDoc,
                        nativeMetadata(invocation, nativeSrc.sourceId(), nativeSrc.revision(), childDoc));
            }

            @Override
            public UiLayoutPageChildSeed capturePageChild(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget page,
                    UiLayoutPageChildSource request,
                    JsonNode candidateChildDocument,
                    UiLayoutValidationContext validation) {
                validation.require(invocation);
                requireValidInvocation(invocation);
                if (request == null || !(request.source() instanceof UiLayoutPageChildSource.NativeSource nativeSrc)) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                            "Only registered-native corporate page child sources are supported.");
                }
                if (isComposedWidgetSource(nativeSrc)) {
                    String childKind = validateComposedWidgetCandidate(
                            invocation, page, request.widgetKey(), nativeSrc, candidateChildDocument);
                    ObjectNode normalizedDoc = candidateChildDocument.deepCopy();
                    return new UiLayoutPageChildSeed(
                            request,
                            new UiLayoutAuthoringDocumentDescriptor(
                                    childKind,
                                    contributor.serviceKey() + ":page-child",
                                    "1"),
                            CANONICAL_PATCH_DESCRIPTOR,
                            "corporate-child:" + request.widgetKey(),
                            normalizedDoc,
                            nativeMetadata(
                                    invocation,
                                    nativeSrc.sourceId(),
                                    COMPOSED_WIDGET_SOURCE_REVISION,
                                    normalizedDoc));
                }
                return capturePageChild(invocation, page, request, validation);
            }

            @Override
            public void requirePageChildEvidence(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget page,
                    UiLayoutPageChildSeed seed,
                    UiLayoutValidationContext validation) {
                validation.require(invocation);
                requireValidInvocation(invocation);
                requireNativeSeedObservation(invocation, seed.metadata());
                if (!(seed.request().source() instanceof UiLayoutPageChildSource.NativeSource nativeSrc)) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.DENIED,
                            "Unsupported page child source.");
                }
                if (isComposedWidgetSource(nativeSrc)) {
                    validateComposedWidgetCandidate(
                            invocation, page, seed.request().widgetKey(), nativeSrc, seed.document());
                    return;
                }
                NativePageSeed parent = findNativePageForChild(page, nativeSrc);
                if (parent == null) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.DENIED,
                            "Page child source is not admitted.");
                }
                ObjectNode expectedChild =
                        UiLayoutPageRevisionReproduction.children(parent.document())
                                .get(seed.request().widgetKey());
                if (expectedChild == null || !expectedChild.equals(seed.document())) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.VALIDATION_FAILED,
                            "Page child evidence does not match registered native origin.");
                }
            }
        };
    }

    private static boolean isComposedWidgetSource(UiLayoutPageChildSource.NativeSource nativeSrc) {
        return nativeSrc != null
                && StringUtils.hasText(nativeSrc.sourceId())
                && nativeSrc.sourceId().startsWith(COMPOSED_WIDGET_SOURCE_PREFIX);
    }

    private String validateComposedWidgetCandidate(
            UiLayoutLifecycleInvocation invocation,
            UiLayoutTarget page,
            String widgetKey,
            UiLayoutPageChildSource.NativeSource nativeSrc,
            JsonNode childDocument) {
        if (!"registered-native".equals(nativeSrc.kind())
                || !COMPOSED_WIDGET_SOURCE_REVISION.equals(nativeSrc.revision())) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                    "Composed page child source revision is unsupported.");
        }
        String widgetType = nativeSrc.sourceId().substring(COMPOSED_WIDGET_SOURCE_PREFIX.length());
        if (!StringUtils.hasText(widgetType) || !admittedComposedWidgetTypes.contains(widgetType)) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.DENIED,
                    "Composed widget type is not admitted for corporate multi-widget lifecycle: " + widgetType);
        }
        if (childDocument == null || !childDocument.isObject()) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                    "Composed page child document is unavailable.");
        }
        String childKind = childDocument.path("kind").asText("");
        String resolvedComponentType;
        try {
            resolvedComponentType =
                    UiLayoutDeclarativeWidgetRevisionReproduction.resolveComponentTypeForDocumentType(childKind);
        } catch (UiLayoutLifecycleException unsupportedKind) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.VALIDATION_FAILED,
                    "Unsupported composed widget authoring document kind: " + childKind);
        }
        if (!widgetType.equals(resolvedComponentType)) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.VALIDATION_FAILED,
                    "Composed widget document kind does not match declared source type: " + widgetType);
        }
        UiLayoutPageRevisionReproduction.requireChild(childDocument, childDocument, widgetKey);
        try {
            contributor.checkComposedWidgetAdmission(invocation, page, widgetType, childDocument);
        } catch (ResponseStatusException statusEx) {
            UiLayoutLifecycleException.Code mapped =
                    statusEx.getStatusCode().value() == 401 || statusEx.getStatusCode().value() == 403
                            ? UiLayoutLifecycleException.Code.DENIED
                            : UiLayoutLifecycleException.Code.VALIDATION_FAILED;
            throw new UiLayoutLifecycleException(
                    mapped,
                    statusEx.getReason() != null
                            ? statusEx.getReason()
                            : "Composed widget admission denied.");
        }
        return childKind;
    }

    public UiLayoutBaselineMetadataAdmission baselineMetadataAdmission() {
        return (invocation, target, authoring, baselineSourceRef, baselineDocument, metadata) -> {
            requireValidInvocation(invocation);
            requireNativeSeedObservation(invocation, metadata);
            if (!contributor.serviceKey().equals(metadata.source().serviceKey())) {
                throw new UiLayoutLifecycleException(
                        UiLayoutLifecycleException.Code.DENIED,
                        "Baseline metadata service identity is not admitted by this host.");
            }
        };
    }

    public UiLayoutValidationBudgetPolicy validationBudgetPolicy() {
        return (operation, invocation) -> {
            requireValidInvocation(invocation);
            Duration configured = contributor.validationBudget();
            if (configured == null || configured.isZero() || configured.isNegative()) {
                return Duration.ofSeconds(30);
            }
            Duration max = Duration.ofMinutes(5);
            return configured.compareTo(max) > 0 ? max : configured;
        };
    }

    public UiLayoutReleaseValidator releaseValidator() {
        return new CanonicalUiLayoutReleaseValidator();
    }

    public UiLayoutLifecycleStructureValidator structureValidator() {
        return new UiLayoutLifecycleStructureValidator() {
            @Override
            public void validateTarget(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget target,
                    UiLayoutValidationContext validation) {
                validation.require(invocation);
                if (target == null
                        || !StringUtils.hasText(target.componentType())
                        || !StringUtils.hasText(target.componentId())) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.INVALID_REQUEST,
                            "Layout target is invalid.");
                }
                if (UiLayoutNavigationGroupsRevisionReproduction.TYPE.equals(target.componentType())) {
                    UiLayoutTarget expected = groupsRootFor(invocation.tenantId(), invocation.environment());
                    if (!expected.equals(target)) {
                        throw new UiLayoutLifecycleException(
                                UiLayoutLifecycleException.Code.DENIED,
                                "Navigation groups target does not match host registration.");
                    }
                }
            }

            @Override
            public void validateAuthoringBaseline(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget target,
                    UiLayoutAuthoringDocumentDescriptor descriptor,
                    JsonNode baselineDocument,
                    UiLayoutValidationContext validation) {
                UiLayoutNativeStructureChecks.requireBaseline(
                        invocation, target, descriptor, baselineDocument, CANONICAL_PATCH_DESCRIPTOR, validation);
                validateIcons(descriptor.documentType(), baselineDocument);
            }

            @Override
            public void validateAuthoringRevision(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget target,
                    UiLayoutAuthoringDocumentDescriptor baselineDescriptor,
                    JsonNode baselineDocument,
                    UiLayoutAuthoringDocumentDescriptor candidateDescriptor,
                    JsonNode candidateDocument,
                    UiLayoutPatchDocumentDescriptor patchDescriptor,
                    JsonNode patchDocument,
                    UiLayoutValidationContext validation) {
                UiLayoutNativeStructureChecks.requireRevision(
                        invocation,
                        target,
                        baselineDescriptor,
                        baselineDocument,
                        candidateDescriptor,
                        candidateDocument,
                        patchDescriptor,
                        patchDocument,
                        validation);
                validateIcons(candidateDescriptor.documentType(), candidateDocument);
            }

            @Override
            public void validatePatch(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget target,
                    JsonNode patch,
                    UiLayoutValidationContext validation) {
                validation.require(invocation);
                if (patch == null || !patch.isObject()) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.VALIDATION_FAILED,
                            "Patch document must be a JSON object.");
                }
            }

            @Override
            public void validateAssignment(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget target,
                    UiLayoutResolutionCandidate.LayerClass layerClass,
                    UiLayoutAudienceSelector selector,
                    UiLayoutValidationContext validation) {
                validation.require(invocation);
            }

            @Override
            public void validateReleaseTargets(
                    UiLayoutLifecycleInvocation invocation,
                    List<UiLayoutTarget> targets,
                    UiLayoutValidationContext validation) {
                validation.require(invocation);
                if (targets == null || targets.isEmpty() || !targets.equals(invocation.composition().targets())) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.VALIDATION_FAILED,
                            "Release targets do not match registered composition.");
                }
            }
        };
    }

    public PageCreationHost pageCreationHost() {
        return new PageCreationHost() {
            @Override
            public PageCreationContext resolve(Principal principal, String contextVersion) {
                ResolvedActorContext actor = resolveAuthenticatedActor(principal, contextVersion);
                UiLayoutTarget root = groupsRootFor(actor.tenantId(), actor.environment());
                UiLayoutLifecycleInvocation invocation =
                        new UiLayoutLifecycleInvocation(
                                actor.actorRef(),
                                actor.tenantId(),
                                actor.administrativeUnit(),
                                actor.environment(),
                                actor.contextVersion(),
                                new UiLayoutCompositionRegistration(root, List.of(root)));
                return new PageCreationContext(
                        contributor.applicationId(), contributor.routeNamespace(), invocation);
            }

            @Override
            public PageCreationContext resolveLifecycle(UiLayoutLifecycleInvocation invocation) {
                return resolveLifecycleContext(invocation);
            }

            @Override
            public UiLayoutTarget navigationGroupsRoot(PageCreationContext currentContext) {
                if (currentContext == null
                        || !contributor.applicationId().equals(currentContext.applicationId())
                        || !contributor.routeNamespace().equals(currentContext.routeNamespace())) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.DENIED,
                            "Page creation context application or namespace is not admitted.");
                }
                return groupsRootFor(
                        currentContext.invocation().tenantId(),
                        currentContext.invocation().environment());
            }

            @Override
            public void require(
                    PageCreationOperation operation,
                    PageCreationContext context,
                    PageSourceReference source) {
                requireLifecycleContextMatch(context);
                contributor.checkPageCreation(operation, context, source);
            }

            @Override
            public PageCreationContext requireCreation(
                    Principal principal,
                    String version,
                    PageCreationContext expected,
                    PageSourceReference source) {
                PageCreationContext observed = resolve(principal, version);
                if (!observed.equals(expected)) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.CONTEXT_STALE,
                            "Page creation authority context changed.");
                }
                require(PageCreationOperation.CREATE_PAGE, observed, null);
                require(PageCreationOperation.READ_SOURCE, observed, source);
                require(PageCreationOperation.COPY_SOURCE, observed, source);
                return observed;
            }

            @Override
            public UiLayoutDraftWorkspaceSeed.TargetSeed captureNative(
                    PageCreationContext context, PageSourceReference.Native source) {
                requireLifecycleContextMatch(context);
                NativePageSeed registered =
                        nativeBySourceRevision.get(sourceKey(source.sourceId(), source.revision()));
                if (registered == null) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                            "Registered native page source was not found: " + source.sourceId());
                }
                UiLayoutTarget target = new UiLayoutTarget(
                        UiLayoutPageRevisionReproduction.TYPE,
                        registered.pageId() != null
                                ? registered.pageId().toString()
                                : UUID.randomUUID().toString());
                return buildPageTargetSeed(context.invocation(), target, registered);
            }

            @Override
            public PageCreationContext requireNativeProvenance(
                    PageCreationContext context,
                    PageSourceReference.Native source,
                    UiLayoutDraftWorkspaceSeed.TargetSeed captured) {
                PageCreationContext observed = resolveLifecycle(context.invocation());
                if (!observed.equals(context)) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.CONTEXT_STALE,
                            "Page provenance context is stale.");
                }
                requireNative(observed, source, captured);
                return observed;
            }

            @Override
            public void requireNative(
                    PageCreationContext context,
                    PageSourceReference.Native source,
                    UiLayoutDraftWorkspaceSeed.TargetSeed captured) {
                requireLifecycleContextMatch(context);
                NativePageSeed registered =
                        nativeBySourceRevision.get(sourceKey(source.sourceId(), source.revision()));
                if (registered == null || captured == null || !registered.document().equals(captured.document())) {
                    throw new UiLayoutLifecycleException(
                            UiLayoutLifecycleException.Code.DENIED,
                            "Captured native document does not match registered host source.");
                }
            }
        };
    }

    private PageCreationContext resolveLifecycleContext(UiLayoutLifecycleInvocation invocation) {
        requireValidInvocation(invocation);
        if (UiLayoutNavigationGroupsRevisionReproduction.TYPE.equals(invocation.rootTarget().componentType())) {
            UiLayoutTarget expected = groupsRootFor(invocation.tenantId(), invocation.environment());
            if (!expected.equals(invocation.rootTarget())) {
                throw new UiLayoutLifecycleException(
                        UiLayoutLifecycleException.Code.DENIED,
                        "Navigation groups root does not match host registration.");
            }
        }
        return new PageCreationContext(
                contributor.applicationId(), contributor.routeNamespace(), invocation);
    }

    private void requireLifecycleContextMatch(PageCreationContext context) {
        if (context == null
                || !contributor.applicationId().equals(context.applicationId())
                || !contributor.routeNamespace().equals(context.routeNamespace())) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.DENIED,
                    "Page creation context mismatch.");
        }
        requireValidInvocation(context.invocation());
    }

    private ResolvedActorContext resolveAuthenticatedActor(Principal principal, String contextVersion) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.SESSION_REQUIRED,
                    "Authenticated principal is required for page layout operations.");
        }
        if (!StringUtils.hasText(contextVersion)) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.CONTEXT_STALE,
                    "Context version is required.");
        }
        String trimmedVersion = contextVersion.trim();
        Optional<ResolvedActorContext> contributed =
                contributor.resolveActor(principal, trimmedVersion);
        if (contributed.isPresent()) {
            ResolvedActorContext resolved = contributed.get();
            if (!trimmedVersion.equals(resolved.contextVersion())) {
                throw new UiLayoutLifecycleException(
                        UiLayoutLifecycleException.Code.CONTEXT_STALE,
                        "Context version does not match host authority.");
            }
            return resolved;
        }
        return new ResolvedActorContext(
                principal.getName().trim(),
                DEFAULT_TENANT,
                DEFAULT_UNIT,
                DEFAULT_ENV,
                null,
                null,
                null,
                Set.of(),
                trimmedVersion);
    }

    private void requireValidInvocation(UiLayoutLifecycleInvocation invocation) {
        if (invocation == null
                || !StringUtils.hasText(invocation.actorRef())
                || !StringUtils.hasText(invocation.tenantId())
                || !StringUtils.hasText(invocation.administrativeUnit())
                || !StringUtils.hasText(invocation.environment())
                || !StringUtils.hasText(invocation.contextVersion())) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.CONTEXT_STALE,
                    "Lifecycle invocation context is incomplete.");
        }
    }

    private void requireNativeSeedObservation(
            UiLayoutLifecycleInvocation invocation, UiLayoutBaselineMetadataSeed metadata) {
        if (metadata == null || metadata.observation() == null) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.VALIDATION_FAILED,
                    "Baseline observation evidence is required.");
        }
        UiLayoutBaselineMetadataSeed.Observation obs = metadata.observation();
        if (!invocation.actorRef().equals(obs.actorRef())
                || !invocation.administrativeUnit().equals(obs.administrativeUnit())
                || !invocation.contextVersion().equals(obs.contextVersion())) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.CONTEXT_STALE,
                    "Baseline observation does not match current invocation context.");
        }
    }

    private UiLayoutDraftWorkspaceSeed.TargetSeed buildPageTargetSeed(
            UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, NativePageSeed seed) {
        Map<String, ObjectNode> children = UiLayoutPageRevisionReproduction.children(seed.document());
        List<UiLayoutPageChildSeed> childSeeds = new ArrayList<>(children.size());
        for (Map.Entry<String, ObjectNode> entry : children.entrySet()) {
            String key = entry.getKey();
            ObjectNode childDoc = entry.getValue();
            UiLayoutPageChildSource req = new UiLayoutPageChildSource(
                    key,
                    new UiLayoutPageChildSource.NativeSource(
                            "registered-native", seed.sourceId(), seed.revision()));
            childSeeds.add(
                    new UiLayoutPageChildSeed(
                            req,
                            new UiLayoutAuthoringDocumentDescriptor(
                                    childDoc.path("kind").asText(),
                                    contributor.serviceKey() + ":page-child",
                                    "1"),
                            CANONICAL_PATCH_DESCRIPTOR,
                            "corporate-child:" + key,
                            childDoc,
                            nativeMetadata(invocation, seed.sourceId(), seed.revision(), childDoc)));
        }
        return new UiLayoutDraftWorkspaceSeed.TargetSeed(
                target,
                new UiLayoutAuthoringDocumentDescriptor(
                        UiLayoutPageRevisionReproduction.KIND,
                        contributor.serviceKey() + ":page",
                        "1"),
                CANONICAL_PATCH_DESCRIPTOR,
                "corporate-page:" + seed.sourceId() + "@" + seed.revision(),
                seed.document(),
                nativeMetadata(invocation, seed.sourceId(), seed.revision(), seed.document()),
                childSeeds);
    }

    private NativePageSeed findNativePageForChild(
            UiLayoutTarget page, UiLayoutPageChildSource.NativeSource source) {
        NativePageSeed bySource = nativeBySourceRevision.get(sourceKey(source.sourceId(), source.revision()));
        if (bySource != null) {
            return bySource;
        }
        if (page != null && StringUtils.hasText(page.componentId())) {
            try {
                return nativeByPageId.get(UUID.fromString(page.componentId()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private UiLayoutBaselineMetadataSeed nativeMetadata(
            UiLayoutLifecycleInvocation invocation, String documentRef, String revision, JsonNode doc) {
        return new UiLayoutBaselineMetadataSeed(
                new UiLayoutBaselineMetadataSeed.NativeDocumentSource(
                        contributor.serviceKey(), documentRef, revision, PRODUCER_REF, PUBLICATION_REF),
                new UiLayoutBaselineMetadataSeed.NativeIdentity(),
                new UiLayoutBaselineMetadataSeed.Observation(
                        invocation.actorRef(),
                        invocation.administrativeUnit(),
                        invocation.contextVersion(),
                        contributor.applicationId() + ":policy",
                        "1",
                        Instant.now(clock)),
                doc.toString());
    }

    private JsonNode buildGroupsDocument(List<NavigationGroupSeed> groups) {
        ObjectNode root = mapper.createObjectNode()
                .put("kind", UiLayoutNavigationGroupsRevisionReproduction.KIND)
                .put("version", 1);
        ArrayNode array = root.putObject("config").putArray("groups");
        if (groups != null) {
            for (NavigationGroupSeed group : groups) {
                if (group == null) {
                    continue;
                }
                array.addObject()
                        .put("id", group.id().toString())
                        .put("label", group.label())
                        .put("icon", group.icon())
                        .put("order", group.order());
            }
        }
        return root;
    }

    private void validateIcons(String documentType, JsonNode document) {
        if (contributor.allowAnyMaterialIcon() || document == null) {
            return;
        }
        if (UiLayoutPageRevisionReproduction.KIND.equals(documentType)) {
            checkIconValue(document.at("/config/presentation/icon").textValue());
            for (JsonNode nav : document.at("/config/navigation")) {
                checkIconValue(nav.path("icon").textValue());
            }
            for (JsonNode widget : document.at("/config/definition/widgets")) {
                JsonNode shellIcon = widget.at("/shell/icon");
                if (shellIcon.isTextual()) {
                    checkIconValue(shellIcon.textValue());
                }
            }
        } else if (UiLayoutNavigationGroupsRevisionReproduction.KIND.equals(documentType)) {
            for (JsonNode group : document.at("/config/groups")) {
                checkIconValue(group.path("icon").textValue());
            }
        }
    }

    private void checkIconValue(String icon) {
        if (!StringUtils.hasText(icon) || !allowedIcons.contains(icon.trim())) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.VALIDATION_FAILED,
                    "Icon is not in the corporate admitted icon catalog: " + icon);
        }
    }

    private UiLayoutTarget groupsRootFor(String tenantId, String environment) {
        UUID rootId = contributor.navigationGroupsRootId(tenantId, environment);
        return new UiLayoutTarget(UiLayoutNavigationGroupsRevisionReproduction.TYPE, rootId.toString());
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.INVALID_REQUEST,
                    "Page componentId must be a valid UUID.");
        }
    }

    private static String sourceKey(String sourceId, String revision) {
        return sourceId + "::" + revision;
    }
}
