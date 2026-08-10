package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadActivationType;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadScope;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.SlotCardinality;

@Tag("unit")
class DomainRuleSnapshotHeadReaderAdapterTest {
  private final DomainRuleSnapshotService snapshotService = mock(DomainRuleSnapshotService.class);
  private final DomainRuleSnapshotHeadReaderAdapter adapter =
      new DomainRuleSnapshotHeadReaderAdapter(snapshotService);

  @Test
  void mapsTheExactGovernedScopeAndHeadIdentity() {
    PublishedRuleSnapshot snapshot = publishedSnapshot();
    String contentHash = "A".repeat(64);
    var response = new DomainRuleSnapshotActivationResponse(
        snapshot, contentHash, "\"head-7\"", 7, "ACTIVE");
    when(snapshotService.findActive("tenant-a", "prod", "frequency-rules"))
        .thenReturn(Optional.of(response));

    var head = adapter.findActive(new PublishedRuleSnapshotHeadScope(
        " tenant-a ", " prod ", " frequency-rules ")).orElseThrow();

    assertThat(head.snapshot()).isSameAs(snapshot);
    assertThat(head.snapshotContentHash()).isEqualTo(contentHash);
    assertThat(head.headEtag()).isEqualTo("\"head-7\"");
    assertThat(head.activationRevision()).isEqualTo(7);
    assertThat(head.activationType()).isEqualTo(PublishedRuleSnapshotHeadActivationType.ACTIVE);
  }

  @Test
  void returnsEmptyOnlyWhenTheServiceHasNoExactActiveHead() {
    when(snapshotService.findActive("tenant-a", "prod", "missing"))
        .thenReturn(Optional.empty());

    assertThat(adapter.findActive(new PublishedRuleSnapshotHeadScope(
        "tenant-a", "prod", "missing"))).isEmpty();
  }

  @Test
  void propagatesIntegrityFailuresInsteadOfInventingFallbackContent() {
    when(snapshotService.findActive("tenant-a", "prod", "invalid"))
        .thenThrow(new IllegalStateException("content hash verification failed"));

    assertThatThrownBy(() -> adapter.findActive(new PublishedRuleSnapshotHeadScope(
        "tenant-a", "prod", "invalid")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("content hash verification failed");
  }

  private PublishedRuleSnapshot publishedSnapshot() {
    var expression = new ObjectMapper().createObjectNode().put("var", "request.eligible");
    var ruleSet = new RuleSetDefinition(
        new RuleSetRef("workforce", "frequency", "frequency-rules", "validate", 1),
        List.of("request"),
        List.of(new DecisionSlot(
            "eligibility", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
            OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT)),
        List.of(new DecisionBinding(
            "eligibility", "eligibility", DecisionSource.PRODUCT, null,
            RuleExecutorRef.jsonLogic(expression), List.of(), 10, true,
            RuleDecision.DENY, "NOT_ELIGIBLE", List.of("request.eligible"))),
        RuleRuntimeCompatibility.current(),
        RuleFailPolicy.FAIL_CLOSED);
    String evidenceHash = "B".repeat(64);
    return new PublishedRuleSnapshot(
        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
        "snapshot-frequency-1",
        "tenant-a",
        "prod",
        "ergon",
        1,
        "2026-08-09T20:00:00Z",
        null,
        "ergon/1.0",
        "2026-08-09T20:00:00Z",
        null,
        List.of(new RuleSnapshotSource(
            "definition-frequency", "frequency:eligibility", 1, evidenceHash)),
        List.of(new RuleSnapshotApproval(
            "approval-frequency", "RULE_DEFINITION_APPROVER", "owner-frequency",
            "2026-08-09T19:00:00Z", evidenceHash)),
        ruleSet);
  }
}
