package org.praxisplatform.config.service;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.dto.AppliedUiLayoutContributionReceipt;
import org.praxisplatform.config.dto.EffectiveUiLayoutCompositionMemberReceipt;
import org.praxisplatform.config.dto.EffectiveUiLayoutCompositionResponse;
import org.praxisplatform.config.dto.EffectiveUiLayoutResponse;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.transaction.annotation.Transactional;

/** Coherent aggregate reader. It has no cache and fixes exactly one release head per call. */
public class UiLayoutCompositionReadService {
  static final String SCHEMA_VERSION = "praxis.effective-ui-layout-composition/v1";
  private final EffectiveUiAudienceProvider audiences;
  private final UiLayoutCompositionRegistry registry;
  private final UiLayoutCompositionReleaseReader releases;
  private final UiLayoutCompositionCandidateSnapshotSource snapshots;
  private final UiLayoutResolutionService engine;
  private final CanonicalJsonHashService hashes;
  private final Clock clock;

  public UiLayoutCompositionReadService(EffectiveUiAudienceProvider audiences, UiLayoutCompositionRegistry registry,
      UiLayoutCompositionReleaseReader releases, UiLayoutCompositionCandidateSnapshotSource snapshots,
      UiLayoutResolutionService engine, CanonicalJsonHashService hashes, Clock clock) {
    this.audiences = audiences; this.registry = registry; this.releases = releases; this.snapshots = snapshots;
    this.engine = engine; this.hashes = hashes; this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public EffectiveUiLayoutCompositionResponse resolve(Principal principal, String expectedContextVersion, UiLayoutTarget rootTarget) {
    if (principal == null) throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.SESSION_REQUIRED, "Authenticated host principal is required.", null);
    if (expectedContextVersion == null || expectedContextVersion.isBlank()) throw new IllegalArgumentException("expectedContextVersion is required.");
    EffectiveUiAudience audience;
    try { audience = audiences.resolve(principal, expectedContextVersion.trim()); }
    catch (UiLayoutResolutionException exception) { throw exception; }
    catch (RuntimeException exception) { throw unavailable("Authoritative UI audience is unavailable."); }
    if (audience == null) throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.AUDIENCE_SOURCE_UNAVAILABLE, "Authoritative UI audience is unavailable.", null);
    if (!expectedContextVersion.trim().equals(audience.contextVersion())) throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.CONTEXT_STALE, "Runtime context changed before layout resolution.", null);
    UiLayoutCompositionRegistration registration;
    try { registration = registry.resolve(audience, rootTarget); }
    catch (UiLayoutResolutionException exception) { throw exception; }
    catch (RuntimeException exception) { throw unavailable("Composition registration is unavailable."); }
    if (registration == null || !rootTarget.equals(registration.rootTarget())) throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.LAYOUT_ACCESS_DENIED, "Composition registration is unavailable to the current context.", null);
    UiLayoutCompositionReleaseSnapshot release;
    try { release = releases.read(audience.tenant(), audience.environment(), rootTarget); }
    catch (UiLayoutResolutionException exception) { throw exception; }
    catch (RuntimeException exception) { throw unavailable("Composition release source is unavailable."); }
    validateMembership(registration, release);
    UiLayoutCompositionCandidateSnapshot baseline;
    try { baseline = snapshots.snapshot(audience, registration); }
    catch (UiLayoutResolutionException exception) { throw exception; }
    catch (RuntimeException exception) { throw unavailable("Composition candidate source is unavailable."); }
    if (baseline == null) throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.LAYOUT_SOURCE_UNAVAILABLE, "Composition candidate source is unavailable.", null);
    if (!baseline.authorizedTargets().containsAll(registration.targets())) {
      throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.LAYOUT_ACCESS_DENIED, "Composition target access was not granted.", null);
    }
    UiLayoutCandidateSource source = new PinnedUiLayoutCandidateSource(baseline, release);
    List<EffectiveUiLayoutCompositionMemberReceipt> members = new ArrayList<>();
    for (int order = 0; order < registration.targets().size(); order++) {
      UiLayoutTarget target = registration.targets().get(order);
      UiLayoutResolutionService.UiLayoutResolutionResult result = engine.resolveDetailedForAudience(audience, target, source).orElse(null);
      EffectiveUiLayoutResponse effective = result == null ? null : result.response();
      members.add(new EffectiveUiLayoutCompositionMemberReceipt(order, target, applied(release, target, result), effective));
    }
    String etag = receiptEtag(release, rootTarget, audience.contextVersion(), members);
    return new EffectiveUiLayoutCompositionResponse(SCHEMA_VERSION, release.releaseRef(), rootTarget, audience.contextVersion(), etag, members, Instant.now(clock));
  }

  private void validateMembership(UiLayoutCompositionRegistration registration, UiLayoutCompositionReleaseSnapshot release) {
    Set<UiLayoutTarget> targets = new HashSet<>(registration.targets());
    for (UiLayoutCompositionContribution value : release.contributions()) {
      if (!targets.contains(value.target())) throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.LAYOUT_REVISION_INTEGRITY, "Release contains a target outside its registered composition.", null);
    }
  }

  private List<AppliedUiLayoutContributionReceipt> applied(UiLayoutCompositionReleaseSnapshot release, UiLayoutTarget target, UiLayoutResolutionService.UiLayoutResolutionResult result) {
    if (result == null) return List.of();
    Set<UiLayoutResolutionCandidate> applied = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    applied.addAll(result.appliedCandidates());
    return release.contributions().stream().filter(value -> target.equals(value.target()) && applied.contains(value.candidate()))
        .map(value -> new AppliedUiLayoutContributionReceipt(value.memberOrder(), value.assignmentRevisionRef(), value.contentRevisionRef(), value.contentHash())).toList();
  }

  private String receiptEtag(UiLayoutCompositionReleaseSnapshot release, UiLayoutTarget root, String context, List<EffectiveUiLayoutCompositionMemberReceipt> members) {
    List<Map<String, Object>> stableMembers = new ArrayList<>();
    for (EffectiveUiLayoutCompositionMemberReceipt member : members) {
      Map<String, Object> value = new HashMap<>(); value.put("memberOrder", member.memberOrder()); value.put("target", member.target());
      value.put("appliedContributions", member.appliedContributions());
      value.put("effective", member.effectiveLayout() == null ? "ABSENT" : Map.of("etag", member.effectiveLayout().compositeEtag(), "document", member.effectiveLayout().document(), "layers", member.effectiveLayout().appliedLayers()));
      stableMembers.add(value);
    }
    return hashes.sha256(Map.of("schemaVersion", SCHEMA_VERSION, "releaseState", release.releaseRef() == null ? "BASE" : "ACTIVE",
        "releaseRef", release.releaseRef() == null ? "" : release.releaseRef(), "root", root, "context", context, "members", stableMembers));
  }

  private UiLayoutResolutionException unavailable(String message) {
    return new UiLayoutResolutionException(UiLayoutResolutionException.Code.LAYOUT_SOURCE_UNAVAILABLE, message, null);
  }
}
