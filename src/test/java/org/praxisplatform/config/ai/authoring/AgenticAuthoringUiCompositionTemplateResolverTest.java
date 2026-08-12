package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiRegistryTemplateRevision;
import org.praxisplatform.config.service.AiRegistryTemplateService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringUiCompositionTemplateResolverTest {

    private static final String REGISTRY_KEY =
            "praxis-dynamic-page:employee-operations-casework";
    private static final String CONFIG_SHA_256 = "a".repeat(64);

    @Mock private AiRegistryTemplateService templateService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgenticAuthoringUiCompositionTemplateResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgenticAuthoringUiCompositionTemplateResolver(templateService);
    }

    @Test
    void resolvesExactActiveTemplateAndPublishesRevisionEvidence() throws Exception {
        stubTemplate(expandedPlan(), CONFIG_SHA_256, "active");

        AgenticAuthoringUiCompositionTemplateResolver.Resolution result =
                resolver.resolve(reference(CONFIG_SHA_256));

        assertThat(result.referenced()).isTrue();
        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.warnings())
                .containsExactly("ui-composition-template-reference-resolved");
        assertThat(result.uiCompositionPlan().path("widgets")).hasSize(1);
        assertThat(result.uiCompositionPlan()
                        .at("/diagnostics/templateResolution/registryKey")
                        .asText())
                .isEqualTo(REGISTRY_KEY);
        assertThat(result.uiCompositionPlan()
                        .at("/diagnostics/templateResolution/configSha256")
                        .asText())
                .isEqualTo(CONFIG_SHA_256);
        assertThat(result.uiCompositionPlan()
                        .at("/diagnostics/templateResolution/version")
                        .asLong())
                .isEqualTo(7L);
    }

    @Test
    void failsClosedWhenPinnedHashDoesNotMatchCurrentTemplate() throws Exception {
        stubTemplate(expandedPlan(), CONFIG_SHA_256, "active");

        AgenticAuthoringUiCompositionTemplateResolver.Resolution result =
                resolver.resolve(reference("b".repeat(64)));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .containsExactly("ui-composition-template-hash-mismatch");
    }

    @Test
    void failsClosedForMissingAndInactiveTemplates() throws Exception {
        when(templateService.getTemplate(REGISTRY_KEY)).thenReturn(Optional.empty());

        AgenticAuthoringUiCompositionTemplateResolver.Resolution missing =
                resolver.resolve(reference(CONFIG_SHA_256));

        assertThat(missing.failureCodes())
                .containsExactly("ui-composition-template-not-found");

        AiRegistry inactiveRegistry = registry("inactive");
        when(templateService.getTemplate(REGISTRY_KEY)).thenReturn(Optional.of(inactiveRegistry));

        AgenticAuthoringUiCompositionTemplateResolver.Resolution inactive =
                resolver.resolve(reference(CONFIG_SHA_256));

        assertThat(inactive.failureCodes())
                .containsExactly("ui-composition-template-inactive");
    }

    @Test
    void rejectsMalformedReferencesMixedPlansAndNonEmptyOverrides() throws Exception {
        JsonNode malformedHash = reference("ABC");
        JsonNode mixedPlan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "templateRef": {
                    "registryKey": "praxis-dynamic-page:employee-operations-casework",
                    "configSha256": "%s"
                  },
                  "widgets": []
                }
                """.formatted(CONFIG_SHA_256));
        JsonNode overrides = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "templateRef": {
                    "registryKey": "praxis-dynamic-page:employee-operations-casework",
                    "configSha256": "%s"
                  },
                  "overrides": { "themePreset": "compact" }
                }
                """.formatted(CONFIG_SHA_256));

        assertThat(resolver.resolve(malformedHash).failureCodes())
                .contains("ui-composition-template-config-sha256-invalid");
        assertThat(resolver.resolve(mixedPlan).failureCodes())
                .contains("ui-composition-template-reference-mixed-plan");
        assertThat(resolver.resolve(overrides).failureCodes())
                .contains("ui-composition-template-overrides-unsupported");
    }

    @Test
    void requiresTheGovernedAuthoringPlanArtifact() throws Exception {
        JsonNode configWithoutPlan = objectMapper.readTree("{\"page\":{\"widgets\":[]}}");
        stubConfig(configWithoutPlan, CONFIG_SHA_256, "active");

        AgenticAuthoringUiCompositionTemplateResolver.Resolution result =
                resolver.resolve(reference(CONFIG_SHA_256));

        assertThat(result.failureCodes())
                .containsExactly("ui-composition-template-authoring-plan-missing");
    }

    @Test
    void compilesReferenceToTheExactSameExecutablePageAsExpandedPlan() throws Exception {
        JsonNode plan = expandedPlan();
        stubTemplate(plan, CONFIG_SHA_256, "active");
        AgenticAuthoringUiCompositionTemplateResolver.Resolution resolved =
                resolver.resolve(reference(CONFIG_SHA_256));
        AgenticAuthoringUiCompositionPlanCompiler compiler =
                new AgenticAuthoringUiCompositionPlanCompiler(objectMapper);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult expandedCompilation =
                compiler.compile(plan, objectMapper.createObjectNode());
        AgenticAuthoringUiCompositionPlanCompiler.CompileResult referencedCompilation =
                compiler.compile(resolved.uiCompositionPlan(), objectMapper.createObjectNode());

        assertThat(expandedCompilation.valid()).isTrue();
        assertThat(referencedCompilation.valid()).isTrue();
        assertThat(referencedCompilation.compiledFormPatch().at("/patch/page"))
                .isEqualTo(expandedCompilation.compiledFormPatch().at("/patch/page"));
    }

    private void stubTemplate(JsonNode authoringPlan, String hash, String status) throws Exception {
        JsonNode configJson = objectMapper.createObjectNode().set("authoringPlan", authoringPlan);
        stubConfig(configJson, hash, status);
    }

    private void stubConfig(JsonNode configJson, String hash, String status) {
        AiRegistry registry = registry(status);
        AiRegistryTemplateRecord record = AiRegistryTemplateRecord.builder()
                .componentId(REGISTRY_KEY)
                .configJson(configJson)
                .revision(AiRegistryTemplateRevision.builder()
                        .version(7L)
                        .etag("123e4567-e89b-12d3-a456-426614174000")
                        .configSha256(hash)
                        .build())
                .build();
        when(templateService.getTemplate(REGISTRY_KEY)).thenReturn(Optional.of(registry));
        when(templateService.toRecord(registry)).thenReturn(record);
    }

    private AiRegistry registry(String status) {
        return AiRegistry.builder()
                .id(UUID.randomUUID())
                .registryKey(REGISTRY_KEY)
                .status(status)
                .build();
    }

    private JsonNode reference(String hash) throws Exception {
        return objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "templateRef": {
                    "registryKey": "praxis-dynamic-page:employee-operations-casework",
                    "configSha256": "%s"
                  },
                  "overrides": {}
                }
                """.formatted(hash));
    }

    private JsonNode expandedPlan() throws Exception {
        return objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [
                    {
                      "key": "employee-portfolio",
                      "componentId": "praxis-table",
                      "inputs": { "resourcePath": "human-resources/funcionarios" }
                    }
                  ]
                }
                """);
    }
}
