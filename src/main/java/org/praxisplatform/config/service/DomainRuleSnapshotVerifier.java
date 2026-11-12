package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.plan.RulePlanException;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.snapshot.CompiledRuleSnapshot;
import org.praxisplatform.rules.snapshot.PraxisRuleSnapshotCompiler;

/** Internal canonical verifier shared by historical reads and executable admission paths. */
final class DomainRuleSnapshotVerifier {
  static final int MINIMUM_DISTINCT_APPROVERS = 2;
  static final String COMPOSITION_CONTRACT_VERSION = "praxis-rule-composition/2";
  private final ObjectMapper objectMapper;
  private final DomainRuleImplementationCatalog implementationCatalog;

  DomainRuleSnapshotVerifier(ObjectMapper objectMapper, DomainRuleImplementationCatalog implementationCatalog) {
    this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper is required");
    this.implementationCatalog = java.util.Objects.requireNonNull(implementationCatalog, "implementationCatalog is required");
  }

  PublishedRuleSnapshot readVerifiedSnapshot(DomainRuleSnapshot stored) {
    PublishedRuleSnapshot snapshot = readHistoricallyVerifiedSnapshot(stored);
    requireCompositionManifest(stored);
    return verifyCurrentRuntime(stored, snapshot);
  }

  /** Verifies immutable evidence independently of the current engine and implementation catalog. */
  PublishedRuleSnapshot readHistoricallyVerifiedSnapshot(DomainRuleSnapshot stored) {
    PublishedRuleSnapshot snapshot = readSnapshot(stored);
    verifyStoredEnvelope(stored, snapshot);
    String canonicalHash = PraxisCanonicalJson.sha256(objectMapper.valueToTree(snapshot));
    if (!canonicalHash.equals(stored.getContentHash())) {
      throw new IllegalStateException("Persisted RuleSet snapshot content hash verification failed");
    }
    if (!isPreManifestSnapshot(stored)) {
      verifyStoredComposition(stored, snapshot);
    } else if (!snapshot.ruleSet().errorDescriptors().isEmpty()) {
      throw new IllegalStateException("Persisted error descriptors require composition approval");
    }
    return snapshot;
  }

  void requireCompositionManifest(DomainRuleSnapshot stored) {
    if (isPreManifestSnapshot(stored)) {
      throw new IllegalStateException("Persisted RuleSet composition manifest is unreadable");
    }
  }

  String governanceState(DomainRuleSnapshot stored) {
    try {
      // Integrity takes precedence: incompatible or pre-manifest content may also be corrupt.
      PublishedRuleSnapshot snapshot = readHistoricallyVerifiedSnapshot(stored);
      if (isPreManifestSnapshot(stored)) return "REPUBLICATION_REQUIRED";
      if (!RuleRuntimeCompatibility.current().equals(snapshot.ruleSet().compatibility())) {
        return "RUNTIME_INCOMPATIBLE";
      }
      verifyCurrentRuntime(stored, snapshot);
      return "READY";
    } catch (RuntimeException invalid) {
      return "INVALID";
    }
  }

