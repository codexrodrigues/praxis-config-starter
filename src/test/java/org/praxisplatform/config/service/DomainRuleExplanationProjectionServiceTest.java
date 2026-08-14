package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleMaterializationResponse;
import org.praxisplatform.config.dto.DomainRuleTimelineEventResponse;
import org.praxisplatform.config.dto.DomainRuleTimelineResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;

@Tag("unit")
class DomainRuleExplanationProjectionServiceTest {

    private static final UUID DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIALIZATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String RULE_KEY = "ergon.retirement.eligibility";
    private static final int VERSION = 3;
    private static final DomainRuleGovernancePrincipal PRINCIPAL =
            new DomainRuleGovernancePrincipal("tenant-secret", "actor-secret", "dev-secret");
    private static final Instant CREATED_AT = Instant.parse("2026-08-14T10:00:00Z");
    private static final Instant APPLIED_AT = Instant.parse("2026-08-14T11:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private DomainRuleService domainRuleService;
    private DomainRuleExplanationProjectionService service;

    @BeforeEach
    void setUp() {
        domainRuleService = mock(DomainRuleService.class);
        service = new DomainRuleExplanationProjectionService(
                domainRuleService,
                new DomainRuleDefinitionFingerprint(objectMapper),
                objectMapper);
    }

    @Test
    void projectsAllowedConditionWithCanonicalHashesAndOnlySafeLifecycleEvidence() throws Exception {
        DomainRuleDefinitionResponse definition = definition("""
                {
                  "aiUsage": {
                    "visibility": "allow",
                    "reasoningUse": "allow",
                    "trainingUse": "allow"
                  }
                }
                """);
        stubCanonicalReads(definition);

        DomainRuleExplanationEvidenceProjection projection = service.project(
                DEFINITION_ID, RULE_KEY, VERSION, PRINCIPAL);

        assertThat(projection.schemaVersion())
                .isEqualTo(DomainRuleExplanationEvidenceProjection.SCHEMA_VERSION);
        assertThat(projection.scopeAttestation().tenantBound()).isTrue();
        assertThat(projection.scopeAttestation().environmentBound()).isTrue();
        assertThat(projection.decisionRef().definitionId()).isEqualTo(DEFINITION_ID);
        assertThat(projection.decisionRef().definitionHash()).matches("[A-Fa-f0-9]{64}");
        assertThat(projection.decisionRef().conditionHash()).matches("[A-Fa-f0-9]{64}");
        assertThat(projection.conditionEvidence().exposureMode()).isEqualTo("full");
        assertThat(projection.conditionEvidence().conditionAst()).isEqualTo(definition.condition());
        assertThat(projection.conditionEvidence().conditionAst()).isNotSameAs(definition.condition());
        assertThat(projection.conditionEvidence().valid()).isTrue();
        assertThat(projection.conditionEvidence().operators())
                .containsExactlyInAnyOrder("and", "<=", "==", "var");
        assertThat(projection.conditionEvidence().factPaths())
                .containsExactly("employee.age", "employee.status");
        assertThat(projection.lifecycle().safeEvents())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("definition.activated");
                    assertThat(event.summary()).isEqualTo("Safe activation summary");
                });
        assertThat(projection.materializations())
                .singleElement()
                .satisfies(materialization -> {
                    assertThat(materialization.id()).isEqualTo(MATERIALIZATION_ID);
                    assertThat(materialization.sourceHash()).isEqualTo("materialization-source-hash");
                });
        assertThat(projection.sourceRefs())
                .anyMatch(ref -> ref.startsWith("domain-rule-definition:" + DEFINITION_ID + "@v3#"))
                .anyMatch(ref -> ref.startsWith("domain-rule-condition:" + DEFINITION_ID + "@v3#"))
                .contains("domain-rule-materialization:" + MATERIALIZATION_ID + "#materialization-source-hash");
        assertThat(projection.versionAttestation().exactMatch()).isTrue();

