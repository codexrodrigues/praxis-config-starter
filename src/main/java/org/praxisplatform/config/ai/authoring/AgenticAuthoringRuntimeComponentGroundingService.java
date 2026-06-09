package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.util.StringUtils;

class AgenticAuthoringRuntimeComponentGroundingService {

    static final String TRUST_BOUNDARY_UNTRUSTED_FRONTEND_OBSERVATION = "untrusted_frontend_observation";
    private static final String OBSERVATION_SCHEMA_VERSION = "praxis-runtime-component-observation.v1";
    private static final String GROUNDED_CONTEXT_SCHEMA_VERSION = "praxis-grounded-runtime-component-context.v1";

    private final ObjectMapper objectMapper;

    AgenticAuthoringRuntimeComponentGroundingService(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    ObjectNode ground(List<JsonNode> observations, String trustBoundary) {
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", GROUNDED_CONTEXT_SCHEMA_VERSION);
        context.put("canonicalContext", "GroundedRuntimeComponentContext");
        context.put("source", "runtimeComponentObservations");
        context.put("trustBoundary", textOrDefault(trustBoundary, ""));
        context.put("trustLevel", TRUST_BOUNDARY_UNTRUSTED_FRONTEND_OBSERVATION);
        context.put("generatedAt", Instant.now().toString());

        ObjectNode policy = context.putObject("policy");
        policy.put("claimsAreUntrusted", true);
        policy.put("requiresBackendReconciliation", true);
        policy.put("mayInformIntentGrounding", true);
        policy.put("mayExecuteActions", false);
        policy.put("mayExposeRawRuntimeValues", false);

        ArrayNode components = context.putArray("components");
        ArrayNode acceptedClaims = context.putArray("acceptedClaims");
        ArrayNode rejectedClaims = context.putArray("rejectedClaims");
        ArrayNode evidenceRefs = context.putArray("evidenceRefs");
        Set<String> allowedFields = new LinkedHashSet<>();
        Set<String> allowedOperations = new LinkedHashSet<>();
        Set<String> availableSurfaces = new LinkedHashSet<>();

        boolean trustBoundaryAccepted = TRUST_BOUNDARY_UNTRUSTED_FRONTEND_OBSERVATION.equals(trustBoundary);
        int observedCount = 0;
        for (JsonNode observation : observations) {
            if (observation == null || !observation.isObject()) {
                reject(rejectedClaims, "invalid_observation", "Observation must be a JSON object.", null);
                continue;
            }
            observedCount++;
            if (!OBSERVATION_SCHEMA_VERSION.equals(text(observation.path("schemaVersion")))) {
                reject(rejectedClaims, "unsupported_schema_version", "Runtime observation schema version is not supported.", observation);
                continue;
            }
            if (!trustBoundaryAccepted) {
                reject(rejectedClaims, "unsupported_trust_boundary", "Runtime observation trust boundary is not accepted.", observation);
                continue;
            }
            JsonNode lifecycle = observation.path("lifecycle");
            if (lifecycle.path("active").isBoolean() && !lifecycle.path("active").asBoolean()) {
                reject(rejectedClaims, "inactive_observation", "Runtime observation is inactive.", observation);
                continue;
            }
            if (isStale(lifecycle)) {
                reject(rejectedClaims, "stale_observation", "Runtime observation is stale.", observation);
                continue;
            }
            ObjectNode component = groundedComponent(observation, allowedFields, allowedOperations, availableSurfaces);
            if (component == null) {
                reject(rejectedClaims, "missing_component_identity", "Runtime observation has no component identity.", observation);
                continue;
            }
            components.add(component);
            appendClaims(acceptedClaims, observation.path("claims"));
            appendEvidenceRefs(evidenceRefs, observation);
        }

        setTextArray(context, "allowedFields", allowedFields);
        setTextArray(context, "allowedOperations", allowedOperations);
        setTextArray(context, "availableSurfaces", availableSurfaces);
        ObjectNode diagnostics = context.putObject("diagnostics");
        diagnostics.put("observedCount", observedCount);
        diagnostics.put("acceptedComponentCount", components.size());
        diagnostics.put("acceptedClaimCount", acceptedClaims.size());
        diagnostics.put("rejectedClaimCount", rejectedClaims.size());
        diagnostics.put("redactionEnforced", true);
        diagnostics.put("rawRuntimeValuesCopied", false);
        return components.isEmpty() && rejectedClaims.isEmpty() ? null : context;
    }

    private ObjectNode groundedComponent(
            JsonNode observation,
            Set<String> allowedFields,
            Set<String> allowedOperations,
            Set<String> availableSurfaces) {
        JsonNode identity = observation.path("identity");
        String componentId = text(identity.path("componentId"));
        String instanceId = text(identity.path("instanceId"));
        if (!StringUtils.hasText(componentId) || !StringUtils.hasText(instanceId)) {
            return null;
        }
        ObjectNode component = objectMapper.createObjectNode();
        ObjectNode groundedIdentity = component.putObject("identity");
        copyText(identity, groundedIdentity, "instanceId");
        copyText(identity, groundedIdentity, "componentId");
        copyText(identity, groundedIdentity, "componentType");
        copyText(identity, groundedIdentity, "widgetKey");
        copyText(identity, groundedIdentity, "ownerPackage");
        copyText(identity, groundedIdentity, "routeKey");

        JsonNode refs = observation.path("refs");
        if (refs.isObject()) {
            ObjectNode groundedRefs = component.putObject("refs");
            copyText(refs, groundedRefs, "componentMetadataId");
            copyText(refs, groundedRefs, "resourcePath");
            copyText(refs, groundedRefs, "resourceKey");
            copyText(refs, groundedRefs, "pageId");
            copyText(refs, groundedRefs, "runtimeSurfaceInstanceRef");
            JsonNode manifestRef = refs.path("authoringManifestRef");
            if (manifestRef.isObject()) {
                ObjectNode groundedManifestRef = groundedRefs.putObject("authoringManifestRef");
                copyText(manifestRef, groundedManifestRef, "componentId");
                copyText(manifestRef, groundedManifestRef, "version");
                copyText(manifestRef, groundedManifestRef, "source");
                copyText(manifestRef, groundedManifestRef, "hash");
            }
        }

        JsonNode lifecycle = observation.path("lifecycle");
        if (lifecycle.isObject()) {
            ObjectNode groundedLifecycle = component.putObject("lifecycle");
            copyBoolean(lifecycle, groundedLifecycle, "active");
            copyBoolean(lifecycle, groundedLifecycle, "visible");
            copyBoolean(lifecycle, groundedLifecycle, "focused");
            copyText(lifecycle, groundedLifecycle, "capturedAt");
            copyInt(lifecycle, groundedLifecycle, "ttlMs");
        }

        JsonNode snapshot = observation.path("snapshot");
        if (snapshot.isObject()) {
            ObjectNode groundedSnapshot = component.putObject("snapshot");
            copySafeDigest(snapshot.path("selectionDigest"), groundedSnapshot, "selectionDigest");
            copySafeDigest(snapshot.path("dataProfileDigest"), groundedSnapshot, "dataProfileDigest");
            copySafeDigest(snapshot.path("stateDigest"), groundedSnapshot, "stateDigest");
            ArrayNode schemaFieldRefs = textArray(snapshot.path("schemaFieldRefs"), 80);
            if (!schemaFieldRefs.isEmpty()) {
                groundedSnapshot.set("schemaFieldRefs", schemaFieldRefs);
                schemaFieldRefs.forEach(item -> allowedFields.add(item.asText()));
            }
            ArrayNode schemaFieldDescriptors = schemaFieldDescriptors(snapshot.path("schemaFieldDescriptors"), 80);
            if (!schemaFieldDescriptors.isEmpty()) {
                groundedSnapshot.set("schemaFieldDescriptors", schemaFieldDescriptors);
                schemaFieldDescriptors.forEach(item -> addIfText(allowedFields, item.path("fieldRef")));
            }
            copyFieldRefList(snapshot, groundedSnapshot, "omittedFields");
            copyFieldRefList(snapshot, groundedSnapshot, "redactedFieldRefs");
            copyFieldRefList(snapshot, groundedSnapshot, "sensitiveFieldRefs");
            copyFieldRefList(snapshot, groundedSnapshot, "hiddenFieldRefs");
            copyFieldRefList(observation.path("diagnostics"), groundedSnapshot, "omittedFields");
            copyFieldRefList(observation.path("diagnostics"), groundedSnapshot, "redactedFieldRefs");
            copyFieldRefList(observation.path("diagnostics"), groundedSnapshot, "sensitiveFieldRefs");
            copyFieldRefList(observation.path("diagnostics"), groundedSnapshot, "hiddenFieldRefs");
            JsonNode relationSurfaceRefs = snapshot.path("stateDigest").path("relationSurfaceRefs");
            ArrayNode groundedRelationSurfaceRefs = relationSurfaceRefs(relationSurfaceRefs, 80);
            if (!groundedRelationSurfaceRefs.isEmpty()) {
                groundedSnapshot.set("relationSurfaceRefs", groundedRelationSurfaceRefs);
                groundedRelationSurfaceRefs.forEach(item -> {
                    addIfText(availableSurfaces, item.path("id"));
                    addIfText(availableSurfaces, item.path("targetSurface"));
                    addIfText(availableSurfaces, item.path("surfaceRef"));
                });
            }
        }

        JsonNode affordances = observation.path("affordances");
        if (affordances.isObject()) {
            ObjectNode groundedAffordances = component.putObject("affordances");
            ArrayNode activeSurfaceRefs = textArray(affordances.path("activeSurfaceRefs"), 80);
            ArrayNode activeActionRefs = textArray(affordances.path("activeActionRefs"), 80);
            ArrayNode activeOperationRefs = textArray(affordances.path("activeOperationRefs"), 80);
            if (!activeSurfaceRefs.isEmpty()) {
                groundedAffordances.set("activeSurfaceRefs", activeSurfaceRefs);
                activeSurfaceRefs.forEach(item -> availableSurfaces.add(item.asText()));
            }
            if (!activeActionRefs.isEmpty()) {
                groundedAffordances.set("activeActionRefs", activeActionRefs);
                activeActionRefs.forEach(item -> allowedOperations.add(item.asText()));
            }
            if (!activeOperationRefs.isEmpty()) {
                groundedAffordances.set("activeOperationRefs", activeOperationRefs);
                activeOperationRefs.forEach(item -> allowedOperations.add(item.asText()));
            }
        }
        return component;
    }

    private ArrayNode relationSurfaceRefs(JsonNode source, int limit) {
        ArrayNode refs = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return refs;
        }
        int count = 0;
        for (JsonNode item : source) {
            if (count >= limit || !item.isObject()) {
                break;
            }
            ObjectNode ref = objectMapper.createObjectNode();
            copyText(item, ref, "id");
            copyText(item, ref, "label");
            copyText(item, ref, "relation");
            copyText(item, ref, "operationId");
            copyText(item, ref, "statePath");
            copyText(item, ref, "sourceWidget");
            copyText(item, ref, "targetWidget");
            copyText(item, ref, "targetResourcePath");
            copyText(item, ref, "runtimeSurfaceInstanceRef");
            copyText(item, ref, "targetRuntimeSurfaceInstanceRef");
            copyText(item, ref, "sourceRuntimeSurfaceInstanceRef");
            copyText(item, ref, "targetSurface");
            copyText(item, ref, "surfaceRef");
            copyText(item, ref, "queryContextPath");
            JsonNode sourceRef = item.path("source");
            if (sourceRef.isObject()) {
                ObjectNode safeSource = ref.putObject("source");
                copyText(sourceRef, safeSource, "widget");
                copyText(sourceRef, safeSource, "componentType");
                copyText(sourceRef, safeSource, "port");
                copyText(sourceRef, safeSource, "childWidgetKey");
                copyText(sourceRef, safeSource, "runtimeSurfaceInstanceRef");
            }
            JsonNode targetRef = item.path("target");
            if (targetRef.isObject()) {
                ObjectNode safeTarget = ref.putObject("target");
                copyText(targetRef, safeTarget, "widget");
                copyText(targetRef, safeTarget, "componentType");
                copyText(targetRef, safeTarget, "port");
                copyText(targetRef, safeTarget, "childWidgetKey");
                copyText(targetRef, safeTarget, "resourcePath");
                copyText(targetRef, safeTarget, "runtimeSurfaceInstanceRef");
            }
            JsonNode queryMapping = item.path("queryMapping");
            if (queryMapping.isObject()) {
                ObjectNode safeQueryMapping = ref.putObject("queryMapping");
                copyText(queryMapping, safeQueryMapping, "sourceField");
                copyText(queryMapping, safeQueryMapping, "targetFilterField");
                copyText(queryMapping, safeQueryMapping, "targetPath");
                copyText(queryMapping, safeQueryMapping, "valueSource");
            }
            ArrayNode semanticAliases = textArray(item.path("semanticAliases"), 12);
            if (!semanticAliases.isEmpty()) {
                ref.set("semanticAliases", semanticAliases);
            }
            refs.add(ref);
            count++;
        }
        return refs;
    }

