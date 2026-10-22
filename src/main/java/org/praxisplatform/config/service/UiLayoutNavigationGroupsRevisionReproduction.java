package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.HashSet;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Closed P0 native groups profile and exact revision relation. This checks data only:
 * the host must admit the registered root, source and icon catalog under current authority.
 * Omission from the complete groups array removes a group; page bindings never belong here.
 */
final class UiLayoutNavigationGroupsRevisionReproduction {
  static final String KIND = "praxis.navigation-groups.editor";
  static final String TYPE = "praxis-app-shell";
  private static final JsonSchema SCHEMA = schema();

  private UiLayoutNavigationGroupsRevisionReproduction() {}

  private static JsonSchema schema() {
    try (var input = UiLayoutNavigationGroupsRevisionReproduction.class.getResourceAsStream(
        "/org/praxisplatform/config/page-publication.schema.json")) {
      ObjectNode schema = (ObjectNode) new ObjectMapper().readTree(input);
      schema.put("$ref", "#/$defs/groupsDocument");
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
    } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
  }

  static void requireNative(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor) {
    if (target == null || descriptor == null || patchDescriptor == null
        || !KIND.equals(descriptor.documentType()) || !TYPE.equals(target.componentType())
        || !target.componentId().matches(UiLayoutPageRevisionReproduction.UUID_PATTERN)
        || !"1".equals(descriptor.schemaVersion())
        || !"praxis.ui-layout/v1".equals(patchDescriptor.schemaVersion())) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "Navigation groups native revision descriptor is unsupported.");
    }
    requireDocument(baseline);
    requireDocument(candidate);
    UiLayoutRevisionRelation.requireUnchangedEnvelope((ObjectNode) baseline, (ObjectNode) candidate);
  }

  static void require(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patch) {
    if (descriptor == null || !KIND.equals(descriptor.documentType())) return;
    requireNative(target, descriptor, baseline, candidate, patchDescriptor);
    UiLayoutJsonBounds.requireDocument(patch, UiLayoutLifecycleException.Code.VALIDATION_FAILED, () -> {});
    if (!patch.isObject()) throw invalid();
    UiLayoutRevisionRelation.requireReproduction((ObjectNode) baseline.get("config"),
        (ObjectNode) candidate.get("config"), (ObjectNode) patch);
  }

  private static void requireDocument(JsonNode document) {
    UiLayoutJsonBounds.requireDocument(document, UiLayoutLifecycleException.Code.VALIDATION_FAILED, () -> {});
    if (!SCHEMA.validate(document).isEmpty()) throw invalid();
    var identities = new HashSet<String>();
    for (JsonNode group : document.at("/config/groups")) {
      if (!identities.add(group.path("id").textValue())) throw invalid();
    }
  }

  private static UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        "Navigation groups native document is outside the admitted profile.");
  }
}