        String serialized = objectMapper.writeValueAsString(projection);
        assertThat(serialized)
                .doesNotContain("tenant-secret")
                .doesNotContain("dev-secret")
                .doesNotContain("actor-secret")
                .doesNotContain("restricted-secret")
                .doesNotContain("materialized-secret")
                .doesNotContain("materialization-actor-secret")
                .doesNotContain("steward-secret")
                .doesNotContain("approver-secret")
                .doesNotContain("release-secret")
                .doesNotContain("materialized-rule-secret")
                .doesNotContain("validation-secret")
                .doesNotContain("diagnostics-secret")
                .doesNotContain("workspace-rationale-secret")
                .doesNotContain("runtime-fact-value-secret");
    }

    @Test
    void defaultsMissingAiUsagePolicyToSummaryOnlyWithoutConditionAst() throws Exception {
        DomainRuleDefinitionResponse definition = definition("{}");
        stubCanonicalReads(definition);

        DomainRuleExplanationEvidenceProjection projection = service.project(
                DEFINITION_ID, RULE_KEY, VERSION, PRINCIPAL);

        assertThat(projection.conditionEvidence().exposureMode()).isEqualTo("summary_only");
        assertThat(projection.conditionEvidence().conditionAst()).isNull();
        assertThat(projection.conditionEvidence().operators()).contains("and", "var");
        assertThat(projection.conditionEvidence().factPaths())
                .containsExactly("employee.age", "employee.status");
        JsonNode serialized = objectMapper.valueToTree(projection);
        assertThat(serialized.path("conditionEvidence").has("conditionAst")).isFalse();
        assertThat(projection.redaction().omittedFields()).contains("conditionAst");
    }

    @Test
    void deniesAstAndConditionShapeWhenVisibilityIsDenied() throws Exception {
        DomainRuleDefinitionResponse definition = definition("""
                {
                  "aiUsage": {
                    "visibility": "deny",
                    "reasoningUse": "allow",
                    "trainingUse": "allow"
                  }
                }
                """);
        stubCanonicalReads(definition);

        DomainRuleExplanationEvidenceProjection projection = service.project(
                DEFINITION_ID, RULE_KEY, VERSION, PRINCIPAL);

        assertThat(projection.conditionEvidence().exposureMode()).isEqualTo("denied");
        assertThat(projection.conditionEvidence().conditionAst()).isNull();
        assertThat(projection.conditionEvidence().operators()).isEmpty();
        assertThat(projection.conditionEvidence().factPaths()).isEmpty();
        JsonNode serialized = objectMapper.valueToTree(projection);
        assertThat(serialized.path("conditionEvidence").has("conditionAst")).isFalse();
    }

    @Test
    void keepsExplanationAvailableWhenOnlyTrainingUseIsDenied() {
        DomainRuleDefinitionResponse definition = definition("""
                {
                  "aiUsage": {
                    "visibility": "allow",
                    "reasoningUse": "allow",
                    "trainingUse": "deny"
                  }
                }
                """);
        stubCanonicalReads(definition);

        DomainRuleExplanationEvidenceProjection projection = service.project(
                DEFINITION_ID, RULE_KEY, VERSION, PRINCIPAL);

        assertThat(projection.conditionEvidence().exposureMode()).isEqualTo("full");
        assertThat(projection.conditionEvidence().conditionAst()).isNotNull();
    }

    @Test
    void rejectsStaleVersionBeforeReadingTimelineOrMaterializations() {
        DomainRuleDefinitionResponse definition = definition("{}");
        when(domainRuleService.definition(DEFINITION_ID, PRINCIPAL)).thenReturn(definition);

        assertThatThrownBy(() -> service.project(DEFINITION_ID, RULE_KEY, VERSION - 1, PRINCIPAL))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("version");

        verify(domainRuleService).definition(DEFINITION_ID, PRINCIPAL);
        verifyNoMoreInteractions(domainRuleService);
    }

    @Test
    void rejectsMismatchedRuleKeyBeforeReadingTimelineOrMaterializations() {
        DomainRuleDefinitionResponse definition = definition("{}");
        when(domainRuleService.definition(DEFINITION_ID, PRINCIPAL)).thenReturn(definition);

        assertThatThrownBy(() -> service.project(DEFINITION_ID, "other.rule", VERSION, PRINCIPAL))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("key");

        verify(domainRuleService).definition(DEFINITION_ID, PRINCIPAL);
        verifyNoMoreInteractions(domainRuleService);
    }

    private void stubCanonicalReads(DomainRuleDefinitionResponse definition) {
        when(domainRuleService.definition(DEFINITION_ID, PRINCIPAL)).thenReturn(definition);
        when(domainRuleService.definitionTimeline(DEFINITION_ID, PRINCIPAL.tenantId(), PRINCIPAL.environment()))
                .thenReturn(timeline());
        when(domainRuleService.materializations(
                PRINCIPAL.tenantId(),
                PRINCIPAL.environment(),
                DEFINITION_ID,
                null,
                null,
                null,
                null))
                .thenReturn(List.of(materialization()));
    }

    private DomainRuleDefinitionResponse definition(String governanceJson) {
        JsonNode condition = json("""
                {
                  "and": [
                    {"<=": [{"var": "employee.age"}, 65]},
                    {"==": [{"var": ["employee.status", "UNKNOWN"]}, "ACTIVE"]}
                  ]
                }
                """);
        return new DomainRuleDefinitionResponse(
                DEFINITION_ID,
                PRINCIPAL.tenantId(),
                PRINCIPAL.environment(),
                RULE_KEY,
                VERSION,
                "selection_eligibility",
                "active",
                "ergon",
                "employees",
                "ergon-service",
                "ergon-policy-team",
                "steward-secret",
                null,
                null,
                json("""
                        {
                          "safeSummary": "Retirement eligibility",
                          "workspaceRationale": "workspace-rationale-secret",
                          "runtimeFact": "runtime-fact-value-secret"
                        }
                        """),
                json("{" + "\"decision\":\"ALLOW\"}"),
                condition,
                json(governanceJson),
                json("{\"valid\":true}"),
                "human",
                "actor-secret",
                "approver-secret",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT,
                CREATED_AT);
    }

    private DomainRuleTimelineResponse timeline() {
        return new DomainRuleTimelineResponse(
                DEFINITION_ID,
                PRINCIPAL.tenantId(),
                PRINCIPAL.environment(),
                RULE_KEY,
                VERSION,
                "selection_eligibility",
                "employees",
                "ergon-service",
                List.of(
                        new DomainRuleTimelineEventResponse(
                                "definition.activated",
                                CREATED_AT,
                                "human",
                                "actor-secret",
                                "Safe activation summary",
                                "active",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "definition-source-hash",
                                "safe"),
                        new DomainRuleTimelineEventResponse(
                                "internal.audit",
                                CREATED_AT.plusSeconds(1),
                                "system",
                                "restricted-actor",
                                "restricted-secret",
                                "active",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "restricted")));
    }

    private DomainRuleMaterializationResponse materialization() {
        return new DomainRuleMaterializationResponse(
                MATERIALIZATION_ID,
                PRINCIPAL.tenantId(),
                PRINCIPAL.environment(),
                DEFINITION_ID,
                RULE_KEY,
                VERSION,
                "ergon-retirement-policy",
                "policy",
                "runtime_policy",
                "employee-retirement",
                "/runtime/policies/employee-retirement",
                "release-secret",
                "materialized-rule-secret",
                "applied",
                json("{\"secret\":\"materialized-secret\"}"),
                "materialization-source-hash",
                json("{\"secret\":\"validation-secret\"}"),
                json("{\"secret\":\"diagnostics-secret\"}"),
                "human",
                "materialization-actor-secret",
                CREATED_AT,
                APPLIED_AT,
                APPLIED_AT);
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
