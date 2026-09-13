package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleEventRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.springframework.beans.factory.ObjectProvider;

@Tag("unit")
class OperationalPolicyContractTest {
    private static final String RESOURCE = "human-resources.eventos-folha";
    private static final String ACTION = "bulk-approve";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectsAndValidatesExplicitEffectsAcrossAllOperationalFamilies() throws Exception {
        DomainRuleService projections = projections();

        JsonNode approval = projections.operationalProjection(
                definition("approval_policy", RESOURCE, "approval", "{\"approvalPolicy\":{\"effect\":\"ALLOW\"}}", null),
                approvalTarget());
        JsonNode workflow = projections.operationalProjection(
                definition("workflow_action_policy", RESOURCE, "workflow", "{\"availabilityPolicy\":{\"effect\":\"BLOCK\"}}", null),
                workflowTarget());
        JsonNode validation = projections.operationalProjection(
                definition("validation", RESOURCE, "validation", "{\"validationPolicy\":{\"effect\":\"ALLOW\"}}", null),
                validationTarget());

        assertThat(approval.at("/approvalPolicy/effect").asText()).isEqualTo("ALLOW");
        assertThat(workflow.at("/availabilityPolicy/effect").asText()).isEqualTo("BLOCK");
        assertThat(validation.at("/validationPolicy/effect").asText()).isEqualTo("ALLOW");
        assertThat(OperationalPolicyContract.validate(
                definition("approval_policy", RESOURCE, "approval", "{\"approvalPolicy\":{\"effect\":\"ALLOW\"}}", null),
                approvalTarget(), approval)).isEqualTo(OperationalPolicyResolution.Effect.ALLOW);
        assertThat(OperationalPolicyContract.validate(
                definition("workflow_action_policy", RESOURCE, "workflow", "{\"availabilityPolicy\":{\"effect\":\"BLOCK\"}}", null),
                workflowTarget(), workflow)).isEqualTo(OperationalPolicyResolution.Effect.BLOCK);
        assertThat(OperationalPolicyContract.validate(
                definition("validation", RESOURCE, "validation", "{\"validationPolicy\":{\"effect\":\"ALLOW\"}}", null),
                validationTarget(), validation)).isEqualTo(OperationalPolicyResolution.Effect.ALLOW);
    }

    @Test
    void legacyPolicyWithoutEffectIsConservativelyBlocked() throws Exception {
        DomainRuleDefinition definition = definition("approval_policy", RESOURCE, "legacy", "{}", null);
        ObjectNode payload = approvalPayload(definition, null);

        OperationalPolicyContract.projectEffect(definition, approvalTarget(), payload);

        assertThat(payload.at("/approvalPolicy/effect").isMissingNode()).isTrue();
        assertThat(OperationalPolicyContract.validate(definition, approvalTarget(), payload))
                .isEqualTo(OperationalPolicyResolution.Effect.BLOCK);
    }

    @Test
    void rejectsInvalidOrContradictoryExplicitAllowEvenWhenRestrictionValuesAreFalseZeroOrEmpty() throws Exception {
        DomainRuleDefinition allow = definition("approval_policy", RESOURCE, "allow", "{\"approvalPolicy\":{\"effect\":\"ALLOW\"}}", null);

        assertInvalidAllowProjection("{\"approvalPolicy\":{\"effect\":\"ALLOW\",\"blockedWhen\":false}}");
        assertInvalidAllowProjection("{\"approvalPolicy\":{\"effect\":\"ALLOW\",\"requiredApprovals\":0}}");
        assertInvalidAllowProjection("{\"approvalPolicy\":{\"effect\":\"ALLOW\",\"approvalGroups\":[]}}");
        assertInvalidAllow(allow, payload -> payload.with("approvalPolicy").put("blockedWhen", false));
        assertInvalidAllow(allow, payload -> payload.with("approvalPolicy").put("requiredApprovals", 0));
        assertInvalidAllow(allow, payload -> payload.with("approvalPolicy").putArray("approvalGroups"));
        DomainRuleDefinition falseCondition = definition("approval_policy", RESOURCE, "condition",
                "{\"approvalPolicy\":{\"effect\":\"ALLOW\"}}", "false");
        assertThatThrownBy(() -> OperationalPolicyContract.validate(
                falseCondition, approvalTarget(), approvalPayload(falseCondition, "ALLOW")))
                .isInstanceOf(ConfigurationIngestionException.class);
        assertThatThrownBy(() -> OperationalPolicyContract.validate(
                allow, approvalTarget(), approvalPayload(allow, "MAYBE")))
                .isInstanceOf(ConfigurationIngestionException.class);
        DomainRuleDefinition nullEffect = definition("approval_policy", RESOURCE, "null-effect",
                "{\"approvalPolicy\":{\"effect\":null}}", null);
        assertThatThrownBy(() -> OperationalPolicyContract.projectEffect(
                nullEffect, approvalTarget(), approvalPayload(nullEffect, "BLOCK")))
                .isInstanceOf(ConfigurationIngestionException.class);
    }