  private PublishedRuleSnapshot verifyCurrentRuntime(
      DomainRuleSnapshot stored, PublishedRuleSnapshot snapshot) {
    CompiledRuleSnapshot compiled;
    try {
      compiled = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.planning(
          allowedImplementations(snapshot.tenantId(), snapshot.environment(), snapshot.ownerServiceKey())))
          .compile(snapshot, snapshot.requiredHostContractVersion());
    } catch (RulePlanException exception) {
      throw new IllegalStateException(
          "Persisted RuleSet snapshot is no longer admitted by the host implementation catalog", exception);
    }
    if (!stored.getContentHash().equals(compiled.snapshotContentHash())) {
      throw new IllegalStateException("Persisted RuleSet snapshot content hash verification failed");
    }
    return compiled.snapshot();
  }

  private void verifyStoredEnvelope(DomainRuleSnapshot stored, PublishedRuleSnapshot snapshot) {
    if (!stored.getSnapshotKey().equals(snapshot.snapshotKey())
        || !stored.getTenantId().equals(snapshot.tenantId())
        || !stored.getEnvironment().equals(snapshot.environment())
        || !stored.getRuleSetKey().equals(snapshot.ruleSet().ref().ruleSetKey())
        || !snapshot.ruleSet().ref().operationKey().equals(stored.getOperationKey())
        || !stored.getRuleSetVersion().equals(snapshot.ruleSet().ref().version())
        || !stored.getPublicationRevision().equals(snapshot.publicationRevision())) {
      throw new IllegalStateException("Persisted RuleSet snapshot identity does not match its immutable envelope");
    }
  }

  private boolean isPreManifestSnapshot(DomainRuleSnapshot stored) {
    return stored.getCompositionManifest() == null && stored.getCompositionDigest() == null;
  }

  private void verifyStoredComposition(DomainRuleSnapshot stored, PublishedRuleSnapshot snapshot) {
    if (stored.getCompositionManifest() == null || stored.getCompositionManifest().isBlank()
        || stored.getCompositionDigest() == null || stored.getCompositionDigest().isBlank()) {
      throw new IllegalStateException("Persisted RuleSet composition manifest is unreadable");
    }
    try {
      var manifest = objectMapper.readTree(stored.getCompositionManifest());
      var approvedDescriptors = manifest.path("ruleSet").path("errorDescriptors");
      var storedDescriptors = snapshot.ruleSet().errorDescriptors();
      if ((!approvedDescriptors.isMissingNode() || !storedDescriptors.isEmpty())
          && !approvedDescriptors.equals(objectMapper.valueToTree(storedDescriptors))) {
        throw new IllegalStateException("Persisted error descriptors do not match the approved composition");
      }
      if (!storedDescriptors.isEmpty()) {
        verifyDescriptorScope(manifest, snapshot);
      }
      if (manifest.has("productProfile")) {
        var product=objectMapper.treeToValue(manifest.get("productProfile"),DomainRuleProductProfile.class);
        if (!product.ownerServiceKey().equals(snapshot.ownerServiceKey())
            || !product.hostContractVersion().equals(snapshot.requiredHostContractVersion()) || !product.matches(snapshot.ruleSet())
            || !snapshot.sources().equals(List.of(product.source(objectMapper)))
            || !manifest.path("sources").equals(objectMapper.valueToTree(snapshot.sources())))
          throw new IllegalStateException("Persisted product provenance differs from its approved profile");
      } else if (snapshot.sources().stream().anyMatch(source->source.definitionId().startsWith("product:"))) {
        throw new IllegalStateException("Product provenance requires its approved server profile");
      }
      String digest = PraxisCanonicalJson.sha256(manifest);
      long approvers = snapshot.approvals().stream()
          .filter(approval -> "RULE_COMPOSITION_APPROVER".equals(approval.role()))
          .filter(approval -> digest.equals(approval.evidenceHash()))
          .map(RuleSnapshotApproval::actorRef).distinct().count();
      if (!digest.equals(stored.getCompositionDigest()) || approvers < MINIMUM_DISTINCT_APPROVERS) {
        throw new IllegalStateException("Persisted RuleSet composition approval verification failed");
      }
      if (COMPOSITION_CONTRACT_VERSION.equals(manifest.path("compositionContractVersion").asText())) {
        var testEvidence = manifest.get("testEvidence");
        if (testEvidence == null || !testEvidence.isArray()) {
          throw new IllegalStateException("Persisted RuleSet test evidence manifest is unreadable");
        }
        for (var item : testEvidence) {
          if (!item.path("satisfied").asBoolean(false)
              || item.path("definitionId").asText().isBlank()
              || item.path("stage").asText().isBlank()
              || item.path("workspaceId").asText().isBlank()
              || item.path("testRunId").asText().isBlank()
              || item.path("requestHash").asText().isBlank()
              || item.path("evidenceDigest").asText().isBlank()) {
            throw new IllegalStateException("Persisted RuleSet test evidence manifest is invalid");
          }
        }
      }
    } catch (JsonProcessingException | NullPointerException exception) {
      throw new IllegalStateException("Persisted RuleSet composition manifest is unreadable", exception);
    }
  }

  private void verifyDescriptorScope(JsonNode manifest, PublishedRuleSnapshot snapshot) {
    var approved = manifest.path("ruleSet");
    var ruleSet = snapshot.ruleSet();
    if (!snapshot.tenantId().equals(manifest.path("tenantId").asText())
        || !snapshot.environment().equals(manifest.path("environment").asText())
        || !snapshot.ownerServiceKey().equals(manifest.path("ownerServiceKey").asText())
        || !approved.path("ref").equals(objectMapper.valueToTree(ruleSet.ref()))) {
      throw new IllegalStateException("Persisted error descriptors do not match the approved scope");
    }
    for (String key : ruleSet.errorDescriptors().keySet()) {
      var binding = ruleSet.bindings().stream().filter(item -> item.bindingKey().equals(key))
          .findFirst().orElseThrow(() -> new IllegalStateException("Persisted descriptor binding is missing"));
      var slot = ruleSet.slots().stream().filter(item -> item.slotKey().equals(binding.slotKey()))
          .findFirst().orElseThrow(() -> new IllegalStateException("Persisted descriptor slot is missing"));
      if (!contains(approved.path("bindings"), objectMapper.valueToTree(binding))
          || !contains(approved.path("slots"), objectMapper.valueToTree(slot))) {
        throw new IllegalStateException("Persisted error descriptor binding or slot was not approved");
      }
    }
  }

  private static boolean contains(JsonNode array, JsonNode expected) {
    if (!array.isArray()) return false;
    for (var element : array) {
      if (expected.equals(element)) return true;
    }
    return false;
  }

  private PublishedRuleSnapshot readSnapshot(DomainRuleSnapshot stored) {
    try {
      return objectMapper.readValue(stored.getSnapshotPayload(), PublishedRuleSnapshot.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Persisted RuleSet snapshot is unreadable", exception);
    }
  }

  List<RuleImplementationRef> allowedImplementations(
      String tenant, String environment, String ownerServiceKey) {
    DomainRuleImplementationScope scope = new DomainRuleImplementationScope(
        tenant, environment, ownerServiceKey);
    Collection<RuleImplementationRef> allowed = implementationCatalog.allowedImplementations(scope);
    if (allowed == null) {
      throw new IllegalStateException("DomainRuleImplementationCatalog must not return null");
    }
    return allowed.stream()
        .sorted(Comparator.comparing(RuleImplementationRef::implementationKey)
            .thenComparing(RuleImplementationRef::implementationVersion))
        .toList();
  }

}
