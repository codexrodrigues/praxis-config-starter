package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.praxisplatform.config.service.UserConfigService;

/**
 * Resolves the semantic source of an update from the exact persisted config identity.
 *
 * <p>The browser may report a source envelope for UX verification, but it is never authority for
 * a new authoring turn. This resolver reloads the source and materialization from
 * {@code ui_user_config}, verifies their canonical hashes and only then exposes the plan to the
 * internal planning request.</p>
 */
public class AgenticAuthoringPersistedUiCompositionSourceResolver {

    private static final String AUTHORING_SOURCE_SCHEMA_VERSION = "praxis.ui-authoring-source/v1";
    private static final String AUTHORING_SOURCE_KIND = "ui-composition-plan";
    private static final String UI_COMPOSITION_PLAN_KIND = "praxis.ui-composition-plan";
    private static final String UI_COMPOSITION_PLAN_VERSION = "1.0";
    private static final String MATERIALIZATION_KIND = "widget-page-definition";

    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;
    private final CanonicalJsonHashService canonicalJsonHashService;
    private final AiApiKeyProtectionService apiKeyProtectionService;

    public AgenticAuthoringPersistedUiCompositionSourceResolver(
            UserConfigService userConfigService,
            ObjectMapper objectMapper,
            CanonicalJsonHashService canonicalJsonHashService,
            AiApiKeyProtectionService apiKeyProtectionService) {
        this.userConfigService = Objects.requireNonNull(userConfigService, "userConfigService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.canonicalJsonHashService = Objects.requireNonNull(
                canonicalJsonHashService,
                "canonicalJsonHashService must not be null");
        this.apiKeyProtectionService = Objects.requireNonNull(
                apiKeyProtectionService,
                "apiKeyProtectionService must not be null");
    }

    /**
     * Reconciles a {@code create} apply target to {@code update} only when the persisted record
     * for the resolved principal identity is a soft-deleted tombstone ({@code tags.deleted=true})
     * or an empty canvas ({@code payload.widgets} empty). Active populated corporate pages are
     * never silently promoted from {@code create} to {@code update}, preserving optimistic
     * concurrency protection ({@code If-None-Match: *}).
     */
    AgenticAuthoringApplyTarget.Resolution reconcileApplyTarget(
            AiPrincipalContext principalContext,
            AgenticAuthoringApplyTarget.Resolution applyTargetResolution) {
        if (applyTargetResolution == null
                || !applyTargetResolution.valid()
                || !"create".equals(applyTargetResolution.target().mode())
                || principalContext == null
                || principalContext.tenantId() == null) {
            return applyTargetResolution;
        }
        AgenticAuthoringApplyTarget target = applyTargetResolution.target();
        UserConfigService.Scope scope = "user".equals(target.scope())
                ? UserConfigService.Scope.USER
                : UserConfigService.Scope.TENANT;
        try {
            UserConfigService.ResolvedConfig persisted = userConfigService.getByScope(
                            scope,
                            principalContext.tenantId(),
                            principalContext.userId(),
                            target.componentType(),
                            target.componentId(),
                            target.environment())
                    .orElse(null);
            if (persisted != null
                    && persisted.config() != null
                    && persisted.config().getEtag() != null
                    && isInactiveOrBlankPersistedConfig(persisted.config())) {
                return AgenticAuthoringApplyTarget.Resolution.resolved(new AgenticAuthoringApplyTarget(
                        target.schemaVersion(),
                        target.componentType(),
                        target.componentId(),
                        target.scope(),
                        target.environment(),
                        "update",
                        persisted.config().getEtag().toString()));
            }
        } catch (RuntimeException ignored) {
            // Keep caller target resolution; downstream persistence or source resolution will govern.
        }
        return applyTargetResolution;
    }

    Resolution resolve(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringApplyTarget.Resolution applyTargetResolution) {
        return resolveCurrentPage(
                request == null ? null : request.currentPage(),
                principalContext,
                applyTargetResolution);
    }

    /**
     * Resolves the trusted source for the synchronous HTTP preview boundary.
     *
     * <p>A target supplied by arbitrary caller context is not enough by itself: the target is
     * rebound to the server-resolved principal and the exact persisted ETag, materialization and
     * source hashes are verified before the semantic plan leaves this service.</p>
     */
    public JsonNode resolvePlanForPreview(
            AgenticAuthoringPlanRequest request,
            AiPrincipalContext principalContext) {
        AgenticAuthoringApplyTarget.Resolution applyTargetResolution =
                AgenticAuthoringApplyTarget.resolve(request, principalContext);
        boolean targetSupplied = request != null
                && request.contextHints() != null
                && request.contextHints().has("agenticApplyTarget");
        if (targetSupplied && !applyTargetResolution.valid()) {
            throw new IllegalStateException(applyTargetResolution.failureCode());
        }
        Resolution resolution = resolveCurrentPage(
                request == null ? null : request.currentPage(),
                principalContext,
                applyTargetResolution);
        if (!resolution.valid()) {
            throw new IllegalStateException(resolution.failureCode());
        }
        return resolution.plan();
    }

