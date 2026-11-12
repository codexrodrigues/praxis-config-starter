package org.praxisplatform.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeRequest;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeResponse;
import org.praxisplatform.config.dto.DomainRulePendingRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleImplementationScope;
import org.praxisplatform.config.service.DomainRuleProductCatalog;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class GovernedRuleFamilyRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");

    enum CarreiraOp {
        CREATE("ergon.carreiras:create");
        final String key;

        CarreiraOp(String key) {
            this.key = key;
        }
    }

    enum VinculoOp {
        CREATE("ergon.vinculos:create");
        final String key;

        VinculoOp(String key) {
            this.key = key;
        }
    }

    @Test
    void indexesFamiliesBuildsProductCatalogAndProbesCandidateWithFailClosedHttpSemantics()
            throws Exception {
        GovernedRuleFamilyDescriptor<CarreiraOp> carreiraFamily =
                buildFamily(
                        CarreiraOp.class,
                        CarreiraOp.CREATE,
                        "ergon.carreiras",
                        CarreiraOp.CREATE.key,
                        "ERG-09001");
        GovernedRuleFamilyDescriptor<VinculoOp> vinculoFamily =
                buildFamily(
                        VinculoOp.class,
                        VinculoOp.CREATE,
                        "ergon.vinculos",
                        VinculoOp.CREATE.key,
                        "ERG-09002");

        GovernedRuleFamilyRegistry registry =
                new GovernedRuleFamilyRegistry(List.of(carreiraFamily, vinculoFamily));

        assertThat(registry.families()).hasSize(2);
        assertThat(registry.findByFamilyKey("ergon.carreiras")).contains(carreiraFamily);
        assertThat(registry.findByOperationKey("ergon.vinculos:create")).contains(vinculoFamily);

        DomainRuleProductCatalog productCatalog = registry.toProductCatalog();
        assertThat(
                        productCatalog.find(
                                new DomainRuleImplementationScope("1", "dev", "ergon-service"),
                                new RuleSetRef(
                                        "ergon-service",
                                        "rh",
                                        "ergon.carreiras",
                                        CarreiraOp.CREATE.key,
                                        1)))
                .isPresent();

        // Duplicate familyKey rejection
        assertThatThrownBy(
                        () ->
                                new GovernedRuleFamilyRegistry(
                                        List.of(carreiraFamily, carreiraFamily)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate GovernedRuleFamilyDescriptor");

        UUID rolloutId = UUID.randomUUID();
        DomainRuleGovernancePrincipal principal =
                new DomainRuleGovernancePrincipal("1", "preload-worker", "dev");
        DomainRuleRolloutService rollouts = mock(DomainRuleRolloutService.class);
        DomainRuleSnapshotService snapshots = mock(DomainRuleSnapshotService.class);
        Clock fixedClock = Clock.fixed(NOW, ZoneId.of("UTC"));

        // 400 BAD_REQUEST when organization <= 0
        assertThatThrownBy(
                        () ->
                                registry.probeCandidate(
                                        0,
                                        VinculoOp.CREATE.key,
                                        rolloutId,
                                        principal,
                                        snapshots,
                                        rollouts,
                                        DomainRuleImplementationCatalog.denyAll(),
                                        JSON,
                                        fixedClock))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        // 403 FORBIDDEN when tenantId mismatches principal
        assertThatThrownBy(
                        () ->
                                registry.probeCandidate(
                                        2,
                                        VinculoOp.CREATE.key,
                                        rolloutId,
                                        principal,
                                        snapshots,
                                        rollouts,
                                        DomainRuleImplementationCatalog.denyAll(),
                                        JSON,
                                        fixedClock))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // 404 NOT_FOUND when operationKey is unknown
        assertThatThrownBy(
                        () ->
                                registry.probeCandidate(
                                        1,
                                        "ergon.inexistente:create",
                                        rolloutId,
                                        principal,
                                        snapshots,
                                        rollouts,
                                        DomainRuleImplementationCatalog.denyAll(),
                                        JSON,
                                        fixedClock))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // 404 NOT_FOUND when no pending rollout exists
        when(rollouts.pending("ergon.vinculos", VinculoOp.CREATE.key, principal))
                .thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                registry.probeCandidate(
                                        1,
                                        VinculoOp.CREATE.key,
                                        rolloutId,
                                        principal,
                                        snapshots,
                                        rollouts,
                                        DomainRuleImplementationCatalog.denyAll(),
                                        JSON,
                                        fixedClock))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // Valid candidate probe
        RuleSetDefinition productRuleSet =
                vinculoFamily.operationSpecs().get(VinculoOp.CREATE).productRuleSet();
        PublishedRuleSnapshot snapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-vinculo-1",
                        "1",
                        "dev",
                        "ergon-service",
                        1,
                        "2026-01-01T00:00:00Z",
                        null,
                        "host/1.0",
                        "2026-01-01T00:00:00Z",
                        null,
                        List.of(
                                new RuleSnapshotSource(
                                        "src-1", "ergon.vinculos", 1, "B".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "app-1",
                                        "RULE_COMPOSITION_APPROVER",
                                        "admin",
                                        "2026-01-01T00:00:00Z",
                                        "B".repeat(64))),
                        productRuleSet);
        String contentHash = PraxisCanonicalJson.sha256(JSON.valueToTree(snapshot));

        when(rollouts.pending("ergon.vinculos", VinculoOp.CREATE.key, principal))
                .thenReturn(
                        Optional.of(
                                new DomainRulePendingRolloutResponse(
                                        rolloutId,
                                        "ergon.vinculos",
                                        VinculoOp.CREATE.key,
                                        "snap-vinculo-1",
                                        contentHash,
                                        "PREPARING",
                                        NOW.plusSeconds(300))));
        when(snapshots.findSnapshot("1", "dev", "snap-vinculo-1"))
                .thenReturn(
                        Optional.of(new DomainRuleSnapshotStoredResponse(snapshot, contentHash)));
        when(rollouts.probe(eq(rolloutId), any(), eq(principal)))
                .thenReturn(new DomainRuleCandidateProbeResponse(true, NOW));

        // 409 CONFLICT when rolloutId mismatches pending rollout
        assertThatThrownBy(
                        () ->
                                registry.probeCandidate(
                                        1,
                                        VinculoOp.CREATE.key,
                                        UUID.randomUUID(),
                                        principal,
                                        snapshots,
                                        rollouts,
                                        DomainRuleImplementationCatalog.denyAll(),
                                        JSON,
                                        fixedClock))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        DomainRuleCandidateProbeResponse response =
                registry.probeCandidate(
                        1,
                        VinculoOp.CREATE.key,
                        rolloutId,
                        principal,
                        snapshots,
                        rollouts,
                        DomainRuleImplementationCatalog.denyAll(),
                        JSON,
                        fixedClock);

        assertThat(response.updated()).isTrue();
        ArgumentCaptor<DomainRuleCandidateProbeRequest> captor =
                ArgumentCaptor.forClass(DomainRuleCandidateProbeRequest.class);
        verify(rollouts).probe(eq(rolloutId), captor.capture(), eq(principal));
        assertThat(captor.getValue().preloadReady()).isTrue();
        assertThat(captor.getValue().failureCode()).isNull();
        assertThat(captor.getValue().candidateSnapshotKey()).isEqualTo("snap-vinculo-1");

        // Incompatible candidate (undeclared fact root) reports preloadReady=false and canonical RUNTIME_INCOMPATIBLE
        Rule badRule =
                new Rule(
                        "ERG-99999",
                        "codigo",
                        "Raiz não permitida.",
                        "PCK_TEST",
                        "IF :NEW.X IS NULL",
                        "{\"!=\":[{\"var\":\"undeclaredRoot.secret\"},null]}");
        RuleSetDefinition badRuleSet =
                OntologyRuleSetBuilder.requireReferencedFactPaths(
                        OntologyRuleSetBuilder.definition(
                                new RuleSetRef(
                                        "ergon-service",
                                        "rh",
                                        "ergon.vinculos",
                                        VinculoOp.CREATE.key,
                                        1),
                                "ergon.vinculos.create",
                                "customer.",
                                List.of(badRule),
                                null,
                                false,
                                Map.of()));
        PublishedRuleSnapshot badSnapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-vinculo-bad",
                        "1",
                        "dev",
                        "ergon-service",
                        2,
                        "2026-01-01T00:00:00Z",
                        null,
                        "host/1.0",
                        "2026-01-01T00:00:00Z",
                        null,
                        List.of(
                                new RuleSnapshotSource(
                                        "src-2", "ergon.vinculos", 2, "C".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "app-2",
                                        "RULE_COMPOSITION_APPROVER",
                                        "admin",
                                        "2026-01-01T00:00:00Z",
                                        "C".repeat(64))),
                        badRuleSet);
        String badHash = PraxisCanonicalJson.sha256(JSON.valueToTree(badSnapshot));
        UUID badRolloutId = UUID.randomUUID();
        when(rollouts.pending("ergon.vinculos", VinculoOp.CREATE.key, principal))
                .thenReturn(
                        Optional.of(
                                new DomainRulePendingRolloutResponse(
                                        badRolloutId,
                                        "ergon.vinculos",
                                        VinculoOp.CREATE.key,
                                        "snap-vinculo-bad",
                                        badHash,
                                        "PREPARING",
                                        NOW.plusSeconds(300))));
        when(snapshots.findSnapshot("1", "dev", "snap-vinculo-bad"))
                .thenReturn(
                        Optional.of(new DomainRuleSnapshotStoredResponse(badSnapshot, badHash)));
        when(rollouts.probe(eq(badRolloutId), any(), eq(principal)))
                .thenReturn(new DomainRuleCandidateProbeResponse(true, NOW));

        registry.probeCandidate(
                1,
                VinculoOp.CREATE.key,
                badRolloutId,
                principal,
                snapshots,
                rollouts,
                DomainRuleImplementationCatalog.denyAll(),
                JSON,
                fixedClock);

        ArgumentCaptor<DomainRuleCandidateProbeRequest> badCaptor =
                ArgumentCaptor.forClass(DomainRuleCandidateProbeRequest.class);
        verify(rollouts).probe(eq(badRolloutId), badCaptor.capture(), eq(principal));
        assertThat(badCaptor.getValue().preloadReady()).isFalse();
        assertThat(badCaptor.getValue().failureCode())
                .isEqualTo(GovernedRuleFamilyRegistry.FAILURE_CODE_RUNTIME_INCOMPATIBLE);
    }

    enum SharedValidateOp {
        VALIDATE("validate");
        final String key;

        SharedValidateOp(String key) {
            this.key = key;
        }
    }

    @Test
    void supportsMultipleFamiliesSharingGenericOperationKeyAndValidatesTenantScopeResolver()
            throws Exception {
        GovernedRuleFamilyDescriptor<SharedValidateOp> lotacaoFamily =
                buildFamily(
                        SharedValidateOp.class,
                        SharedValidateOp.VALIDATE,
                        "ergon.lotacao",
                        "validate",
                        "ERG-09101");
        GovernedRuleFamilyDescriptor<SharedValidateOp> feriasFamily =
                buildFamily(
                        SharedValidateOp.class,
                        SharedValidateOp.VALIDATE,
                        "ergon.ferias",
                        "validate",
                        "ERG-09102");

        GovernedRuleFamilyRegistry registry =
                new GovernedRuleFamilyRegistry(List.of(lotacaoFamily, feriasFamily));

        assertThat(registry.findAllByOperationKey("validate"))
                .containsExactly(lotacaoFamily, feriasFamily);
        assertThat(registry.findByFamilyAndOperation("ergon.ferias", "validate"))
                .contains(feriasFamily);
        assertThatThrownBy(() -> registry.findByOperationKey("validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared by multiple governed rule families");

        UUID feriasRolloutId = UUID.randomUUID();
        DomainRuleGovernancePrincipal principal =
                new DomainRuleGovernancePrincipal("1", "preload-worker", "dev");
        DomainRuleRolloutService rollouts = mock(DomainRuleRolloutService.class);
        DomainRuleSnapshotService snapshots = mock(DomainRuleSnapshotService.class);
        Clock fixedClock = Clock.fixed(NOW, ZoneId.of("UTC"));

        RuleSetDefinition feriasProduct =
                feriasFamily.operationSpecs().get(SharedValidateOp.VALIDATE).productRuleSet();
        PublishedRuleSnapshot feriasSnapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-ferias-1",
                        "1",
                        "dev",
                        "ergon-service",
                        1,
                        "2026-01-01T00:00:00Z",
                        null,
                        "host/1.0",
                        "2026-01-01T00:00:00Z",
                        null,
                        List.of(new RuleSnapshotSource("src-f1", "ergon.ferias", 1, "D".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "app-f1",
                                        "RULE_COMPOSITION_APPROVER",
                                        "admin",
                                        "2026-01-01T00:00:00Z",
                                        "D".repeat(64))),
                        feriasProduct);
        String feriasHash = PraxisCanonicalJson.sha256(JSON.valueToTree(feriasSnapshot));

        when(rollouts.pending("ergon.lotacao", "validate", principal))
                .thenReturn(Optional.empty());
        when(rollouts.pending("ergon.ferias", "validate", principal))
                .thenReturn(
                        Optional.of(
                                new DomainRulePendingRolloutResponse(
                                        feriasRolloutId,
                                        "ergon.ferias",
                                        "validate",
                                        "snap-ferias-1",
                                        feriasHash,
                                        "PREPARING",
                                        NOW.plusSeconds(300))));
        when(snapshots.findSnapshot("1", "dev", "snap-ferias-1"))
                .thenReturn(
                        Optional.of(
                                new DomainRuleSnapshotStoredResponse(feriasSnapshot, feriasHash)));
        when(rollouts.probe(eq(feriasRolloutId), any(), eq(principal)))
                .thenReturn(new DomainRuleCandidateProbeResponse(true, NOW));

        DomainRuleCandidateProbeResponse probeRes =
                registry.probeCandidate(
                        1,
                        "validate",
                        feriasRolloutId,
                        principal,
                        snapshots,
                        rollouts,
                        DomainRuleImplementationCatalog.denyAll(),
                        JSON,
                        fixedClock);
        assertThat(probeRes.updated()).isTrue();

        // TenantScopeResolver checks (2-level tenant vs intra-tenant organization)
        org.springframework.mock.web.MockHttpServletRequest conflictReq =
                new org.springframework.mock.web.MockHttpServletRequest();
        conflictReq.addHeader("X-Tenant-ID", "rio");
        conflictReq.addHeader("X-Tenant", "df");
        assertThatThrownBy(() -> TenantScopeResolver.resolveHeaderTenant(conflictReq))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        org.springframework.mock.web.MockHttpServletRequest multiOrgReq =
                new org.springframework.mock.web.MockHttpServletRequest();
        multiOrgReq.addHeader("X-Tenant-ID", "rio");
        multiOrgReq.addHeader("X-Organization-ID", "2");
        assertThat(TenantScopeResolver.resolveHeaderTenant(multiOrgReq)).contains("rio");
        assertThat(TenantScopeResolver.resolveHeaderOrganization(multiOrgReq)).contains("2");

        com.fasterxml.jackson.databind.node.ObjectNode badCtx =
                JSON.createObjectNode().put("tenantId", "df");
        assertThatThrownBy(() -> TenantScopeResolver.verifyFactsContextScope(badCtx, "rio"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");

        com.fasterxml.jackson.databind.node.ObjectNode badOrgCtx =
                JSON.createObjectNode().put("tenantId", "rio").put("organizationId", "9");
        assertThatThrownBy(
                        () ->
                                TenantScopeResolver.verifyFactsContextScope(
                                        badOrgCtx, "rio", java.util.Set.of("1", "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId");
    }

    private static <O extends Enum<O>> GovernedRuleFamilyDescriptor<O> buildFamily(
            Class<O> opClass, O op, String ruleSetKey, String opKey, String errorCode)
            throws Exception {
        Rule r1 =
                new Rule(
                        errorCode,
                        "codigo",
                        "Código obrigatório.",
                        "PCK_TEST",
                        "IF :NEW.CODIGO IS NULL",
                        "{\"!=\":[{\"var\":\"draft.codigo\"},null]}");
        RuleSetDefinition productRuleSet =
                OntologyRuleSetBuilder.requireReferencedFactPaths(
                        OntologyRuleSetBuilder.definition(
                                new RuleSetRef("ergon-service", "rh", ruleSetKey, opKey, 1),
                                ruleSetKey + ".create",
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
                      "required": ["codigo"],
                      "additionalProperties": false,
                      "maxProperties": 1,
                      "properties": {
                        "codigo": { "type": ["string", "null"], "maxLength": 200 }
                      }
                    },
                    "context": {
                      "type": "object",
                      "required": ["empCodigo"],
                      "additionalProperties": false,
                      "maxProperties": 1,
                      "properties": {
                        "empCodigo": { "type": "integer" }
                      }
                    }
                  }
                }
                """;
        RuleFactContract contract =
                new RuleFactContract(
                        "host/1.0",
                        opKey,
                        "provider/1.0",
                        RuleFactSchema.compile(JSON.readTree(schemaJson)),
                        List.of());
        GovernedHostRuleBundleRuntime<O> runtime =
                new GovernedHostRuleBundleRuntime<>(
                        opClass,
                        "ergon-service",
                        ruleSetKey,
                        "host/1.0",
                        "provider/1.0",
                        List.of(new OperationSpec<>(op, opKey, productRuleSet, contract)),
                        requested -> Optional.empty(),
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                        ZoneId.of("UTC"),
                        RuleBindingExecutorRegistry.empty(),
                        OntologyRuleSetBuilder.catalogOf(List.of(r1)));
        return new GovernedRuleFamilyDescriptor<>(runtime);
    }
}
