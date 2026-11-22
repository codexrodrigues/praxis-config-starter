package org.praxisplatform.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHead;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadActivationType;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSet;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetScope;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.TenantEnvironmentScope;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.SingleRuleOperation;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleDecision;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class GovernedHostRuleBundleRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    enum TestOperation {
        CREATE("test.carreiras:create");

        private final String key;

        TestOperation(String key) {
            this.key = key;
        }
    }

    @AfterEach
    void cleanupTx() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void admitsBundleEvaluatesAndEnforcesCommitFenceOnPolicyRotation() throws Exception {
        Rule r1 = new Rule(
                "WF-09001",
                "codigo",
                "Código obrigatório.",
                "PCK_TEST",
                "IF :NEW.CODIGO IS NULL",
                "{\"!=\":[{\"var\":\"draft.codigo\"},null]}");
        RuleSetDefinition productRuleSet = OntologyRuleSetBuilder.requireReferencedFactPaths(
                OntologyRuleSetBuilder.definition(
                        new RuleSetRef("test-service", "rh", "test.carreiras", TestOperation.CREATE.key, 1),
                        "carreiras.create",
                        "customer.",
                        List.of(r1),
                        List.of(),
                        false,
                        Map.of()));

        String schemaJson = """
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
                      "required": ["tenantId", "organizationId"],
                      "additionalProperties": false,
                      "maxProperties": 2,
                      "properties": {
                        "tenantId": { "type": "string", "maxLength": 64 },
                        "organizationId": { "type": "string", "maxLength": 64 }
                      }
                    }
                  }
                }
                """;
        RuleFactContract contract = new RuleFactContract(
                "host/1.0",
                TestOperation.CREATE.key,
                "provider/1.0",
                RuleFactSchema.compile(JSON.readTree(schemaJson)),
                List.of());

        PublishedRuleSnapshot snapshot = new PublishedRuleSnapshot(
                PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                "snap-1",
                "1",
                "dev",
                "test-service",
                1,
                "2026-01-01T00:00:00Z",
                null,
                "host/1.0",
                "2026-01-01T00:00:00Z",
                null,
                List.of(new RuleSnapshotSource("src-1", "test.carreiras", 1, "A".repeat(64))),
                List.of(new RuleSnapshotApproval("app-1", "APPROVER", "admin", "2026-01-01T00:00:00Z", "A".repeat(64))),
                productRuleSet);
        String contentHash = PraxisCanonicalJson.sha256(JSON.valueToTree(snapshot));

        PublishedRuleSnapshotHead headV1 = new PublishedRuleSnapshotHead(
                snapshot,
                contentHash,
                "\"etag-1\"",
                1L,
                PublishedRuleSnapshotHeadActivationType.ACTIVE);
        PublishedRuleSnapshotHead headV2 = new PublishedRuleSnapshotHead(
                snapshot,
                contentHash,
                "\"etag-2\"",
                2L,
                PublishedRuleSnapshotHeadActivationType.ACTIVE);

        PublishedRuleSnapshotHeadSetScope requestedScope = new PublishedRuleSnapshotHeadSetScope(
                "1", "dev", "test.carreiras", List.of(TestOperation.CREATE.key));
        AtomicReference<PublishedRuleSnapshotHeadSet> activeSet = new AtomicReference<>(
                new PublishedRuleSnapshotHeadSet(requestedScope, Map.of(TestOperation.CREATE.key, headV1)));

        GovernedHostRuleBundleRuntime<TestOperation> runtime = new GovernedHostRuleBundleRuntime<>(
                TestOperation.class,
                "test-service",
                "test.carreiras",
                "host/1.0",
                "provider/1.0",
                List.of(new OperationSpec<>(TestOperation.CREATE, TestOperation.CREATE.key, productRuleSet, contract)),
                scope -> Optional.of(activeSet.get()),
                Clock.fixed(NOW, ZoneId.of("UTC")),
                ZoneId.of("UTC"),
                RuleBindingExecutorRegistry.empty(),
                OntologyRuleSetBuilder.catalogOf(List.of(r1)),
                (plan, eval) -> new IllegalStateException("Denied: " + eval.result().reasonCodes()));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        var admissionScope = runtime.require(new TenantEnvironmentScope("1", "dev"), NOW, true);
        ObjectNode validFacts = JSON.createObjectNode();
        validFacts.putObject("draft").put("codigo", "CAR-01");
        validFacts.putObject("context").put("tenantId", "1").put("organizationId", "org-a");

        var admission = admissionScope.runtime().enforceAdmission(
                TestOperation.CREATE,
                validFacts,
                admissionScope.nowUtc(),
                admissionScope.zone(),
                java.util.Set.of("org-a", "org-b"));
        assertThat(admission.evaluation().result().decision()).isEqualTo(RuleDecision.ALLOW);
        assertThat(admission.policyBundleDigest()).isNotBlank();

        // Simulate head rotation before transaction commit -> CommitFence must abort!
        activeSet.set(new PublishedRuleSnapshotHeadSet(requestedScope, Map.of(TestOperation.CREATE.key, headV2)));
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        assertThatThrownBy(() -> syncs.getFirst().beforeCommit(false))
                .isInstanceOf(ResponseStatusException.class);

        // Reject read-only transactions when requireSyncTransaction=true so CommitFence cannot be bypassed
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> runtime.require(new TenantEnvironmentScope("1", "dev"), NOW, true))
                .isInstanceOf(ResponseStatusException.class);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        // Reject context tenantId mismatch against authorized tenant scope
        ObjectNode mismatchedTenantFacts = JSON.createObjectNode();
        mismatchedTenantFacts.putObject("draft").put("codigo", "CAR-01");
        mismatchedTenantFacts.putObject("context").put("tenantId", "2").put("organizationId", "org-a");
        assertThatThrownBy(() -> admissionScope.runtime().enforceAdmission(
                TestOperation.CREATE, mismatchedTenantFacts, admissionScope.nowUtc(), admissionScope.zone()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");

        // Reject context organizationId outside authorized intra-tenant organizations
        ObjectNode mismatchedOrgFacts = JSON.createObjectNode();
        mismatchedOrgFacts.putObject("draft").put("codigo", "CAR-01");
        mismatchedOrgFacts.putObject("context").put("tenantId", "1").put("organizationId", "org-unauthorized");
        assertThatThrownBy(() -> admissionScope.runtime().enforceAdmission(
                TestOperation.CREATE,
                mismatchedOrgFacts,
                admissionScope.nowUtc(),
                admissionScope.zone(),
                java.util.Set.of("org-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void admitsAndEvaluatesUntypedSingleRuleSetFamilyAtLiveRuntimeWithCommitFence() {
        Rule r1 = new Rule(
                "WF-09101",
                "nome",
                "Nome obrigatório.",
                "pkg_legacy_rules.setor",
                "IF :NEW.NOME IS NULL",
                "{\"!=\":[{\"var\":\"draft.nome\"},null]}");
        RuleSetDefinition productRuleSet = OntologyRuleSetBuilder.requireReferencedFactPaths(
                OntologyRuleSetBuilder.definition(
                        new RuleSetRef("test-service", "rh", "test.setores", "test.setores:save", 1),
                        "setores.save",
                        "customer.",
                        List.of(r1),
                        List.of(),
                        false,
                        Map.of()));

        PublishedRuleSnapshot snapshot = new PublishedRuleSnapshot(
                PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                "snap-untyped-1",
                "tenant-acme",
                "prod",
                "test-service",
                1,
                "2026-01-01T00:00:00Z",
                null,
                "host/1.0",
                "2026-01-01T00:00:00Z",
                null,
                List.of(new RuleSnapshotSource("src-u1", "test.setores", 1, "B".repeat(64))),
                List.of(new RuleSnapshotApproval("app-u1", "APPROVER", "admin", "2026-01-01T00:00:00Z", "B".repeat(64))),
                productRuleSet);
        String contentHash = PraxisCanonicalJson.sha256(JSON.valueToTree(snapshot));

        PublishedRuleSnapshotHead headV1 = new PublishedRuleSnapshotHead(
                snapshot,
                contentHash,
                "\"etag-u1\"",
                1L,
                PublishedRuleSnapshotHeadActivationType.ACTIVE);
        PublishedRuleSnapshotHead headV2 = new PublishedRuleSnapshotHead(
                snapshot,
                contentHash,
                "\"etag-u2\"",
                2L,
                PublishedRuleSnapshotHeadActivationType.ACTIVE);

        PublishedRuleSnapshotHeadSetScope requestedScope = new PublishedRuleSnapshotHeadSetScope(
                "tenant-acme", "prod", "test.setores", List.of("test.setores:save"));
        AtomicReference<PublishedRuleSnapshotHeadSet> activeSet = new AtomicReference<>(
                new PublishedRuleSnapshotHeadSet(requestedScope, Map.of("test.setores:save", headV1)));

        GovernedRuleFamilyDescriptor<SingleRuleOperation> descriptor =
                GovernedRuleFamilyDescriptor.ofSingleRuleSet(
                        "test-service",
                        "host/1.0",
                        productRuleSet,
                        scope -> Optional.of(activeSet.get()),
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                        ZoneId.of("UTC"),
                        RuleBindingExecutorRegistry.empty(),
                        OntologyRuleSetBuilder.catalogOf(List.of(r1)));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        var admissionScope = descriptor.runtime().require(
                new TenantEnvironmentScope("tenant-acme", "prod"), NOW, true);
        assertThat(admissionScope.runtime().compiledSnapshot(SingleRuleOperation.DEFAULT).snapshotContentHash())
                .isEqualTo(contentHash);

        ObjectNode validFacts = JSON.createObjectNode();
        validFacts.putObject("draft").put("nome", "Diretoria Executiva");
        validFacts
                .putObject("context")
                .put("tenantId", "tenant-acme")
                .put("referenceDate", "2020-01-01");

        var admission = admissionScope.runtime().enforceAdmission(
                SingleRuleOperation.DEFAULT, validFacts, admissionScope.nowUtc(), admissionScope.zone());
        assertThat(admission.evaluation().result().decision()).isEqualTo(RuleDecision.ALLOW);
        assertThat(admission.policyBundleDigest()).hasSize(64);

        // Violation raises canonical PraxisRuleViolationException with 422 provenance
        ObjectNode invalidFacts = JSON.createObjectNode();
        invalidFacts.putObject("draft").putNull("nome");
        invalidFacts.putObject("context").put("tenantId", "tenant-acme");
        assertThatThrownBy(() -> admissionScope.runtime().enforceAdmission(
                SingleRuleOperation.DEFAULT, invalidFacts, admissionScope.nowUtc(), admissionScope.zone()))
                .isInstanceOf(org.praxisplatform.rules.violation.PraxisRuleViolationException.class)
                .hasMessageContaining("Nome obrigatório.");

        // Custom host violationExceptionFactory is honored by ofSingleRuleSet overload
        GovernedRuleFamilyDescriptor<SingleRuleOperation> customExceptionDescriptor =
                GovernedRuleFamilyDescriptor.ofSingleRuleSet(
                        "test-service",
                        "host/1.0",
                        productRuleSet,
                        scope -> Optional.of(activeSet.get()),
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                        ZoneId.of("UTC"),
                        RuleBindingExecutorRegistry.empty(),
                        OntologyRuleSetBuilder.catalogOf(List.of(r1)),
                        (plan, eval) -> new IllegalStateException("HOST-VIOLATION:" + eval.result().reasonCodes()));
        var customScope = customExceptionDescriptor.runtime().require(
                new TenantEnvironmentScope("tenant-acme", "prod"), NOW, true);
        assertThatThrownBy(() -> customScope.runtime().enforce(
                SingleRuleOperation.DEFAULT, invalidFacts, customScope.nowUtc(), customScope.zone()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOST-VIOLATION:[WF-09101]");

        // Tenant mismatch in context fails closed even for untyped RuleSets
        ObjectNode wrongTenantFacts = JSON.createObjectNode();
        wrongTenantFacts.putObject("draft").put("nome", "Diretoria Executiva");
        wrongTenantFacts.putObject("context").put("tenantId", "tenant-other");
        assertThatThrownBy(() -> admissionScope.runtime().enforceAdmission(
                SingleRuleOperation.DEFAULT, wrongTenantFacts, admissionScope.nowUtc(), admissionScope.zone()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");

        // CommitFence aborts transaction if head rotates before commit
        activeSet.set(new PublishedRuleSnapshotHeadSet(requestedScope, Map.of("test.setores:save", headV2)));
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(2);
        assertThatThrownBy(() -> syncs.getFirst().beforeCommit(false))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> syncs.get(1).beforeCommit(false))
                .isInstanceOf(ResponseStatusException.class);
    }
}
