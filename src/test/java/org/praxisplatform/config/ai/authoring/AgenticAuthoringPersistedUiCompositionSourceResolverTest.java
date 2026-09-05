package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.praxisplatform.config.service.UserConfigService;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringPersistedUiCompositionSourceResolverTest {

    private static final String TENANT = "acme";
    private static final String USER = "analyst";
    private static final String ENVIRONMENT = "staging";
    private static final String COMPONENT_TYPE = "praxis-dynamic-page";
    private static final String COMPONENT_ID = "risk-dashboard";

    @Mock
    private UserConfigService userConfigService;

    private ObjectMapper objectMapper;
    private CanonicalJsonHashService canonicalJsonHashService;
    private AgenticAuthoringPersistedUiCompositionSourceResolver resolver;
    private AiPrincipalContext principal;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        canonicalJsonHashService = new CanonicalJsonHashService(objectMapper);
        resolver = new AgenticAuthoringPersistedUiCompositionSourceResolver(
                userConfigService,
                objectMapper,
                canonicalJsonHashService);
        principal = new AiPrincipalContext(TENANT, USER, ENVIRONMENT, true);
    }

    @Test
    void resolvesOnlyTheServerPersistedPlanForTheExactUpdateIdentity() throws Exception {
        UUID etag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        ObjectNode plan = compositionPlan("bar");
        UiUserConfig config = persistedConfig(etag, page, authoringSource(plan, page));
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

        ObjectNode forgedBrowserSource = authoringSource(compositionPlan("pie"), page);
        AgenticAuthoringTurnStreamRequest request = updateRequest(page, etag, forgedBrowserSource);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isTrue();
        assertThat(resolution.required()).isTrue();
        assertThat(resolution.plan()).isEqualTo(plan);
        assertThat(resolution.plan()).isNotSameAs(plan);
        assertThat(resolution.plan().at("/widgets/0/inputs/chartDocument/kind").asText()).isEqualTo("bar");
        verify(userConfigService).getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT);
    }

    @Test
    void blocksAStaleEtagBeforeExposingThePersistedSource() throws Exception {
        UUID persistedEtag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        UiUserConfig config = persistedConfig(
                persistedEtag,
                page,
                authoringSource(compositionPlan("bar"), page));
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

        AgenticAuthoringTurnStreamRequest request = updateRequest(page, UUID.randomUUID(), null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.failureCode()).isEqualTo("persisted-ui-composition-etag-mismatch");
        assertThat(resolution.plan()).isNull();
    }

    @Test
    void blocksWhenTheOpenPageNoLongerMatchesThePersistedMaterialization() throws Exception {
        UUID etag = UUID.randomUUID();
        ObjectNode persistedPage = materializedPage("bar");
        UiUserConfig config = persistedConfig(
                etag,
                persistedPage,
                authoringSource(compositionPlan("bar"), persistedPage));
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

        AgenticAuthoringTurnStreamRequest request = updateRequest(materializedPage("line"), etag, null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.failureCode()).isEqualTo("persisted-ui-composition-current-page-mismatch");
    }

    @Test
    void blocksWhenThePersistedMaterializationHashWasTampered() throws Exception {
        UUID etag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        ObjectNode envelope = authoringSource(compositionPlan("bar"), page);
        envelope.withObject("/materialization").put("sha256", "0".repeat(64));
        UiUserConfig config = persistedConfig(etag, page, envelope);
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

        AgenticAuthoringTurnStreamRequest request = updateRequest(page, etag, null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.failureCode())
                .isEqualTo("persisted-ui-composition-materialization-hash-mismatch");
    }

    @Test
    void blocksWhenThePersistedMaterializationIdentityDoesNotMatchTheApplyTarget() throws Exception {
        UUID etag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        ObjectNode envelope = authoringSource(compositionPlan("bar"), page);
        envelope.withObject("/materialization").put("componentId", "another-dashboard");
        UiUserConfig config = persistedConfig(etag, page, envelope);
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

        AgenticAuthoringTurnStreamRequest request = updateRequest(page, etag, null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.failureCode())
                .isEqualTo("persisted-ui-composition-materialization-identity-mismatch");
    }

    @Test
    void cannotResolveAnotherUsersPersistedSourceFromTheBrowserTarget() {
        UUID etag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        AiPrincipalContext anotherPrincipal = new AiPrincipalContext(TENANT, "another-user", ENVIRONMENT, true);
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                "another-user",
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.empty());

        AgenticAuthoringTurnStreamRequest request = updateRequest(page, etag, null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                anotherPrincipal,
                AgenticAuthoringApplyTarget.resolve(request, anotherPrincipal));

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.failureCode()).isEqualTo("persisted-ui-composition-config-not-found");
        verify(userConfigService).getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                "another-user",
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT);
    }

    @Test
    void blocksAStoredSourceWhoseCanonicalHashWasTampered() throws Exception {
        UUID etag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        ObjectNode envelope = authoringSource(compositionPlan("bar"), page);
        envelope.put("sourceSha256", "0".repeat(64));
        UiUserConfig config = persistedConfig(etag, page, envelope);
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

        AgenticAuthoringTurnStreamRequest request = updateRequest(page, etag, null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.failureCode()).isEqualTo("persisted-ui-composition-source-hash-mismatch");
    }

    @Test
    void keepsLegacyConfigsWithoutAnAuthoringSourceEditable() throws Exception {
        UUID etag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        UiUserConfig config = persistedConfig(etag, page, null);
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

        AgenticAuthoringTurnStreamRequest request = updateRequest(page, etag, null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isTrue();
        assertThat(resolution.required()).isFalse();
        assertThat(resolution.plan()).isNull();
    }

    @Test
    void blocksMalformedPersistedAuthoringSourceInsteadOfTreatingItAsLegacy() throws Exception {
        UUID etag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        UiUserConfig config = UiUserConfig.builder()
                .tenantId(TENANT)
                .userId(USER)
                .componentType(COMPONENT_TYPE)
                .componentId(COMPONENT_ID)
                .environment(ENVIRONMENT)
                .etag(etag)
                .version(2L)
                .payload(objectMapper.writeValueAsString(page))
                .authoringSource("{malformed-json")
                .build();
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(config, UserConfigService.Scope.USER)));

        AgenticAuthoringTurnStreamRequest request = updateRequest(page, etag, null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.failureCode()).isEqualTo("persisted-ui-composition-authoring-source-invalid");
    }

    @Test
    void blocksApplyWhenThePersistedIdentityCannotBeRead() {
        UUID etag = UUID.randomUUID();
        ObjectNode page = materializedPage("bar");
        when(userConfigService.getByScope(
                UserConfigService.Scope.USER,
                TENANT,
                USER,
                COMPONENT_TYPE,
                COMPONENT_ID,
                ENVIRONMENT))
                .thenThrow(new IllegalStateException("database unavailable"));

        AgenticAuthoringTurnStreamRequest request = updateRequest(page, etag, null);
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution resolution = resolver.resolve(
                request,
                principal,
                AgenticAuthoringApplyTarget.resolve(request, principal));

        assertThat(resolution.valid()).isFalse();
        assertThat(resolution.failureCode()).isEqualTo("persisted-ui-composition-read-failed");
    }

    @Test
    void rejectsMalformedSynchronousApplyTargetInsteadOfTreatingItAsAbsent() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("agenticApplyTarget", "browser-controlled-invalid-target");
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Refine o gráfico",
                "openai",
                "gpt-5.4-mini",
                null,
                materializedPage("bar"),
                null,
                "session",
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                contextHints);

        assertThatThrownBy(() -> resolver.resolvePlanForPreview(request, principal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("apply-target-missing");
    }

    private AgenticAuthoringTurnStreamRequest updateRequest(
            JsonNode currentPage,
            UUID etag,
            JsonNode browserSource) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode target = contextHints.putObject("agenticApplyTarget");
        target.put("schemaVersion", AgenticAuthoringApplyTarget.SCHEMA_VERSION);
        target.put("componentType", COMPONENT_TYPE);
        target.put("componentId", COMPONENT_ID);
        target.put("scope", "user");
        target.put("mode", "update");
        target.put("baseEtag", etag.toString());
        if (browserSource != null) {
            contextHints.set("uiCompositionAuthoringSource", browserSource);
        }
        return new AgenticAuthoringTurnStreamRequest(
                "Refine o gráfico",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                currentPage,
                "risk-chart",
                "openai",
                "gpt-5.4-mini",
                null,
                "session",
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                contextHints,
                null,
                null);
    }

    private UiUserConfig persistedConfig(
            UUID etag,
            JsonNode page,
            JsonNode authoringSource) throws Exception {
        return UiUserConfig.builder()
                .tenantId(TENANT)
                .userId(USER)
                .componentType(COMPONENT_TYPE)
                .componentId(COMPONENT_ID)
                .environment(ENVIRONMENT)
                .etag(etag)
                .version(2L)
                .payload(objectMapper.writeValueAsString(page))
                .authoringSource(authoringSource == null ? null : objectMapper.writeValueAsString(authoringSource))
                .build();
    }

    private ObjectNode authoringSource(JsonNode plan, JsonNode page) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", "praxis.ui-authoring-source/v1");
        envelope.put("kind", "ui-composition-plan");
        envelope.set("source", plan.deepCopy());
        envelope.put("sourceSha256", canonicalJsonHashService.sha256(plan));
        ObjectNode materialization = envelope.putObject("materialization");
        materialization.put("kind", "widget-page-definition");
        materialization.put("componentType", COMPONENT_TYPE);
        materialization.put("componentId", COMPONENT_ID);
        materialization.put("sha256", canonicalJsonHashService.sha256(page));
        return envelope;
    }

    private ObjectNode compositionPlan(String chartKind) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("version", "1.0");
        ObjectNode widget = plan.putArray("widgets").addObject();
        widget.put("key", "risk-chart");
        widget.put("componentId", "praxis-chart");
        widget.putObject("inputs").putObject("chartDocument").put("kind", chartKind);
        return plan;
    }

    private ObjectNode materializedPage(String chartKind) {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "risk-chart");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        definition.putObject("inputs").putObject("chartDocument").put("kind", chartKind);
        return page;
    }
}
