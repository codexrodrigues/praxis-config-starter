package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleEventRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

@Tag("unit")
class OperationalPolicyServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
    private static final OperationalPolicyTarget TARGET = new OperationalPolicyTarget(
            "backend_validation", "resource-validation", "procurement.suppliers");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void failsClosedWhenTheExactSnapshotQueryRaisesDataAccessFailure() {
        DomainRuleMaterializationRepository repository = mock(DomainRuleMaterializationRepository.class);
        PlatformTransactionManager transactions = transactionManager();
        when(repository.operationalSnapshot("tenant-a", "dev", TARGET.targetLayer(), TARGET.targetArtifactType(), TARGET.targetArtifactKey()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        OperationalPolicyResolution resolution = reader(repository, transactions)
                .resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "dev"));

        assertUnavailable(resolution);
        verify(repository).operationalSnapshot("tenant-a", "dev", TARGET.targetLayer(), TARGET.targetArtifactType(), TARGET.targetArtifactKey());
        verify(repository, never()).findAll();
    }

    @Test
    void boundedResolutionFloorsToRemainingWholeSecondsAndFailsClosedBelowOneSecond() {
        DomainRuleMaterializationRepository repository = mock(DomainRuleMaterializationRepository.class);
        when(repository.operationalSnapshot(any(), any(), any(), any(), any())).thenReturn(List.of());
        PlatformTransactionManager transactions = transactionManager();
        var reader = reader(repository, transactions);

        reader.resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "dev"), Duration.ofMillis(1_999));
        var captured = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(captured.capture());
        assertThat(captured.getValue().getTimeout()).isEqualTo(1);

        clearInvocations(repository, transactions);
        OperationalPolicyResolution tooLate = reader.resolveOperationalPolicy(TARGET,
                principal("tenant-a", "reader-a", "dev"), Duration.ofMillis(999));
        assertUnavailable(tooLate);
        verify(transactions, never()).getTransaction(any());
        verify(repository, never()).operationalSnapshot(any(), any(), any(), any(), any());
    }

    @Test
    void failsClosedWhenTheReadTransactionCannotStartOrCommit() {
        DomainRuleMaterializationRepository cannotStartRepository = mock(DomainRuleMaterializationRepository.class);
        PlatformTransactionManager cannotStart = mock(PlatformTransactionManager.class);
        when(cannotStart.getTransaction(any())).thenThrow(new CannotCreateTransactionException("transaction unavailable"));

        OperationalPolicyResolution cannotStartResolution = reader(cannotStartRepository, cannotStart)
                .resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "dev"));

        assertUnavailable(cannotStartResolution);
        verify(cannotStartRepository, never()).operationalSnapshot(any(), any(), any(), any(), any());

        DomainRuleMaterializationRepository commitFailureRepository = mock(DomainRuleMaterializationRepository.class);
        PlatformTransactionManager commitFailure = transactionManager();
        when(commitFailureRepository.operationalSnapshot("tenant-a", "dev", TARGET.targetLayer(), TARGET.targetArtifactType(), TARGET.targetArtifactKey()))
                .thenReturn(List.of());
        doThrow(new TransactionSystemException("commit failed")).when(commitFailure).commit(any());

        OperationalPolicyResolution commitFailureResolution = reader(commitFailureRepository, commitFailure)
                .resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "dev"));

        assertUnavailable(commitFailureResolution);
        verify(commitFailureRepository).operationalSnapshot("tenant-a", "dev", TARGET.targetLayer(), TARGET.targetArtifactType(), TARGET.targetArtifactKey());
    }

    @Test
    void failsClosedForNullMalformedAndOversizedSnapshots() {
        assertUnavailable(resolveRows(null));
        assertUnavailable(resolveRows(Collections.singletonList(null)));
        assertUnavailable(resolveRows(List.of("{not-json")));
        assertUnavailable(resolveRows(Collections.nCopies(4097, "{}")));
    }

    @Test
    void initialAbsenceIsStableForActorButBoundToTenantEnvironmentAndExactTarget() {
        DomainRuleMaterializationRepository repository = mock(DomainRuleMaterializationRepository.class);
        when(repository.operationalSnapshot(any(), any(), any(), any(), any())).thenReturn(List.of());
        OperationalPolicyService reader = reader(repository, transactionManager());

        OperationalPolicyResolution first = reader.resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "dev"));
        OperationalPolicyResolution sameScopeDifferentActor = reader.resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-b", "dev"));
        OperationalPolicyResolution otherTenant = reader.resolveOperationalPolicy(TARGET, principal("tenant-b", "reader-a", "dev"));
        OperationalPolicyResolution otherEnvironment = reader.resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "test"));
        OperationalPolicyResolution otherTarget = reader.resolveOperationalPolicy(
                new OperationalPolicyTarget("backend_validation", "resource-validation", "procurement.orders"),
                principal("tenant-a", "reader-a", "dev"));

        assertThat(first.resolutionState()).isEqualTo(OperationalPolicyResolution.State.NEVER_APPLIED);
        assertThat(first.policy()).isNull();
        assertThat(sameScopeDifferentActor).isEqualTo(first);
        assertThat(otherTenant.resolutionFingerprint()).isNotEqualTo(first.resolutionFingerprint());
        assertThat(otherEnvironment.resolutionFingerprint()).isNotEqualTo(first.resolutionFingerprint());
        assertThat(otherTarget.resolutionFingerprint()).isNotEqualTo(first.resolutionFingerprint());
        verify(repository, times(2)).operationalSnapshot(
                "tenant-a", "dev", TARGET.targetLayer(), TARGET.targetArtifactType(), TARGET.targetArtifactKey());
        verify(repository, never()).findAll();
    }

    @Test
    void rejectsTwoWellFormedAppliedHeadsForTheSameExactCoordinate() throws Exception {
        DomainRuleMaterializationRepository repository = mock(DomainRuleMaterializationRepository.class);
        when(repository.operationalSnapshot("tenant-a", "dev", TARGET.targetLayer(), TARGET.targetArtifactType(), TARGET.targetArtifactKey()))
                .thenReturn(List.of(canonicalAppliedRow(UUID.randomUUID()), canonicalAppliedRow(UUID.randomUUID())));

        OperationalPolicyResolution resolution = reader(repository, transactionManager())
                .resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "dev"));

        assertUnavailable(resolution);
    }

    @Test
    void returnsDetachedCanonicalPolicySnapshotFromRealProjection() throws Exception {
        DomainRuleMaterializationRepository repository = mock(DomainRuleMaterializationRepository.class);
        when(repository.operationalSnapshot("tenant-a", "dev", TARGET.targetLayer(), TARGET.targetArtifactType(), TARGET.targetArtifactKey()))
                .thenReturn(List.of(canonicalAppliedRow(UUID.randomUUID())));

        OperationalPolicyResolution resolution = reader(repository, transactionManager())
                .resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "dev"));

        assertThat(resolution.resolutionState()).isEqualTo(OperationalPolicyResolution.State.ELIGIBLE_APPLIED_HEAD);
        assertThat(resolution.policy().effect()).isEqualTo(OperationalPolicyResolution.Effect.BLOCK);
        ObjectNode callerCopy = (ObjectNode) resolution.policy().payload();
        callerCopy.with("validationPolicy").put("effect", "ALLOW");
        assertThat(resolution.policy().payload().path("validationPolicy").path("effect").asText()).isEqualTo("BLOCK");
    }

    private OperationalPolicyResolution resolveRows(List<String> rows) {
        DomainRuleMaterializationRepository repository = mock(DomainRuleMaterializationRepository.class);
        when(repository.operationalSnapshot(any(), any(), any(), any(), any())).thenReturn(rows);
        return reader(repository, transactionManager()).resolveOperationalPolicy(TARGET, principal("tenant-a", "reader-a", "dev"));
    }

    private String canonicalAppliedRow(UUID materializationId) throws Exception {
        UUID definitionId = UUID.randomUUID();
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.putObject("validationPolicy").put("effect", "BLOCK");
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("procurement.suppliers.rule.block-invalid")
                .version(1)
                .ruleType("validation")
                .status("active")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Block invalid suppliers.\"}")
                .parameters(parameters.toString())
                .governance("{}")
                .build();
        JsonNode payload = projections().operationalProjection(definition, TARGET);
        String sourceHash = DomainRuleMaterializationFingerprint.sha256(definition, TARGET.targetLayer(), TARGET.targetArtifactType(),
                TARGET.targetArtifactKey(), "/validationPolicy", "backend-validation-policy", payload);
        ObjectNode source = objectMapper.createObjectNode();
        source.put("id", definitionId.toString());
        source.put("tenantId", "tenant-a");
        source.put("environment", "dev");
        source.put("status", "active");
        source.put("ruleKey", definition.getRuleKey());
        source.put("version", 1);
        source.put("ruleType", "validation");
        source.put("resourceKey", "procurement.suppliers");
        source.put("serviceKey", "praxis-api-quickstart");
        source.put("contextKey", "procurement");
        source.set("definition", objectMapper.readTree(definition.getDefinition()));
        source.set("parameters", parameters);
        source.putNull("condition");
        source.set("governance", objectMapper.createObjectNode());
        ObjectNode row = objectMapper.createObjectNode();
        row.put("kind", "materialization");
        row.put("id", materializationId.toString());
        row.put("revision", 1);
        row.put("status", "applied");
        row.put("everApplied", true);
        row.put("sourceHash", sourceHash);
        row.put("definitionId", definitionId.toString());
        row.put("targetPointer", "/validationPolicy");
        row.put("materializedRuleId", "backend-validation-policy");
        row.set("payload", payload);
        row.set("definition", source);
        return objectMapper.writeValueAsString(row);
    }

    private OperationalPolicyService reader(
            DomainRuleMaterializationRepository repository, PlatformTransactionManager transactionManager) {
        return new OperationalPolicyService(repository, projections(), transactionManager, CLOCK);
    }

    @SuppressWarnings("unchecked")
    private DomainRuleService projections() {
        return new DomainRuleService(
                mock(DomainRuleDefinitionRepository.class),
                mock(DomainRuleMaterializationRepository.class),
                mock(DomainRuleEventRepository.class),
                mock(DomainCatalogReleaseRepository.class),
                mock(DomainKnowledgeChangeSetRepository.class),
                mock(DomainRuleDefinitionApprovalRepository.class),
                new DomainRuleDefinitionFingerprint(objectMapper),
                objectMapper,
                mock(ObjectProvider.class),
                new GovernedColorPaletteContractValidator(objectMapper),
                mock(DomainRuleEntityRefresh.class));
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return transactionManager;
    }

    private DomainRuleGovernancePrincipal principal(String tenant, String actor, String environment) {
        return new DomainRuleGovernancePrincipal(tenant, actor, environment);
    }

    private void assertUnavailable(OperationalPolicyResolution resolution) {
        assertThat(resolution.resolutionState()).isEqualTo(OperationalPolicyResolution.State.INCONSISTENT_OR_UNAVAILABLE);
        assertThat(resolution.policy()).isNull();
    }
}