    @Test
    void rejectsOperationalIdentityMismatchesAndInvalidCanonicalTargets() throws Exception {
        DomainRuleDefinition definition = definition("approval_policy", RESOURCE, "identity", "{\"approvalPolicy\":{\"effect\":\"BLOCK\"}}", null);
        ObjectNode payload = approvalPayload(definition, "BLOCK");
        payload.with("resourceAction").put("actionId", "another-action");

        assertThatThrownBy(() -> OperationalPolicyContract.validate(definition, approvalTarget(), payload))
                .isInstanceOf(ConfigurationIngestionException.class);
        assertThatThrownBy(() -> new OperationalPolicyTarget("approval_policy", "resource-action-approval", RESOURCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationalPolicyTarget("approval_policy", "resource-action-approval", " " + RESOURCE + ":" + ACTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationalPolicyTarget("not-a-layer", "resource-action-approval", RESOURCE + ":" + ACTION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintIsIndependentOfObjectKeyOrderButBindsNumericValues() {
        DomainRuleDefinition ordered = definition("validation", RESOURCE, "hash", "{\"a\":1,\"b\":2}", null);
        DomainRuleDefinition reordered = copyWith(ordered, "{\"b\":2,\"a\":1}");
        DomainRuleDefinition changedNumber = copyWith(ordered, "{\"a\":2,\"b\":2}");
        JsonNode payload = objectMapper.createObjectNode().put("kind", "resource_validation_policy");

        String first = DomainRuleMaterializationFingerprint.sha256(ordered, "backend_validation", "resource-validation",
                RESOURCE, "/validationPolicy", "resource-validation-policy", payload);
        String second = DomainRuleMaterializationFingerprint.sha256(reordered, "backend_validation", "resource-validation",
                RESOURCE, "/validationPolicy", "resource-validation-policy", payload);
        String third = DomainRuleMaterializationFingerprint.sha256(changedNumber, "backend_validation", "resource-validation",
                RESOURCE, "/validationPolicy", "resource-validation-policy", payload);

        assertThat(first).isEqualTo(second).startsWith("derived:sha256:");
        assertThat(third).isNotEqualTo(first);
    }

    @Test
    void resolutionAndPolicyDefensivelyCopyPayloadAndDoNotLogItsContent() throws Exception {
        ObjectNode source = objectMapper.createObjectNode().put("secretBusinessValue", "private");
        OperationalPolicyResolution.Policy policy = new OperationalPolicyResolution.Policy(
                UUID.randomUUID(), UUID.randomUUID(), 1, "derived:sha256:source", OperationalPolicyResolution.Effect.BLOCK, source);
        source.put("secretBusinessValue", "changed");
        JsonNode returned = policy.payload();
        ((ObjectNode) returned).put("secretBusinessValue", "changed-again");

        assertThat(policy.payload().path("secretBusinessValue").asText()).isEqualTo("private");
        assertThat(policy).hasToString("Policy[materializationId=" + policy.materializationId() + ", effect=BLOCK]");
        assertThat(policy.toString()).doesNotContain("secretBusinessValue", "private");
        assertThatThrownBy(() -> new OperationalPolicyResolution(
                OperationalPolicyResolution.State.ELIGIBLE_APPLIED_HEAD, approvalTarget(), "fingerprint", null, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationalPolicyResolution(
                OperationalPolicyResolution.State.NEVER_APPLIED, approvalTarget(), "fingerprint", policy, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowCannotHideConstraintsInAnActionIdentityDescriptor() {
        assertInvalidAllowProjection("{\"approvalPolicy\":{\"effect\":\"ALLOW\"},\"approvalAction\":{\"requiredApprovals\":[]}}");
        assertInvalidAllowProjection("{\"approvalPolicy\":{\"effect\":\"ALLOW\"},\"workflowAction\":{\"blockedWhen\":false}}");
    }

    private void assertInvalidAllow(DomainRuleDefinition definition, java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode payload = approvalPayload(definition, "ALLOW");
        mutation.accept(payload);
        assertThatThrownBy(() -> OperationalPolicyContract.validate(definition, approvalTarget(), payload))
                .isInstanceOf(ConfigurationIngestionException.class);
    }

    private void assertInvalidAllowProjection(String parameters) {
        DomainRuleDefinition definition = definition("approval_policy", RESOURCE, "restricted-allow", parameters, null);
        assertThatThrownBy(() -> projections().operationalProjection(definition, approvalTarget()))
                .isInstanceOf(ConfigurationIngestionException.class);
    }

    private OperationalPolicyTarget approvalTarget() {
        return new OperationalPolicyTarget("approval_policy", "resource-action-approval", RESOURCE + ":" + ACTION);
    }

    private OperationalPolicyTarget workflowTarget() {
        return new OperationalPolicyTarget("workflow_action", "resource-workflow-action", RESOURCE + ":" + ACTION);
    }

    private OperationalPolicyTarget validationTarget() {
        return new OperationalPolicyTarget("backend_validation", "resource-validation", RESOURCE);
    }

    private ObjectNode approvalPayload(DomainRuleDefinition definition, String effect) {
        ObjectNode payload = objectMapper.createObjectNode().put("kind", "approval_policy");
        payload.putObject("metadata").put("origin", "domain_rule_definition");
        payload.putObject("resourceAction").put("resourceKey", RESOURCE).put("actionId", ACTION);
        ObjectNode policy = payload.putObject("approvalPolicy")
                .put("ruleKey", definition.getRuleKey())
                .put("ruleVersion", definition.getVersion())
                .put("resourceKey", RESOURCE)
                .put("actionId", ACTION);
        if (effect != null) {
            policy.put("effect", effect);
        }
        return payload;
    }

    private DomainRuleDefinition definition(String ruleType, String resourceKey, String ruleKey,
                                            String parameters, String condition) {
        return DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey(ruleKey)
                .version(1)
                .ruleType(ruleType)
                .status("active")
                .contextKey("bulk")
                .resourceKey(resourceKey)
                .serviceKey("operations")
                .definition("{\"summary\":\"operational policy\"}")
                .parameters(parameters)
                .condition(condition)
                .governance("{}")
                .build();
    }

    private DomainRuleDefinition copyWith(DomainRuleDefinition source, String parameters) {
        return DomainRuleDefinition.builder()
                .id(source.getId())
                .tenantId(source.getTenantId())
                .environment(source.getEnvironment())
                .ruleKey(source.getRuleKey())
                .version(source.getVersion())
                .ruleType(source.getRuleType())
                .status(source.getStatus())
                .contextKey(source.getContextKey())
                .resourceKey(source.getResourceKey())
                .serviceKey(source.getServiceKey())
                .definition(source.getDefinition())
                .parameters(parameters)
                .condition(source.getCondition())
                .governance(source.getGovernance())
                .build();
    }

    @SuppressWarnings("unchecked")
    private DomainRuleService projections() {
        return new DomainRuleService(
                mock(DomainRuleDefinitionRepository.class),
                mock(DomainRuleMaterializationRepository.class),
                mock(DomainRuleEventRepository.class),
                mock(DomainCatalogReleaseRepository.class),
                mock(DomainKnowledgeChangeSetRepository.class),
                mock(DomainRuleDefinitionApprovalRepository.class),
                new DomainRuleDefinitionFingerprint(objectMapper),
                objectMapper,
                mock(ObjectProvider.class),
                new GovernedColorPaletteContractValidator(objectMapper),
                mock(DomainRuleEntityRefresh.class));
    }
}
