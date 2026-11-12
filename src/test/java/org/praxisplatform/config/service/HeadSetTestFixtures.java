package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Synthetic approved content only; never operational approvals, host probes or selected heads. */
final class HeadSetTestFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static DomainRuleSnapshot snapshot(String operation) throws Exception {
        var slot = new DecisionSlot("allow", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, List.of());
        var binding = new DecisionBinding("allow", "allow", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"var\":\"context.allowed\"}")), List.of(), 1, true,
                RuleDecision.DENY, "DENIED", List.of(), null);
        var definition = new RuleSetDefinition(new RuleSetRef("domain", "context", "family", operation, 1),
                List.of("context"), List.of(slot), List.of(binding), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
        var manifest = JSON.createObjectNode().put("compositionContractVersion", "praxis-rule-composition/2")
                .put("tenantId", "2").put("environment", "stage").put("ownerServiceKey", "host");
        manifest.set("ruleSet", JSON.valueToTree(definition)); manifest.putArray("testEvidence");
        var digest = PraxisCanonicalJson.sha256(manifest);
        var snapshot = new PublishedRuleSnapshot(PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION, "snapshot-"+operation,
                "2", "stage", "host", 1, "2026-11-05T00:00:00Z", null, "host/2.0", "2026-11-05T00:00:00Z", null,
                List.of(new RuleSnapshotSource("source", "definition", 1, "A".repeat(64))),
                List.of(new RuleSnapshotApproval("approval-a", "RULE_COMPOSITION_APPROVER", "alice", "2026-11-05T00:00:00Z", digest),
                        new RuleSnapshotApproval("approval-b", "RULE_COMPOSITION_APPROVER", "bob", "2026-11-05T00:00:00Z", digest)), definition);
        return DomainRuleSnapshot.builder().id(UUID.randomUUID()).tenantId("2").environment("stage")
                .snapshotKey(snapshot.snapshotKey()).ruleSetKey("family").operationKey(operation).ruleSetVersion(1).publicationRevision(1)
                .snapshotPayload(JSON.writeValueAsString(snapshot)).contentHash(PraxisCanonicalJson.sha256(JSON.valueToTree(snapshot)))
                .compositionManifest(manifest.toString()).compositionDigest(digest).publishedBy("publisher")
                .publishedAt(java.time.Instant.parse("2026-11-05T00:00:00Z")).build();
    }
}
