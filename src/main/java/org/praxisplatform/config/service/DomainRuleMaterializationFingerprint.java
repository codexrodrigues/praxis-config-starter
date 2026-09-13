package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Shared semantic digest, independent of PostgreSQL JSONB whitespace and object-key order. */
public final class DomainRuleMaterializationFingerprint {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private DomainRuleMaterializationFingerprint() {}

    public static String sha256(DomainRuleDefinition definition, String layer, String type, String key,
            String pointer, String ruleId, JsonNode payload) {
        ObjectNode value = JSON.createObjectNode();
        value.put("format", "praxis.config.materialization/1");
        value.put("definitionId", definition.getId() == null ? null : definition.getId().toString());
        value.put("tenantId", definition.getTenantId());
        value.put("environment", definition.getEnvironment());
        value.put("ruleKey", definition.getRuleKey());
        value.put("ruleVersion", definition.getVersion());
        value.put("ruleType", definition.getRuleType());
        value.put("contextKey", definition.getContextKey());
        value.put("resourceKey", definition.getResourceKey());
        value.put("serviceKey", definition.getServiceKey());
        value.set("definition", parse(definition.getDefinition()));
        value.set("parameters", parse(definition.getParameters()));
        value.set("condition", parse(definition.getCondition()));
        value.set("governance", parse(definition.getGovernance()));
        value.put("targetLayer", layer); value.put("targetArtifactType", type); value.put("targetArtifactKey", key);
        value.put("targetPointer", pointer); value.put("materializedRuleId", ruleId);
        value.set("payload", payload);
        return "derived:sha256:" + PraxisCanonicalJson.sha256(value);
    }

    static JsonNode parse(String json) {
        try {
            JsonNode result = json == null ? JSON.nullNode() : JSON.readTree(json);
            if (result == null || result.isMissingNode()) throw new ConfigurationIngestionException("Invalid governed JSON");
            return result;
        }
        catch (JsonProcessingException ex) { throw new ConfigurationIngestionException("Invalid governed JSON", ex); }
    }
}
