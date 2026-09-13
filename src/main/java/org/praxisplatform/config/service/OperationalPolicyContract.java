package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.exception.ConfigurationIngestionException;

/** Validates the explicit operational effect in the existing policy projections. */
final class OperationalPolicyContract {
    private static final Set<String> IDENTITY_AND_COPY = Set.of("ruleKey", "ruleVersion", "resourceKey", "actionId",
            "serviceKey", "contextKey", "message", "severity", "validationMessageTemplate", "effect", "parameters");
    private static final Set<String> PARAMETER_COPY = Set.of("resourceKey", "actionId", "workflowAction", "approvalAction",
            "message", "severity", "validationMessageTemplate");
    private OperationalPolicyContract() {}

    static void projectEffect(DomainRuleDefinition definition, OperationalPolicyTarget target, ObjectNode payload) {
        JsonNode parameters = DomainRuleMaterializationFingerprint.parse(definition.getParameters());
        JsonNode declared = parameters.path(target.family().slot);
        if (declared.has("effect")) {
            if (!declared.isObject()) throw invalid();
            ((ObjectNode) payload.path(target.family().slot)).set("effect", declared.get("effect"));
        }
        if (declared.has("effect")) validate(definition, target, payload);
    }

    static OperationalPolicyResolution.Effect validate(DomainRuleDefinition definition, OperationalPolicyTarget target, JsonNode payload) {
        var family = target.family();
        if (!family.ruleTypes.contains(definition.getRuleType()) || !target.resourceKey().equals(definition.getResourceKey())
                || !payload.isObject() || !family.kind.equals(payload.path("kind").asText())
                || !"domain_rule_definition".equals(payload.path("metadata").path("origin").asText())) throw invalid();
        JsonNode policy = payload.path(family.slot);
        if (!policy.isObject() || !definition.getRuleKey().equals(policy.path("ruleKey").asText())
                || !policy.path("ruleVersion").isIntegralNumber() || policy.path("ruleVersion").intValue() != definition.getVersion()
                || !target.resourceKey().equals(policy.path("resourceKey").asText())) throw invalid();
        if (family == OperationalPolicyTarget.Family.VALIDATION) {
            if (!target.resourceKey().equals(payload.path("resourceKey").asText())) throw invalid();
        } else {
            JsonNode action = payload.path(family == OperationalPolicyTarget.Family.APPROVAL ? "resourceAction" : "workflowAction");
            if (!target.resourceKey().equals(action.path("resourceKey").asText())
                    || !target.actionId().equals(action.path("actionId").asText())
                    || !target.actionId().equals(policy.path("actionId").asText())) throw invalid();
        }
        // Specialized legacy policies never imply permission in the operational gate.
        if (!policy.has("effect")) return OperationalPolicyResolution.Effect.BLOCK;
        if (!policy.path("effect").isTextual()) throw invalid();
        OperationalPolicyResolution.Effect effect;
        try { effect = OperationalPolicyResolution.Effect.valueOf(policy.path("effect").textValue()); }
        catch (IllegalArgumentException ex) { throw invalid(); }
        if (effect == OperationalPolicyResolution.Effect.ALLOW) {
            if (!DomainRuleMaterializationFingerprint.parse(definition.getCondition()).isNull()) throw invalid();
            policy.fieldNames().forEachRemaining(key -> { if (!IDENTITY_AND_COPY.contains(key)) throw invalid(); });
            JsonNode parameters = DomainRuleMaterializationFingerprint.parse(definition.getParameters());
            if (!parameters.isObject()) throw invalid();
            parameters.fieldNames().forEachRemaining(key -> {
                if (!PARAMETER_COPY.contains(key) && !family.slot.equals(key)) throw invalid();
            });
            for (String descriptor : Set.of("workflowAction", "approvalAction")) {
                if (!parameters.has(descriptor)) continue;
                JsonNode action = parameters.path(descriptor);
                if (!action.isObject()) throw invalid();
                action.fieldNames().forEachRemaining(key -> {
                    if (!Set.of("resourceKey", "actionId").contains(key)) throw invalid();
                });
                if ((action.has("resourceKey") && !target.resourceKey().equals(action.path("resourceKey").asText()))
                        || (action.has("actionId") && !java.util.Objects.equals(target.actionId(), action.path("actionId").asText()))) throw invalid();
            }
            JsonNode declared = parameters.path(family.slot);
            if (!declared.isObject() || !"ALLOW".equals(declared.path("effect").asText())) throw invalid();
            declared.fieldNames().forEachRemaining(key -> {
                if (!Set.of("effect", "message", "severity", "validationMessageTemplate").contains(key)) throw invalid();
            });
            if (family == OperationalPolicyTarget.Family.WORKFLOW && payload.has("approvalPolicy")) throw invalid();
        }
        return effect;
    }

    private static ConfigurationIngestionException invalid() {
        return new ConfigurationIngestionException("Operational policy identity or explicit effect is invalid or contradictory");
    }
}