    private Resolution resolveCurrentPage(
            JsonNode currentPage,
            AiPrincipalContext principalContext,
            AgenticAuthoringApplyTarget.Resolution applyTargetResolution) {
        if (applyTargetResolution == null
                || !applyTargetResolution.valid()
                || !"update".equals(applyTargetResolution.target().mode())) {
            return Resolution.notRequired();
        }
        if (principalContext == null || principalContext.tenantId() == null) {
            return Resolution.blocked("persisted-ui-composition-principal-missing");
        }

        AgenticAuthoringApplyTarget target = applyTargetResolution.target();
        UserConfigService.Scope scope = "user".equals(target.scope())
                ? UserConfigService.Scope.USER
                : UserConfigService.Scope.TENANT;
        UserConfigService.ResolvedConfig persisted;
        try {
            persisted = userConfigService.getByScope(
                            scope,
                            principalContext.tenantId(),
                            principalContext.userId(),
                            target.componentType(),
                            target.componentId(),
                            target.environment())
                    .orElse(null);
        } catch (RuntimeException exception) {
            return Resolution.blocked("persisted-ui-composition-read-failed");
        }
        if (persisted == null) {
            return Resolution.blocked("persisted-ui-composition-config-not-found");
        }

        UiUserConfig config = persisted.config();
        if (config.getEtag() == null
                || !config.getEtag().toString().equals(AgenticAuthoringApplyTarget.normalizeEtag(target.baseEtag()))) {
            return Resolution.blocked("persisted-ui-composition-etag-mismatch");
        }

        if (isBlankCanvas(currentPage)) {
            return Resolution.notRequired();
        }

        String rawAuthoringSource = config.getAuthoringSource();
        if (rawAuthoringSource == null || rawAuthoringSource.isBlank()) {
            return Resolution.notRequired();
        }
        JsonNode authoringSource = readJson(rawAuthoringSource);
        if (authoringSource == null || authoringSource.isNull() || authoringSource.isMissingNode()) {
            return Resolution.blocked("persisted-ui-composition-authoring-source-invalid");
        }
        if (!authoringSource.isObject()
                || !AUTHORING_SOURCE_SCHEMA_VERSION.equals(authoringSource.path("schemaVersion").asText())
                || !AUTHORING_SOURCE_KIND.equals(authoringSource.path("kind").asText())) {
            return Resolution.blocked("persisted-ui-composition-authoring-source-invalid");
        }

        try {
            JsonNode plan = authoringSource.path("source");
            if (!plan.isObject()
                    || !UI_COMPOSITION_PLAN_KIND.equals(plan.path("kind").asText())
                    || !UI_COMPOSITION_PLAN_VERSION.equals(plan.path("version").asText())) {
                return Resolution.blocked("persisted-ui-composition-plan-invalid");
            }
            if (!canonicalJsonHashService.sha256(plan).equals(authoringSource.path("sourceSha256").asText())) {
                return Resolution.blocked("persisted-ui-composition-source-hash-mismatch");
            }

            JsonNode materialization = authoringSource.path("materialization");
            if (!materialization.isObject()
                    || !MATERIALIZATION_KIND.equals(materialization.path("kind").asText())
                    || !target.componentType().equals(materialization.path("componentType").asText())
                    || !target.componentId().equals(materialization.path("componentId").asText())) {
                return Resolution.blocked("persisted-ui-composition-materialization-identity-mismatch");
            }

            JsonNode persistedPage = readJson(config.getPayload());
            if (persistedPage == null || !persistedPage.isObject()) {
                return Resolution.blocked("persisted-ui-composition-materialization-invalid");
            }
            JsonNode publicPersistedPage = apiKeyProtectionService.sanitizeForResponse(persistedPage);
            String persistedPageHash = canonicalJsonHashService.sha256(publicPersistedPage);
            if (!persistedPageHash.equals(materialization.path("sha256").asText())) {
                return Resolution.blocked("persisted-ui-composition-materialization-hash-mismatch");
            }
            if (currentPage == null
                    || !persistedPageHash.equals(canonicalJsonHashService.sha256(currentPage))) {
                return Resolution.blocked("persisted-ui-composition-current-page-mismatch");
            }
            return Resolution.resolved(plan.deepCopy());
        } catch (RuntimeException exception) {
            return Resolution.blocked("persisted-ui-composition-validation-failed");
        }
    }

    private boolean isInactiveOrBlankPersistedConfig(UiUserConfig config) {
        if (config == null) {
            return true;
        }
        JsonNode tags = readJson(config.getTags());
        if (tags != null && tags.isObject() && tags.path("deleted").asBoolean(false)) {
            return true;
        }
        JsonNode payload = readJson(config.getPayload());
        return payload == null || !payload.isObject() || isBlankCanvas(payload);
    }

    private boolean isBlankCanvas(JsonNode currentPage) {
        return currentPage != null
                && currentPage.isObject()
                && currentPage.path("widgets").isArray()
                && currentPage.path("widgets").isEmpty();
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            return null;
        }
    }

    record Resolution(boolean required, JsonNode plan, String failureCode) {
        static Resolution notRequired() {
            return new Resolution(false, null, "");
        }

        static Resolution resolved(JsonNode plan) {
            return new Resolution(true, plan, "");
        }

        static Resolution blocked(String failureCode) {
            return new Resolution(true, null, failureCode);
        }

        boolean valid() {
            return !required || plan != null && (failureCode == null || failureCode.isBlank());
        }
    }
}
