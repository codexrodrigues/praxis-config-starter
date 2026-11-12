package org.praxisplatform.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestResponse;
import org.praxisplatform.config.dto.DomainRulePendingRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyMutationResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutReadinessResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.runtime.ProductBaselineGenesisInitializer.GenesisReport;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.facts.RuleFactContract;
import org.praxisplatform.rules.facts.RuleFactSchema;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder.Rule;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;

@Tag("unit")
class ProductBaselineGenesisInitializerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");

    enum GenesisOp {
        CREATE("CREATE");
        final String key;

        GenesisOp(String key) {
            this.key = key;
        }
    }

    @Test
    void initializesVirginScopeThroughFullGovernedPipelineAndNeverTouchesWithdrawnOrExistingScopes()
            throws Exception {
        GovernedRuleFamilyDescriptor<GenesisOp> family = buildFamily();
        GovernedRuleFamilyRegistry registry = new GovernedRuleFamilyRegistry(List.of(family));

        DomainRuleSnapshotHeadRepository headRepo = mock(DomainRuleSnapshotHeadRepository.class);
        DomainRuleSnapshotRepository snapRepo = mock(DomainRuleSnapshotRepository.class);
        DomainRuleSnapshotService snapshotService = mock(DomainRuleSnapshotService.class);
        DomainRuleRolloutPolicyService policyService = mock(DomainRuleRolloutPolicyService.class);
        DomainRuleRolloutService rolloutService = mock(DomainRuleRolloutService.class);
        Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));

        ProductBaselineGenesisInitializer initializer =
                new ProductBaselineGenesisInitializer(
                        registry,
                        headRepo,
                        snapRepo,
                        snapshotService,
                        policyService,
                        rolloutService,
                        DomainRuleImplementationCatalog.denyAll(),
                        JSON,
                        clock,
                        List.of("1"),
                        "local",
                        "2020-01-01T00:00:00Z");

        // Case 1: Withdrawn head -> MUST skip without publishing or reactivating
        DomainRuleSnapshotHead withdrawnHead =
                DomainRuleSnapshotHead.builder()
                        .id(UUID.randomUUID())
                        .tenantId("1")
                        .environment("local")
                        .ruleSetKey("corp.leaves")
                        .operationKey("CREATE")
                        .withdrawn(true)
                        .build();
        when(headRepo.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(
                        "1", "local", "corp.leaves", "CREATE"))
                .thenReturn(Optional.of(withdrawnHead));

        GenesisReport skippedWithdrawn = initializer.initializeAll();
        assertThat(skippedWithdrawn.initializedOperations()).isZero();
        assertThat(skippedWithdrawn.skippedExistingOrWithdrawnOperations()).isEqualTo(1);
        verify(snapshotService, never()).publish(any(), any(), any(), any(), any(), any());
        verify(snapshotService, never())
                .activatePublished(any(), any(), any(), any(), any(), any(), any());

        // Case 2: No head, but prior snapshot history exists -> MUST skip
        when(headRepo.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(
                        "1", "local", "corp.leaves", "CREATE"))
                .thenReturn(Optional.empty());
        when(snapRepo.findMaximumPublicationRevision("1", "local", "corp.leaves", "CREATE"))
                .thenReturn(1);

        GenesisReport skippedHistory = initializer.initializeAll();
        assertThat(skippedHistory.initializedOperations()).isZero();
        assertThat(skippedHistory.skippedExistingOrWithdrawnOperations()).isEqualTo(1);
        verify(snapshotService, never()).publish(any(), any(), any(), any(), any(), any());

        // Case 3: Strictly virgin scope -> executes full governed pipeline
        when(snapRepo.findMaximumPublicationRevision("1", "local", "corp.leaves", "CREATE"))
                .thenReturn(null);

        UUID policyId = UUID.randomUUID();
        UUID policyHeadEtag = UUID.randomUUID();
        when(policyService.catalog(eq("corp.leaves"), any(), eq(true), eq(true), eq(true)))
                .thenReturn(
                        new DomainRuleRolloutPolicyCatalogResponse(
                                "corp.leaves", 0L, null, null, List.of(), List.of()));
        DomainRuleRolloutPolicyResponse draftPolicy =
                new DomainRuleRolloutPolicyResponse(
                        policyId,
                        "corp.leaves",
                        ProductBaselineGenesisInitializer.GENESIS_POLICY_KEY,
                        1,
                        "DRAFT",
                        "REQUIRED",
                        1,
                        BigDecimal.ONE,
                        true,
                        300L,
                        3600L,
                        ProductBaselineGenesisInitializer.ACTOR_POLICY_AUTHOR,
                        NOW,
                        null,
                        null,
                        null,
                        null,
                        List.of());
        when(policyService.create(any(), any()))
                .thenReturn(
                        new DomainRuleRolloutPolicyMutationResponse(
                                draftPolicy, 0L, policyHeadEtag.toString()));
        when(policyService.approve(eq(policyId), any()))
                .thenReturn(
                        new DomainRuleRolloutPolicyMutationResponse(
                                draftPolicy, 0L, policyHeadEtag.toString()));

        when(snapshotService.prepareCompositionManifest(any(), eq("1"), eq("local")))
                .thenReturn(
                        new DomainRuleCompositionManifestResponse(
                                "praxis.domain-rule-composition.v1",
                                "A".repeat(64),
                                "B".repeat(64),
                                JSON.createObjectNode()));

        RuleSetDefinition canonicalDef = family.productProfiles().get(0).ruleSet();
        PublishedRuleSnapshot publishedSnapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-genesis-v1",
                        "1",
                        "local",
                        "hr-service",
                        1,
                        "2026-11-10T15:00:00Z",
                        null,
                        "host/1.0",
                        "2020-01-01T00:00:00Z",
                        null,
                        List.of(new RuleSnapshotSource("src-g1", "corp.leaves", 1, "C".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "a1",
                                        "RULE_COMPOSITION_APPROVER",
                                        ProductBaselineGenesisInitializer.ACTOR_COMP_APPROVER_1,
                                        "2026-11-10T15:00:00Z",
                                        "C".repeat(64)),
                                new RuleSnapshotApproval(
                                        "a2",
                                        "RULE_COMPOSITION_APPROVER",
                                        ProductBaselineGenesisInitializer.ACTOR_COMP_APPROVER_2,
                                        "2026-11-10T15:00:00Z",
                                        "C".repeat(64))),
                        canonicalDef);
        String contentHash = PraxisCanonicalJson.sha256(JSON.valueToTree(publishedSnapshot));
        when(snapshotService.publish(
                        any(),
                        eq("1"),
                        eq("local"),
                        eq(null),
                        eq("*"),
                        eq(ProductBaselineGenesisInitializer.ACTOR_PUBLISHER)))
                .thenReturn(new DomainRuleSnapshotStoredResponse(publishedSnapshot, contentHash));
        when(snapshotService.findSnapshot("1", "local", "snap-genesis-v1"))
                .thenReturn(
                        Optional.of(
                                new DomainRuleSnapshotStoredResponse(
                                        publishedSnapshot, contentHash)));

        UUID rolloutId = UUID.randomUUID();
        when(rolloutService.create(any(), any(), eq(null), eq("*")))
                .thenReturn(
                        new DomainRuleRolloutResponse(
                                rolloutId,
                                "corp.leaves",
                                "CREATE",
                                "snap-genesis-v1",
                                contentHash,
                                null,
                                null,
                                ProductBaselineGenesisInitializer.GENESIS_POLICY_KEY,
                                1,
                                "REQUIRED",
                                "PREPARING",
                                NOW,
                                NOW.plusSeconds(3600)));
        when(rolloutService.pending(eq("corp.leaves"), eq("CREATE"), any()))
                .thenReturn(
                        Optional.of(
                                new DomainRulePendingRolloutResponse(
                                        rolloutId,
                                        "corp.leaves",
                                        "CREATE",
                                        "snap-genesis-v1",
                                        contentHash,
                                        "PREPARING",
                                        NOW.plusSeconds(3600))));
        when(rolloutService.probe(eq(rolloutId), any(), any()))
                .thenReturn(new DomainRuleCandidateProbeResponse(true, NOW));
        when(rolloutService.readiness(eq(rolloutId), any()))
                .thenReturn(
                        new DomainRuleRolloutReadinessResponse(
                                rolloutId,
                                "corp.leaves",
                                "snap-genesis-v1",
                                "READY",
                                "REQUIRED",
                                1,
                                1.0,
                                1,
                                1,
                                0,
                                0,
                                0,
                                true,
                                NOW.minusSeconds(300)));

        GenesisReport initialized = initializer.initializeAll();
        assertThat(initialized.initializedOperations()).isEqualTo(1);
        assertThat(initialized.skippedExistingOrWithdrawnOperations()).isZero();

        verify(policyService)
                .activate(eq(policyId), eq('"' + policyHeadEtag.toString() + '"'), any());
        verify(snapshotService)
                .approveComposition(
                        any(),
                        eq("1"),
                        eq("local"),
                        eq(ProductBaselineGenesisInitializer.ACTOR_COMP_APPROVER_1));
        verify(snapshotService)
                .approveComposition(
                        any(),
                        eq("1"),
                        eq("local"),
                        eq(ProductBaselineGenesisInitializer.ACTOR_COMP_APPROVER_2));
        verify(snapshotService)
                .activatePublished(
                        eq("snap-genesis-v1"),
                        eq(ProductBaselineGenesisInitializer.ACTOR_OPERATOR),
                        eq("1"),
                        eq("local"),
                        eq(null),
                        eq("*"),
                        eq(rolloutId));
    }

    private static GovernedRuleFamilyDescriptor<GenesisOp> buildFamily() throws Exception {
        Rule r1 =
                new Rule(
                        "LEAVE-001",
                        "days",
                        "Dias obrigatórios.",
                        "LEAVE_POLICY",
                        "days > 0",
                        "{\">\":[{\"var\":\"draft.days\"},0]}");
        RuleSetDefinition productRuleSet =
                OntologyRuleSetBuilder.requireReferencedFactPaths(
                        OntologyRuleSetBuilder.definition(
                                new RuleSetRef("hr-service", "corp.leaves", "corp.leaves", "CREATE", 1),
                                "corp.leaves.create",
                                "customer.",
                                List.of(r1),
                                null,
                                false,
                                Map.of()));
        String schemaJson =
                """
                {
                  "type": "object",
                  "required": ["draft", "context"],
                  "additionalProperties": false,
                  "maxProperties": 2,
                  "properties": {
                    "draft": {
                      "type": "object",
                      "required": ["days"],
                      "additionalProperties": false,
                      "maxProperties": 1,
                      "properties": {
                        "days": { "type": "integer" }
                      }
                    },
                    "context": {
                      "type": "object",
                      "required": ["tenantId"],
                      "additionalProperties": false,
                      "maxProperties": 1,
                      "properties": {
                        "tenantId": { "type": "string", "maxLength": 64 }
                      }
                    }
                  }
                }
                """;
        RuleFactContract contract =
                new RuleFactContract(
                        "host/1.0",
                        "CREATE",
                        "provider/1.0",
                        RuleFactSchema.compile(JSON.readTree(schemaJson)),
                        List.of());
        GovernedHostRuleBundleRuntime<GenesisOp> runtime =
                new GovernedHostRuleBundleRuntime<>(
                        GenesisOp.class,
                        "hr-service",
                        "corp.leaves",
                        "host/1.0",
                        "provider/1.0",
                        List.of(new OperationSpec<>(GenesisOp.CREATE, "CREATE", productRuleSet, contract)),
                        requested -> Optional.empty(),
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                        ZoneId.of("UTC"),
                        RuleBindingExecutorRegistry.empty(),
                        OntologyRuleSetBuilder.catalogOf(List.of(r1)));
        return new GovernedRuleFamilyDescriptor<>(runtime);
    }
}
