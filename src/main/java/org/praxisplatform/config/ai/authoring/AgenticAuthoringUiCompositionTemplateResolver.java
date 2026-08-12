package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiRegistryTemplateRevision;
import org.praxisplatform.config.service.AiRegistryTemplateService;

/**
 * Resolves an exact, content-pinned template reference before the pure UI composition compiler.
 *
 * <p>The resolver deliberately supports only complete-plan references without overrides. Semantic
 * search may help an author choose a template before this stage, but it never participates in the
 * exact resolution decision.</p>
 */
public final class AgenticAuthoringUiCompositionTemplateResolver {

    static final String PLAN_KIND = "praxis.ui-composition-plan";
    static final String PLAN_VERSION = "1.0";
    static final String RESOLVED_WARNING = "ui-composition-template-reference-resolved";

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> REFERENCE_DOCUMENT_FIELDS =
            Set.of("version", "kind", "templateRef", "overrides");

    private final AiRegistryTemplateService templateService;

    public AgenticAuthoringUiCompositionTemplateResolver(AiRegistryTemplateService templateService) {
        this.templateService = templateService;
    }

    public Resolution resolve(JsonNode candidate) {
        if (candidate == null || !candidate.isObject() || !candidate.has("templateRef")) {
            return Resolution.notReferenced(candidate);
        }

        List<String> failures = validateReferenceDocument(candidate);
        if (!failures.isEmpty()) {
            return Resolution.invalid(candidate, failures);
        }

        JsonNode templateRef = candidate.path("templateRef");
        String registryKey = templateRef.path("registryKey").textValue();
        String expectedHash = templateRef.path("configSha256").textValue();
        Optional<AiRegistry> storedTemplate = templateService.getTemplate(registryKey);
        if (storedTemplate.isEmpty()) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-not-found"));
        }

        AiRegistry registry = storedTemplate.get();
        if (!"active".equalsIgnoreCase(safe(registry.getStatus()))) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-inactive"));
        }

        AiRegistryTemplateRecord record = templateService.toRecord(registry);
        if (record == null) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-config-invalid"));
        }
        AiRegistryTemplateRevision revision = record == null ? null : record.getRevision();
        String actualHash = revision == null ? null : revision.getConfigSha256();
        if (!SHA_256.matcher(safe(actualHash)).matches()) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-revision-invalid"));
        }
        if (!constantTimeEquals(expectedHash, actualHash)) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-hash-mismatch"));
        }

        JsonNode configJson = record.getConfigJson();
        if (configJson == null || !configJson.isObject()) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-config-invalid"));
        }
        JsonNode authoringPlan = configJson.get("authoringPlan");
        if (authoringPlan == null || !authoringPlan.isObject()) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-authoring-plan-missing"));
        }
        if (!PLAN_KIND.equals(authoringPlan.path("kind").asText(""))) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-authoring-plan-kind-invalid"));
        }
        if (!PLAN_VERSION.equals(authoringPlan.path("version").asText(""))) {
            return Resolution.invalid(candidate, List.of("ui-composition-template-authoring-plan-version-invalid"));
        }

        ObjectNode resolvedPlan = authoringPlan.deepCopy();
        ObjectNode diagnostics = resolvedPlan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : resolvedPlan.putObject("diagnostics");
        ObjectNode evidence = diagnostics.putObject("templateResolution");
        evidence.put("registryKey", registryKey);
        evidence.put("configSha256", actualHash);
        if (revision.getVersion() != null) {
            evidence.put("version", revision.getVersion());
        }
        if (revision.getEtag() != null && !revision.getEtag().isBlank()) {
            evidence.put("etag", revision.getEtag());
        }
        return Resolution.resolved(resolvedPlan);
    }

    private List<String> validateReferenceDocument(JsonNode candidate) {
        List<String> failures = new ArrayList<>();
        if (!PLAN_KIND.equals(candidate.path("kind").asText(""))) {
            failures.add("ui-composition-template-reference-kind-invalid");
        }
        if (!PLAN_VERSION.equals(candidate.path("version").asText(""))) {
            failures.add("ui-composition-template-reference-version-invalid");
        }
        JsonNode templateRef = candidate.path("templateRef");
        if (!templateRef.isObject()) {
            failures.add("ui-composition-template-reference-object-required");
            return List.copyOf(failures);
        }
        JsonNode registryKeyNode = templateRef.get("registryKey");
        String registryKey = registryKeyNode != null && registryKeyNode.isTextual()
                ? registryKeyNode.textValue()
                : "";
        if (registryKey.isBlank() || !registryKey.equals(registryKey.trim())) {
            failures.add("ui-composition-template-registry-key-invalid");
        }
        JsonNode configSha256Node = templateRef.get("configSha256");
        String configSha256 = configSha256Node != null && configSha256Node.isTextual()
                ? configSha256Node.textValue()
                : "";
        if (!SHA_256.matcher(configSha256).matches()) {
            failures.add("ui-composition-template-config-sha256-invalid");
        }
        if (templateRef.size() != 2
                || !templateRef.has("registryKey")
                || !templateRef.has("configSha256")) {
            failures.add("ui-composition-template-reference-fields-invalid");
        }
        JsonNode overrides = candidate.get("overrides");
        if (overrides != null && (!overrides.isObject() || !overrides.isEmpty())) {
            failures.add("ui-composition-template-overrides-unsupported");
        }
        Iterator<String> fields = candidate.fieldNames();
        while (fields.hasNext()) {
            if (!REFERENCE_DOCUMENT_FIELDS.contains(fields.next())) {
                failures.add("ui-composition-template-reference-mixed-plan");
                break;
            }
        }
        return List.copyOf(failures);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record Resolution(
            boolean referenced,
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode uiCompositionPlan) {

        static Resolution notReferenced(JsonNode plan) {
            return new Resolution(false, true, List.of(), List.of(), plan);
        }

        static Resolution invalid(JsonNode plan, List<String> failures) {
            return new Resolution(true, false, List.copyOf(failures), List.of(), plan);
        }

        static Resolution resolved(JsonNode plan) {
            return new Resolution(true, true, List.of(), List.of(RESOLVED_WARNING), plan);
        }
    }
}