    private void copySafeDigest(JsonNode source, ObjectNode target, String fieldName) {
        if (!source.isObject()) {
            return;
        }
        ObjectNode copy = objectMapper.createObjectNode();
        source.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            if (value.isTextual()) {
                copy.put(entry.getKey(), value.asText());
            } else if (value.isInt() || value.isLong()) {
                copy.put(entry.getKey(), value.asLong());
            } else if (value.isDouble() || value.isFloat() || value.isBigDecimal()) {
                copy.put(entry.getKey(), value.asDouble());
            } else if (value.isBoolean()) {
                copy.put(entry.getKey(), value.asBoolean());
            } else if (value.isArray()) {
                ArrayNode values = textArray(value, 80);
                if (!values.isEmpty()) {
                    copy.set(entry.getKey(), values);
                }
            }
        });
        if (!copy.isEmpty()) {
            target.set(fieldName, copy);
        }
    }

    private void appendClaims(ArrayNode target, JsonNode claims) {
        if (!claims.isArray()) {
            return;
        }
        for (JsonNode claim : claims) {
            if (!claim.isObject()) {
                continue;
            }
            ObjectNode grounded = objectMapper.createObjectNode();
            copyText(claim, grounded, "kind");
            copyText(claim, grounded, "ref");
            copyText(claim, grounded, "digest");
            copyBoolean(claim, grounded, "observed");
            if (StringUtils.hasText(grounded.path("kind").asText(""))
                    && StringUtils.hasText(grounded.path("ref").asText(""))) {
                target.add(grounded);
            }
        }
    }

    private void appendEvidenceRefs(ArrayNode target, JsonNode observation) {
        ObjectNode evidenceRef = objectMapper.createObjectNode();
        evidenceRef.put("source", "runtimeComponentObservation");
        copyText(observation.path("identity"), evidenceRef, "instanceId");
        copyText(observation.path("identity"), evidenceRef, "componentId");
        copyText(observation.path("refs"), evidenceRef, "componentMetadataId");
        copyText(observation.path("refs"), evidenceRef, "resourcePath");
        copyText(observation.path("refs"), evidenceRef, "resourceKey");
        copyText(observation.path("refs"), evidenceRef, "pageId");
        copyText(observation.path("diagnostics"), evidenceRef, "snapshotHash");
        target.add(evidenceRef);
    }

    private void reject(ArrayNode target, String reason, String message, JsonNode observation) {
        ObjectNode rejected = objectMapper.createObjectNode();
        rejected.put("reason", reason);
        rejected.put("message", message);
        if (observation != null && observation.isObject()) {
            copyText(observation.path("identity"), rejected, "instanceId");
            copyText(observation.path("identity"), rejected, "componentId");
            copyText(observation, rejected, "schemaVersion");
        }
        target.add(rejected);
    }

    private ArrayNode textArray(JsonNode source, int limit) {
        ArrayNode target = objectMapper.createArrayNode();
        if (!source.isArray()) {
            return target;
        }
        int count = 0;
        for (JsonNode item : source) {
            if (count >= limit) {
                break;
            }
            String text = text(item);
            if (StringUtils.hasText(text)) {
                target.add(text);
                count++;
            }
        }
        return target;
    }

    private void copyFieldRefList(JsonNode source, ObjectNode target, String fieldName) {
        ArrayNode fieldRefs = textArray(source.path(fieldName), 80);
        if (!fieldRefs.isEmpty()) {
            target.set(fieldName, fieldRefs);
        }
    }

    private ArrayNode objectArray(JsonNode source, Set<String> allowedKeys, int limit) {
        ArrayNode target = objectMapper.createArrayNode();
        if (!source.isArray()) {
            return target;
        }
        int count = 0;
        for (JsonNode item : source) {
            if (count >= limit) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            ObjectNode safe = objectMapper.createObjectNode();
            allowedKeys.forEach(key -> copyText(item, safe, key));
            if (!safe.isEmpty()) {
                target.add(safe);
                count++;
            }
        }
        return target;
    }

    private ArrayNode schemaFieldDescriptors(JsonNode source, int limit) {
        ArrayNode target = objectMapper.createArrayNode();
        if (!source.isArray()) {
            return target;
        }
        int count = 0;
        for (JsonNode item : source) {
            if (count >= limit) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            ObjectNode descriptor = objectMapper.createObjectNode();
            String fieldRef = firstNonBlank(
                    text(item.path("fieldRef")),
                    text(item.path("ref")),
                    text(item.path("field")),
                    text(item.path("path")),
                    text(item.path("name")));
            if (!safeIdentifier(fieldRef)) {
                continue;
            }
            descriptor.put("fieldRef", fieldRef);
            copyText(item, descriptor, "fieldType");
            copyText(item, descriptor, "valueType");
            copyText(item, descriptor, "dataType");
            copyText(item, descriptor, "semanticType");
            copyText(item, descriptor, "type");
            copyText(item, descriptor, "format");
            copyText(item, descriptor, "controlType");
            target.add(descriptor);
            count++;
        }
        return target;
    }

    private void setTextArray(ObjectNode target, String fieldName, Set<String> values) {
        ArrayNode array = target.putArray(fieldName);
        values.stream()
                .filter(StringUtils::hasText)
                .limit(120)
                .forEach(array::add);
    }

    private void copyText(JsonNode source, ObjectNode target, String fieldName) {
        String value = text(source.path(fieldName));
        if (StringUtils.hasText(value)) {
            target.put(fieldName, value);
        }
    }

    private void copyBoolean(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.path(fieldName);
        if (value.isBoolean()) {
            target.put(fieldName, value.asBoolean());
        }
    }

    private void copyInt(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.path(fieldName);
        if (value.canConvertToInt()) {
            target.put(fieldName, value.asInt());
        }
    }

    private void addIfText(Set<String> target, JsonNode value) {
        String text = text(value);
        if (StringUtils.hasText(text)) {
            target.add(text);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean safeIdentifier(String value) {
        return StringUtils.hasText(value)
                && value.length() <= 80
                && value.matches("[A-Za-z_][A-Za-z0-9_.-]*");
    }

    private boolean isStale(JsonNode lifecycle) {
        if (lifecycle == null || !lifecycle.isObject()) {
            return false;
        }
        String capturedAt = text(lifecycle.path("capturedAt"));
        JsonNode ttl = lifecycle.path("ttlMs");
        if (!StringUtils.hasText(capturedAt) || !ttl.canConvertToLong() || ttl.asLong() <= 0L) {
            return false;
        }
        try {
            return Instant.parse(capturedAt).plusMillis(ttl.asLong()).isBefore(Instant.now());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private String textOrDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
