package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Strict codec and invariant checker for the persisted workspace envelope. */
public class UiLayoutDraftWorkspaceCodec {
  public static final int MAX_DOCUMENT_BYTES = 256 * 1024;
  private final ObjectMapper mapper;
  private final CanonicalJsonHashService hashes;

  public UiLayoutDraftWorkspaceCodec(ObjectMapper mapper, CanonicalJsonHashService hashes) {
    // Closed JSON/UUID envelope: application serializers must not rewrite pinned native documents or identities.
    this.mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
    this.hashes = hashes;
  }

  public UiLayoutDraftWorkspaceDocument fromSeed(UiLayoutLifecycleInvocation invocation, UiLayoutDraftWorkspaceSeed seed) {
    if (seed == null) throw unavailable("Workspace source returned no seed.");
    List<UiLayoutTarget> registered = invocation.composition().targets();
    if (seed.targets().size() != registered.size()) throw unavailable("Workspace source did not return every registered target.");
    List<UiLayoutDraftWorkspaceDocument.TargetState> states = new ArrayList<>();
    for (int index = 0; index < registered.size(); index++) {
      UiLayoutDraftWorkspaceSeed.TargetSeed value = seed.targets().get(index);
      if (value == null || !registered.get(index).equals(value.target())) {
        throw unavailable("Workspace source target order differs from the registered composition.");
      }
      JsonNode baseline = copyObject(value.document(), "baseline document");
      requireSize(baseline, "baseline document");
      String hash = hashes.sha256Exact(baseline);
      states.add(new UiLayoutDraftWorkspaceDocument.TargetState(value.target(), value.authoring(), value.patch(),
          new UiLayoutDraftWorkspaceDocument.BaselineDocument(value.sourceRef(), hash, baseline),
          new UiLayoutDraftWorkspaceDocument.WorkingDocument(hash, baseline), null, null));
    }
    return new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, states, null);
  }

  public String encode(UiLayoutDraftWorkspaceDocument document) {
    validate(document);
    try { return mapper.writeValueAsString(document); }
    catch (Exception exception) { throw invalid("Workspace document could not be encoded."); }
  }

  public UiLayoutDraftWorkspaceDocument decode(String json, UiLayoutCompositionRegistration registration) {
    UiLayoutDraftWorkspaceDocument document = decodeHistorical(json);
    List<UiLayoutTarget> targets = document.targets().stream().map(UiLayoutDraftWorkspaceDocument.TargetState::target).toList();
    if (!registration.targets().equals(targets)) throw invalid("Workspace targets differ from the registered composition.");
    return document;
  }

  /**
   * Reads pinned evidence independently of today's target registry. This verifies the envelope,
   * not authorization, immutable repository references or compatibility with current contracts.
   * Callers must establish authorized historical scope before exposing any recovered document.
   */
  public UiLayoutDraftWorkspaceDocument decodeHistorical(String json) {
    try {
      UiLayoutDraftWorkspaceDocument document = mapper.readValue(json, UiLayoutDraftWorkspaceDocument.class);
      validate(document);
      return document;
    } catch (UiLayoutLifecycleException exception) { throw exception; }
    catch (Exception exception) { throw invalid("Workspace document is unavailable or malformed."); }
  }

  public void requireSize(JsonNode document, String name) {
    try {
      if (mapper.writeValueAsBytes(document).length > MAX_DOCUMENT_BYTES) {
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_REQUEST,
            name + " exceeds " + MAX_DOCUMENT_BYTES + " bytes.");
      }
    } catch (UiLayoutLifecycleException exception) { throw exception; }
    catch (Exception exception) { throw invalid(name + " could not be measured."); }
  }

  private void validate(UiLayoutDraftWorkspaceDocument document) {
    if (document == null) throw invalid("Workspace document is unavailable.");
    var identities = new LinkedHashSet<UiLayoutTarget>();
    for (UiLayoutDraftWorkspaceDocument.TargetState target : document.targets()) {
      if (!identities.add(target.target())) throw invalid("Workspace contains duplicate targets.");
      requireSize(target.baseline().document(), "baseline document");
      requireSize(target.working().document(), "working document");
      if (!hashes.sha256Exact(target.baseline().document()).equals(target.baseline().contentHash())) {
        throw invalid("Workspace baseline hash is invalid.");
      }
      if (!hashes.sha256Exact(target.working().document()).equals(target.working().contentHash())) {
        throw invalid("Workspace working hash is invalid.");
      }
      if (target.selectedAssignment() != null && (target.selectedRevision() == null
          || !target.selectedAssignment().revisionRef().equals(target.selectedRevision().revisionRef()))) {
        throw invalid("Workspace assignment does not select the current revision.");
      }
    }
  }

  private JsonNode copyObject(JsonNode value, String name) {
    if (value == null || !value.isObject()) throw unavailable(name + " must be an object.");
    return value.deepCopy();
  }

  private UiLayoutLifecycleException invalid(String message) {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE, message);
  }

  private UiLayoutLifecycleException unavailable(String message) {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, message);
  }
}
