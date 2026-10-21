package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.stream.Stream;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleDefinitionApproval;
import org.praxisplatform.config.domain.DomainRuleCompositionApproval;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.dto.DomainRuleSnapshotPublicationRequest;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestRequest;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleCompositionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleExtensionTrust;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.snapshot.PraxisRuleSnapshotCompiler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;

@Tag("unit")
class DomainRuleSnapshotServiceTest {
  private final DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
  private final DomainRuleSnapshotRepository snapshotRepository = mock(DomainRuleSnapshotRepository.class);
  private final DomainRuleSnapshotHeadRepository headRepository = mock(DomainRuleSnapshotHeadRepository.class);
  private final DomainRuleSnapshotEventRepository eventRepository = mock(DomainRuleSnapshotEventRepository.class);
  private final DomainRuleCompositionApprovalRepository compositionApprovalRepository =
      mock(DomainRuleCompositionApprovalRepository.class);
  private final DomainRuleDefinitionApprovalRepository definitionApprovalRepository =
      mock(DomainRuleDefinitionApprovalRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DomainRuleImplementationCatalog implementationCatalog = mock(
      DomainRuleImplementationCatalog.class);
  private final Map<UUID, String> definitionApprovers = new HashMap<>();
  private DomainRuleSnapshotService service;
  private final DomainRuleSnapshotScopeLock scopeLock = mock(DomainRuleSnapshotScopeLock.class);
  private final DomainRuleEntityRefresh entityRefresh = mock(DomainRuleEntityRefresh.class);
  private final DomainRuleSnapshotActivationGate defaultActivationGate = mock(DomainRuleSnapshotActivationGate.class);

  @BeforeEach
  void setUp() {
    definitionApprovers.clear();
    service = new DomainRuleSnapshotService(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        compositionApprovalRepository,
        definitionApprovalRepository,
        new DomainRuleDefinitionFingerprint(objectMapper),
        objectMapper,
        implementationCatalog,  defaultActivationGate,  null,  scopeLock, entityRefresh);
    when(implementationCatalog.allowedImplementations(any())).thenReturn(List.of(
        new RuleImplementationRef("benefits:amount", "1.0.0")));
    when(snapshotRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(headRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(definitionApprovalRepository
        .findByTenantIdAndEnvironmentAndDefinitionIdAndDefinitionHashOrderByApprovedAtAsc(
            anyString(), anyString(), any(UUID.class), anyString())).thenAnswer(invocation -> {
              UUID definitionId = invocation.getArgument(2);
              String approver = definitionApprovers.get(definitionId);
              if (approver == null) return List.of();
              return List.of(DomainRuleDefinitionApproval.builder()
                  .id(UUID.nameUUIDFromBytes((definitionId + ":approval").getBytes()))
                  .tenantId(invocation.getArgument(0))
                  .environment(invocation.getArgument(1))
                  .definitionId(definitionId)
                  .definitionHash(invocation.getArgument(3))
                  .actorRef(approver)
                  .role("RULE_DEFINITION_APPROVER")
                  .approvedAt(Instant.parse("2026-07-13T19:00:00Z"))
                  .build());
            });
  }

  @Test
  void maximumRuleSetVersionIsScopedAndIndependentOfHeadOrPublicationOrder() {
    when(snapshotRepository.findMaximumRuleSetVersion("tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(100);
    assertThat(service.findMaximumRuleSetVersion("tenant-a", "prod", "extraordinary-grant"))
        .isEqualTo(100);
    assertThat(service.findMaximumRuleSetVersion("tenant-b", "prod", "extraordinary-grant"))
        .isZero();
    verify(headRepository, never()).findByTenantIdAndEnvironmentAndRuleSetKey(any(), any(), any());
    assertThatThrownBy(() -> service.findMaximumRuleSetVersion(" ", "prod", "extraordinary-grant"))
        .hasMessageContaining("X-Tenant-ID");
  }

  @Test
  void parameterizedPublicationChecksApprovedSourceBeforeCreatingSnapshot() throws Exception {
    UUID id = UUID.randomUUID();
    var source = approvedDefinition(id, "limit", "approver-a");
    source.setDefinition(DomainRuleParametersTest.definition(objectMapper).toString());
    source.setParameters("{\"parameterValues\":{\"limit\":50}}");
    source.setCondition("{\"<=\":[{\"var\":\"request.amount\"},50]}");
    when(definitionRepository.findAllById(List.of(id))).thenReturn(List.of(source));
    when(headRepository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    var template = DomainRuleParametersTest.template(objectMapper);
    var binding = new DecisionBinding("limit", "limit", DecisionSource.CUSTOMER,
        CompositionPolicy.PARAMETERIZE, null, List.of(), 1, true, null, null, List.of(),
        new org.praxisplatform.rules.contract.RuleParameterization("test:grant-share", "1",
            objectMapper.createObjectNode().put("limit", 50)));
    var set = new RuleSetDefinition(ruleSet().ref(), List.of("request"),
        List.of(new DecisionSlot("limit", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
            OverridePolicy.PARAMETERIZABLE, DecisionAggregationPolicy.SINGLE_RESULT, template)),
        List.of(binding), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    var request = publicationRequest(set, List.of(id));
    var response = service.publish(request, "tenant-a", "prod", null, "*");
    assertThat(response.snapshot().ruleSet().bindings().getFirst().executor()).isNull();
    assertThat(response.snapshot().ruleSet().bindings().getFirst().parameterization().values().path("limit").intValue())
        .isEqualTo(50);
    clearInvocations(snapshotRepository);
    source.setParameters("{\"parameterValues\":{\"limit\":60}}");
    source.setCondition("{\"<=\":[{\"var\":\"request.amount\"},60]}");
    assertThatThrownBy(() -> service.publish(request, "tenant-a", "prod", null, "*"))
        .hasMessageContaining("approved source template and values");
    verify(snapshotRepository, never()).save(any());
  }

  @Test
  void firstPublicationCompilesGovernedSnapshotAndCreatesOpaqueHead() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));

    DomainRuleSnapshotPublicationRequest request =
        publicationRequest(ruleSet(), List.of(firstId, secondId));
    clearInvocations(implementationCatalog);

    var response = service.publish(
        request,
        "tenant-a",
        "prod",
        null,
        "*");

    assertThat(response.snapshot().publicationRevision()).isEqualTo(1);
    assertThat(response.snapshot().sources()).hasSize(2);
    assertThat(response.snapshot().approvals()).extracting(RuleSnapshotApproval::actorRef)
        .containsExactlyInAnyOrder(
            "approver-a", "approver-b", "composition-approver-a", "composition-approver-b");
    assertThat(response.snapshotContentHash()).matches("[A-F0-9]{64}");
    verify(snapshotRepository).saveAndFlush(any(DomainRuleSnapshot.class));
    verify(headRepository, never()).saveAndFlush(any());
    verify(defaultActivationGate, never()).requireAllowed(any(), any(), any(), any());
    verify(defaultActivationGate, never()).activationCompleted(any(), any(), any());
    verify(eventRepository).save(argThat(event -> "PUBLISHED".equals(event.getEventType())
        && event.getHeadEtag() == null && event.getActivationRevision() == null));
    verify(implementationCatalog, times(1)).allowedImplementations(
        new DomainRuleImplementationScope("tenant-a", "prod", "quickstart"));
  }

  @Test
  void publishesOrderedReactiveDeterminationsAsOneImmutableAggregateHead() {
    UUID netSalaryId = UUID.randomUUID();
    UUID paymentDateId = UUID.randomUUID();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "human-resources.payroll.reactive-determinations"))
        .thenReturn(Optional.empty());
    when(definitionRepository.findAllById(List.of(netSalaryId, paymentDateId))).thenReturn(List.of(
        approvedDefinition(netSalaryId, "human-resources.payroll.net-salary", "approver-a"),
        approvedDefinition(paymentDateId, "human-resources.payroll.payment-date", "approver-b")));

    var response = service.publish(
        publicationRequest(payrollReactiveDeterminations(1), List.of(netSalaryId, paymentDateId)),
        "tenant-a", "prod", null, "*");

    assertThat(response.snapshot().ruleSet().bindings())
        .extracting(DecisionBinding::bindingKey)
        .containsExactly(
            "human-resources.payroll.net-salary",
            "human-resources.payroll.payment-date");
    assertThat(response.snapshot().ruleSet().bindings().get(1).dependsOn())
        .containsExactly("human-resources.payroll.net-salary");
    assertThat(response.snapshot().sources())
        .extracting(RuleSnapshotSource::definitionKey)
        .containsExactlyInAnyOrder(
            "human-resources.payroll.net-salary",
            "human-resources.payroll.payment-date");
    verify(headRepository, never()).saveAndFlush(any());
  }

  @Test
  void publishedJavaExecutorSurvivesPersistenceRoundTrip() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));

    service.publish(
        publicationRequest(javaRuleSet(), List.of(firstId, secondId)),
        "tenant-a",
        "prod",
        null,
        "*");

    ArgumentCaptor<DomainRuleSnapshot> snapshotCaptor = ArgumentCaptor.forClass(DomainRuleSnapshot.class);
    verify(snapshotRepository).saveAndFlush(snapshotCaptor.capture());
    DomainRuleSnapshot stored = snapshotCaptor.getValue();
    DomainRuleSnapshotHead head = rollbackHead(stored.getId(), UUID.randomUUID());
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        stored.getId(), "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(stored));

    var active = service.findActive("tenant-a", "prod", "extraordinary-grant").orElseThrow();

    assertThat(stored.getSnapshotPayload()).contains("\"expression\":null");
    assertThat(active.snapshot().ruleSet().bindings().stream()
        .filter(binding -> "calculation".equals(binding.bindingKey()))
        .findFirst().orElseThrow().executor().expression()).isNull();
    assertThat(active.snapshot().ruleSet().slots().stream()
        .filter(slot -> "calculation".equals(slot.slotKey()))
        .findFirst().orElseThrow().stage())
        .isEqualTo(DecisionStage.TRANSFORMATION_INTENT);
    verify(implementationCatalog, atLeastOnce()).allowedImplementations(new DomainRuleImplementationScope(
        "tenant-a", "prod", "quickstart"));
  }

  @Test
  void javaPublicationFailsClosedWhenHostProvidesNoExternalCatalog() {
    service = new DomainRuleSnapshotService(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        compositionApprovalRepository,
        definitionApprovalRepository,
        new DomainRuleDefinitionFingerprint(objectMapper),
        objectMapper,  org.praxisplatform.config.service.DomainRuleImplementationCatalog.denyAll(),  defaultActivationGate,  null,  scopeLock, entityRefresh);
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    prepareFirstPublication(firstId, secondId);

    assertThatThrownBy(() -> service.publish(
        publication(javaRuleSet(), firstId, secondId),
        "tenant-a", "prod", null, "*"))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(exception.getMessage()).contains("PLAN_IMPLEMENTATION_UNAVAILABLE");
            });

    verify(snapshotRepository, never()).saveAndFlush(any());
  }

  @Test
  void customerJavaPublicationRequiresAttestedExternalCatalogEntry() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    prepareFirstPublication(firstId, secondId);
    when(implementationCatalog.allowedImplementations(any())).thenReturn(List.of(
        new RuleImplementationRef(
            "customer:benefit-eligibility",
            "1.0.0",
            new RuleExtensionTrust(
                "A".repeat(64),
                "sigstore:tenant-a-release",
                "policy:customer-extension-v1",
                "B".repeat(64)))));

    var response = service.publish(
        publication(customerJavaRuleSet(), firstId, secondId),
        "tenant-a", "prod", null, "*");

    verify(snapshotRepository).saveAndFlush(any(DomainRuleSnapshot.class));
  }

  @Test
  void customerJavaPublicationRejectsUnsignedCatalogEntry() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    prepareFirstPublication(firstId, secondId);
    when(implementationCatalog.allowedImplementations(any())).thenReturn(List.of(
        new RuleImplementationRef("customer:benefit-eligibility", "1.0.0")));

    assertThatThrownBy(() -> service.publish(
        publication(customerJavaRuleSet(), firstId, secondId),
        "tenant-a", "prod", null, "*"))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(exception.getMessage()).contains("PLAN_EXTENSION_TRUST_INVALID");
            });

    verify(snapshotRepository, never()).saveAndFlush(any());
  }

  @Test
  void staleHeadEtagRejectsPublicationBeforeAnyImmutableWrite() {
    DomainRuleSnapshotHead head = DomainRuleSnapshotHead.builder()
        .id(UUID.randomUUID())
        .tenantId("tenant-a")
        .environment("prod")
        .ruleSetKey("extraordinary-grant")
        .activeSnapshotId(UUID.randomUUID())
        .activationRevision(4L)
        .headEtag(UUID.randomUUID())
        .rowVersion(0L)
        .build();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));

    assertThatThrownBy(() -> service.publish(
        unapprovedRequest(ruleSet(), List.of(UUID.randomUUID())),
        "tenant-a", "prod", "\"stale\"", null))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));

    verify(snapshotRepository, never()).saveAndFlush(any());
  }

  @Test
  void persistentConstraintFailureIsNotMisreportedAsStaleEtag() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    when(snapshotRepository.saveAndFlush(any())).thenThrow(
        new DataIntegrityViolationException("constraint failure"));

    assertThatThrownBy(() -> service.publish(
        publicationRequest(ruleSet(), List.of(firstId, secondId)),
        "tenant-a", "prod", null, "*"))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT))
        .hasMessageContaining("collided with immutable scoped content")
        .hasMessageNotContaining("head changed");
  }

  @Test
  void dirtyExternalContextIsRejectedBeforeAnyLifecycleQuery() {
    org.mockito.Mockito.doThrow(new org.praxisplatform.config.exception.ConfigurationIngestionException("pending external changes"))
        .when(entityRefresh).requireCleanSnapshotEntry();
    assertThatThrownBy(() -> service.publish(unapprovedRequest(ruleSet(), List.of(UUID.randomUUID())),
        "tenant-a", "prod", null, "*"))
        .isInstanceOf(org.praxisplatform.config.exception.ConfigurationIngestionException.class);
    verify(headRepository, never()).findByTenantIdAndEnvironmentAndRuleSetKey(any(), any(), any());
    verify(definitionRepository, never()).findAllById(any());
    verify(snapshotRepository, never()).saveAndFlush(any());
  }

  @Test
  void publicationRechecksSourceGovernanceAfterRefreshingCleanPreloadedDefinitions() {
    UUID first = UUID.randomUUID(); UUID second = UUID.randomUUID();
    var source = approvedDefinition(first, "grant:eligibility", "approver-a");
    when(definitionRepository.findAllById(List.of(first, second))).thenReturn(List.of(source,
        approvedDefinition(second, "grant:amount", "approver-b")));
    var request = publicationRequest(ruleSet(), List.of(first, second));
    org.mockito.Mockito.doAnswer(call -> { ((DomainRuleDefinition) call.getArgument(0)).setStatus("retired"); return null; })
        .when(entityRefresh).definition(source);
    assertThatThrownBy(() -> service.publish(request, "tenant-a", "prod", null, "*"))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class).hasMessageContaining("approved or active");
    verify(snapshotRepository, never()).saveAndFlush(any());
    verify(eventRepository, never()).save(any());
  }

  @Test
  void bootstrapSelectsOnlyThroughExplicitGateAndAbsentHeadPrecondition() throws Exception {
    DomainRuleSnapshot target = storedSnapshot(publishedSnapshot());
    UUID rolloutId = UUID.randomUUID();
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey("tenant-a", "prod", target.getSnapshotKey()))
        .thenReturn(Optional.of(target));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey("tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(Optional.empty());

    var result = service.activatePublished(target.getSnapshotKey(), "operator-a", "tenant-a", "prod", null, "*", rolloutId);

    assertThat(result.activationRevision()).isEqualTo(1);
    assertThat(result.activationType()).isEqualTo("ACTIVATED");
    assertThat(result.headEtag()).isNotEqualTo(result.snapshotContentHash());
    var order = inOrder(scopeLock, headRepository, defaultActivationGate, eventRepository);
    order.verify(scopeLock).lock("tenant-a", "prod", "extraordinary-grant");
    order.verify(headRepository).findByTenantIdAndEnvironmentAndRuleSetKey("tenant-a", "prod", "extraordinary-grant");
    order.verify(defaultActivationGate).requireAllowed(rolloutId, target, null, "operator-a");
    order.verify(headRepository).saveAndFlush(argThat(head -> head.getActiveSnapshotId().equals(target.getId())
        && head.getActivationRevision() == 1L && head.getRowVersion() == null));
    order.verify(eventRepository).save(argThat(event -> "ACTIVATED".equals(event.getEventType())
        && event.getFromSnapshotId() == null && event.getActivationRevision() == 1L));
    order.verify(defaultActivationGate).activationCompleted(rolloutId, target, "operator-a");
    verify(snapshotRepository, never()).saveAndFlush(any());
  }

  @Test
  void bootstrapMissingOrStalePreconditionCannotMutateAHead() throws Exception {
    DomainRuleSnapshot target = storedSnapshot(publishedSnapshot());
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey("tenant-a", "prod", target.getSnapshotKey()))
        .thenReturn(Optional.of(target));
    assertThatThrownBy(() -> service.activatePublished(target.getSnapshotKey(), "operator-a", "tenant-a", "prod", null, null, UUID.randomUUID()))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            failure -> assertThat(failure.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey("tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(Optional.of(rollbackHead(UUID.randomUUID(), UUID.randomUUID())));
    assertThatThrownBy(() -> service.activatePublished(target.getSnapshotKey(), "operator-a", "tenant-a", "prod", null, "*", UUID.randomUUID()))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            failure -> assertThat(failure.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
    verify(defaultActivationGate, never()).requireAllowed(any(), any(), any(), any());
    verify(headRepository, never()).saveAndFlush(any());
    verify(eventRepository, never()).save(any());
  }

  @Test
  void readyPublishedContentWithoutAHeadOffersGovernedRolloutInsteadOfDirectActivation() throws Exception {
    DomainRuleSnapshot stored = storedSnapshot(publishedSnapshot());
    when(snapshotRepository.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        eq("tenant-a"), eq("prod"), eq("extraordinary-grant"), any(Pageable.class))).thenReturn(List.of(stored));
    var versions = service.listVersions("tenant-a", "prod", "extraordinary-grant", 25);
    assertThat(versions.getFirst().active()).isFalse();
    assertThat(versions.getFirst().governanceState()).isEqualTo("READY");
    assertThat(versions.getFirst().availableAction()).isEqualTo("CREATE_ROLLOUT");
  }

  @Test
  void publishedRuleSetVersionCannotBeReusedForDifferentContent() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    DomainRuleSnapshotHead head = DomainRuleSnapshotHead.builder()
        .id(UUID.randomUUID())
        .tenantId("tenant-a")
        .environment("prod")
        .ruleSetKey("extraordinary-grant")
        .activeSnapshotId(UUID.randomUUID())
        .activationRevision(1L)
        .headEtag(etag)
        .rowVersion(0L)
        .build();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    when(snapshotRepository.existsByTenantIdAndEnvironmentAndRuleSetKeyAndRuleSetVersion(
        "tenant-a", "prod", "extraordinary-grant", 1)).thenReturn(true);

    assertThatThrownBy(() -> service.publish(
        publicationRequest(ruleSet(), List.of(firstId, secondId)),
        "tenant-a", "prod", "\"" + etag + "\"", null))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));

    verify(snapshotRepository, never()).saveAndFlush(any());
  }

  @Test
  void rollbackSelectsExistingContentAndAlwaysRotatesHeadEtag() throws Exception {
    UUID activeId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID oldEtag = UUID.randomUUID();
    PublishedRuleSnapshot targetContract = publishedSnapshot();
    DomainRuleSnapshot target = DomainRuleSnapshot.builder()
        .id(targetId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(targetContract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(1)
        .snapshotPayload(objectMapper.writeValueAsString(targetContract))
        .contentHash(snapshotHash(targetContract))
        .compositionManifest(compositionManifestJson())
        .compositionDigest(compositionDigest())
        .publishedBy("release-manager")
        .publishedAt(Instant.parse("2026-07-13T20:00:00Z"))
        .build();
    DomainRuleSnapshotHead head = DomainRuleSnapshotHead.builder()
        .id(UUID.randomUUID())
        .tenantId("tenant-a")
        .environment("prod")
        .ruleSetKey("extraordinary-grant")
        .activeSnapshotId(activeId)
        .activationRevision(2L)
        .headEtag(oldEtag)
        .updatedAt(Instant.now())
        .rowVersion(0L)
        .build();
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
        "tenant-a", "prod", target.getSnapshotKey())).thenReturn(Optional.of(target));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        activeId, "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(
        DomainRuleSnapshot.builder()
            .id(activeId)
            .publicationRevision(2)
            .build()));

    var response = service.rollback(
        target.getSnapshotKey(), "operator-a", "tenant-a", "prod", "\"" + oldEtag + "\"");

    assertThat(response.activationType()).isEqualTo("ROLLED_BACK");
    assertThat(response.activationRevision()).isEqualTo(3);
    assertThat(response.headEtag()).isNotEqualTo(oldEtag.toString());
    assertThat(response.snapshotContentHash()).isEqualTo(snapshotHash(targetContract));
    verify(snapshotRepository, never()).saveAndFlush(any());
    verify(eventRepository).save(argThat(argThatEvent("ROLLED_BACK", 3L)));
  }

  @Test
  void rollbackRejectsSnapshotNewerThanCurrentActivePublication() throws Exception {
    UUID activeId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    PublishedRuleSnapshot targetContract = publishedSnapshot();
    DomainRuleSnapshot target = DomainRuleSnapshot.builder()
        .id(targetId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(targetContract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(3)
        .publicationRevision(3)
        .snapshotPayload(objectMapper.writeValueAsString(targetContract))
        .contentHash("A".repeat(64))
        .compositionManifest(compositionManifestJson())
        .compositionDigest(compositionDigest())
        .build();
    DomainRuleSnapshotHead head = rollbackHead(activeId, etag);
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
        "tenant-a", "prod", target.getSnapshotKey())).thenReturn(Optional.of(target));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        activeId, "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(
        DomainRuleSnapshot.builder().id(activeId).publicationRevision(2).build()));

    assertThatThrownBy(() -> service.rollback(
        target.getSnapshotKey(), "operator-a", "tenant-a", "prod", "\"" + etag + "\""))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT))
        .hasMessageContaining("older");

    verify(headRepository, never()).save(any());
    verify(eventRepository, never()).save(any());
  }

  @Test
  void explicitActivationSelectsANewerVerifiedSnapshotAndRotatesHeadEtag() throws Exception {
    DomainRuleSnapshotActivationGate activationGate = mock(DomainRuleSnapshotActivationGate.class);
    service = new DomainRuleSnapshotService(
        definitionRepository,  snapshotRepository,  headRepository,  eventRepository,
        compositionApprovalRepository,  definitionApprovalRepository,
        new DomainRuleDefinitionFingerprint(objectMapper),  objectMapper,  implementationCatalog,
        activationGate,  null,  scopeLock, entityRefresh);
    UUID activeId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID oldEtag = UUID.randomUUID();
    PublishedRuleSnapshot targetContract = publishedSnapshotWithRevision(3);
    DomainRuleSnapshot target = DomainRuleSnapshot.builder()
        .id(targetId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(targetContract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(3)
        .snapshotPayload(objectMapper.writeValueAsString(targetContract))
        .contentHash(snapshotHash(targetContract))
        .compositionManifest(compositionManifestJson())
        .compositionDigest(compositionDigest())
        .publishedBy("release-manager")
        .publishedAt(Instant.parse("2026-07-15T20:00:00Z"))
        .build();
    DomainRuleSnapshotHead head = rollbackHead(activeId, oldEtag);
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
        "tenant-a", "prod", target.getSnapshotKey())).thenReturn(Optional.of(target));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        activeId, "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(
        DomainRuleSnapshot.builder().id(activeId).publicationRevision(2).build()));

    UUID rolloutId = UUID.randomUUID();
    var response = service.activatePublished(
        target.getSnapshotKey(),  "operator-a",  "tenant-a",  "prod",  "\"" + oldEtag + "\"",  null,
        rolloutId);

    assertThat(response.activationType()).isEqualTo("ACTIVATED");
    assertThat(response.activationRevision()).isEqualTo(3);
    assertThat(response.headEtag()).isNotEqualTo(oldEtag.toString());
    verify(snapshotRepository, never()).saveAndFlush(any());
    verify(eventRepository).save(argThat(argThatEvent("ACTIVATED", 3L)));
    var order = inOrder(activationGate, headRepository, eventRepository);
    order.verify(activationGate).requireAllowed(rolloutId, target, head, "operator-a");
    order.verify(headRepository).saveAndFlush(head);
    order.verify(eventRepository).save(any());
    order.verify(activationGate).activationCompleted(rolloutId, target, "operator-a");
  }

  @Test
  void explicitActivationRejectsAnOlderSnapshotAndDirectsTheCallerToRollback() throws Exception {
    UUID activeId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    PublishedRuleSnapshot targetContract = publishedSnapshot();
    DomainRuleSnapshot target = DomainRuleSnapshot.builder()
        .id(targetId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(targetContract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(1)
        .snapshotPayload(objectMapper.writeValueAsString(targetContract))
        .contentHash(snapshotHash(targetContract))
        .compositionManifest(compositionManifestJson())
        .compositionDigest(compositionDigest())
        .build();
    DomainRuleSnapshotHead head = rollbackHead(activeId, etag);
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
        "tenant-a", "prod", target.getSnapshotKey())).thenReturn(Optional.of(target));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        activeId, "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(
        DomainRuleSnapshot.builder().id(activeId).publicationRevision(2).build()));

    assertThatThrownBy(() -> service.activatePublished(
        target.getSnapshotKey(),  "operator-a",  "tenant-a",  "prod",  "\"" + etag + "\"",  null,  null))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT))
        .hasMessageContaining("use rollback");

    verify(headRepository, never()).save(any());
    verify(eventRepository, never()).save(any());
  }

  @Test
  void rollbackRejectsSnapshotOutsideGovernedValidityInterval() throws Exception {
    UUID activeId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    Instant future = Instant.now().plusSeconds(3600);
    PublishedRuleSnapshot targetContract = publishedSnapshot(future.toString(), null);
    DomainRuleSnapshot target = DomainRuleSnapshot.builder()
        .id(targetId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(targetContract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(1)
        .snapshotPayload(objectMapper.writeValueAsString(targetContract))
        .contentHash(snapshotHash(targetContract))
        .compositionManifest(compositionManifestJson())
        .compositionDigest(compositionDigest())
        .build();
    DomainRuleSnapshotHead head = rollbackHead(activeId, etag);
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
        "tenant-a", "prod", target.getSnapshotKey())).thenReturn(Optional.of(target));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        activeId, "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(
        DomainRuleSnapshot.builder().id(activeId).publicationRevision(2).build()));

    assertThatThrownBy(() -> service.rollback(
        target.getSnapshotKey(), "operator-a", "tenant-a", "prod", "\"" + etag + "\""))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT))
        .hasMessageContaining("validity interval");

    PublishedRuleSnapshot expiredContract = publishedSnapshot(
        Instant.now().minusSeconds(7200).toString(),
        Instant.now().minusSeconds(3600).toString());
    DomainRuleSnapshot expired = DomainRuleSnapshot.builder()
        .id(UUID.randomUUID())
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(expiredContract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(1)
        .snapshotPayload(objectMapper.writeValueAsString(expiredContract))
        .contentHash(snapshotHash(expiredContract))
        .compositionManifest(compositionManifestJson())
        .compositionDigest(compositionDigest())
        .build();
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
        "tenant-a", "prod", expired.getSnapshotKey())).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> service.rollback(
        expired.getSnapshotKey(), "operator-a", "tenant-a", "prod", "\"" + etag + "\""))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT))
        .hasMessageContaining("validity interval");

    verify(headRepository, never()).save(any());
    verify(eventRepository, never()).save(any());
  }

  @Test
  void activeReadFailsClosedWhenPersistedContentHashDoesNotMatchEnvelope() throws Exception {
    PublishedRuleSnapshot contract = publishedSnapshot();
    UUID storedId = UUID.randomUUID();
    DomainRuleSnapshot stored = DomainRuleSnapshot.builder()
        .id(storedId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(contract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(1)
        .snapshotPayload(objectMapper.writeValueAsString(contract))
        .contentHash("F".repeat(64))
        .compositionManifest(compositionManifestJson())
        .compositionDigest(compositionDigest())
        .build();
    DomainRuleSnapshotHead head = rollbackHead(storedId, UUID.randomUUID());
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        storedId, "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(stored));

    assertThatThrownBy(() -> service.findActive("tenant-a", "prod", "extraordinary-grant"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("content hash verification failed");
  }

  @Test
  void publicationRejectsCompositionDriftAfterApproval() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<UUID> ids = List.of(firstId, secondId);
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(definitionRepository.findAllById(ids)).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    DomainRuleSnapshotPublicationRequest approved = publicationRequest(ruleSet(), ids);
    DomainRuleSnapshotPublicationRequest drifted = new DomainRuleSnapshotPublicationRequest(
        javaRuleSet(), ids, approved.ownerServiceKey(), approved.requiredHostContractVersion(),
        approved.validFromUtc(), approved.validUntilUtc(), approved.compositionDigest());

    assertThatThrownBy(() -> service.publish(drifted, "tenant-a", "prod", null, "*"))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class)
        .hasMessageContaining("compositionDigest");
    verify(snapshotRepository, never()).saveAndFlush(any());
  }

  @Test
  void governedPublicationCanSupersedePreservedPreManifestSnapshot() throws Exception {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    UUID previousId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    PublishedRuleSnapshot previousContract = publishedSnapshot();
    DomainRuleSnapshot previous = DomainRuleSnapshot.builder()
        .id(previousId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(previousContract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(1)
        .snapshotPayload(objectMapper.writeValueAsString(previousContract))
        .contentHash(org.praxisplatform.rules.digest.PraxisCanonicalJson.sha256(
            objectMapper.valueToTree(previousContract)))
        .compositionManifest(null)
        .compositionDigest(null)
        .publishedBy("legacy-publisher")
        .publishedAt(Instant.parse("2026-07-13T20:00:00Z"))
        .build();
    DomainRuleSnapshotHead head = rollbackHead(previousId, etag);
    head.setActivationRevision(1L);
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    when(snapshotRepository.findMaximumPublicationRevision(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(1);
    when(snapshotRepository.findTopByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(previous));

    var response = service.publish(
        publicationRequest(ruleSet(2), List.of(firstId, secondId)),
        "tenant-a", "prod", "\"" + etag + "\"", null);

    assertThat(response.snapshot().ruleSet().ref().version()).isEqualTo(2);
    assertThat(response.snapshot().supersedesSnapshotKey()).isEqualTo(previous.getSnapshotKey());
    ArgumentCaptor<DomainRuleSnapshot> persisted = ArgumentCaptor.forClass(DomainRuleSnapshot.class);
    verify(snapshotRepository).saveAndFlush(persisted.capture());
    assertThat(persisted.getValue().getSupersedesSnapshotId()).isEqualTo(previousId);
    assertThat(persisted.getValue().getCompositionManifest()).isNotBlank();
    assertThat(persisted.getValue().getCompositionDigest()).matches("[A-F0-9]{64}");
  }

  @Test
  void governedPublicationCanSupersedeVerifiedManifestSnapshotFromOlderEngineBaseline() throws Exception {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    UUID previousId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    RuleSetDefinition current = ruleSet(1);
    RuleSetDefinition olderDefinition = new RuleSetDefinition(
        current.ref(), current.availableRoots(), current.slots(), current.bindings(),
        new RuleRuntimeCompatibility(
            "1.4",
            RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION,
            RuleRuntimeCompatibility.JSON_LOGIC_CORPUS_SHA256),
        current.failPolicy());
    PublishedRuleSnapshot previousContract = publishedSnapshot(
        "2026-07-13T20:00:00Z", null, olderDefinition);
    DomainRuleSnapshot previous = DomainRuleSnapshot.builder()
        .id(previousId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(previousContract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(1)
        .snapshotPayload(objectMapper.writeValueAsString(previousContract))
        .contentHash(org.praxisplatform.rules.digest.PraxisCanonicalJson.sha256(
            objectMapper.valueToTree(previousContract)))
        .compositionManifest(compositionManifestJson())
        .compositionDigest(compositionDigest())
        .publishedBy("prior-release-manager")
        .publishedAt(Instant.parse("2026-07-13T20:00:00Z"))
        .build();
    DomainRuleSnapshotHead head = rollbackHead(previousId, etag);
    head.setActivationRevision(1L);
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    when(snapshotRepository.findMaximumPublicationRevision(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(1);
    when(snapshotRepository.findTopByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(previous));

    var response = service.publish(
        publicationRequest(ruleSet(2), List.of(firstId, secondId)),
        "tenant-a", "prod", "\"" + etag + "\"", null);

    assertThat(response.snapshot().ruleSet().ref().version()).isEqualTo(2);
    assertThat(response.snapshot().supersedesSnapshotKey()).isEqualTo(previousContract.snapshotKey());
    verify(snapshotRepository).saveAndFlush(argThat(snapshot -> previousId.equals(snapshot.getSupersedesSnapshotId())));
  }

  @Test
  void preManifestHeadIsInspectableForRecoveryButRemainsUnreadableForRuntime() throws Exception {
    UUID storedId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    PublishedRuleSnapshot contract = publishedSnapshot();
    DomainRuleSnapshot stored = DomainRuleSnapshot.builder()
        .id(storedId)
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey(contract.snapshotKey())
        .ruleSetKey("extraordinary-grant")
        .ruleSetVersion(1)
        .publicationRevision(1)
        .snapshotPayload(objectMapper.writeValueAsString(contract))
        .contentHash(snapshotHash(contract))
        .build();
    DomainRuleSnapshotHead head = rollbackHead(storedId, etag);
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        storedId, "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(stored));

    var status = service.findHeadStatus("tenant-a", "prod", "extraordinary-grant").orElseThrow();

    assertThat(status.executionReady()).isFalse();
    assertThat(status.governanceState()).isEqualTo("REPUBLICATION_REQUIRED");
    assertThat(status.headEtag()).isEqualTo(etag.toString());
    assertThatThrownBy(() -> service.findActive("tenant-a", "prod", "extraordinary-grant"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("composition manifest is unreadable");
  }

  @ParameterizedTest
  @MethodSource("incompatibleTuples")
  void intactIncompatibleHistoryRemainsReadableWithoutGrantingExecution(
      RuleRuntimeCompatibility compatibility) throws Exception {
    PublishedRuleSnapshot historical = withCompatibility(publishedSnapshot(), compatibility);
    DomainRuleSnapshot stored = storedSnapshot(historical);
    DomainRuleSnapshotHead head = stubSelectedSnapshot(stored);
    String bytes = stored.getSnapshotPayload();
    String hash = stored.getContentHash();
    String manifest = stored.getCompositionManifest();
    UUID etag = head.getHeadEtag();

    var detail = service.findSnapshot("tenant-a", "prod", stored.getSnapshotKey()).orElseThrow();
    assertThat(detail.snapshot()).isEqualTo(historical);
    assertThat(detail.snapshotContentHash()).isEqualTo(hash);
    assertHistoricalState(stored, "RUNTIME_INCOMPATIBLE", false, "UNAVAILABLE");
    verify(implementationCatalog, never()).allowedImplementations(any());
    assertThatThrownBy(() -> service.findActive("tenant-a", "prod", "extraordinary-grant"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no longer admitted");
    assertMutationBlocked(stored);
    assertThat(stored.getSnapshotPayload()).isEqualTo(bytes);
    assertThat(stored.getContentHash()).isEqualTo(hash);
    assertThat(stored.getCompositionManifest()).isEqualTo(manifest);
    assertThat(head.getHeadEtag()).isEqualTo(etag);
    verify(snapshotRepository, never()).saveAndFlush(any());
    verify(headRepository, never()).save(any());
    verify(eventRepository, never()).save(any());
  }

  static Stream<RuleRuntimeCompatibility> incompatibleTuples() {
    RuleRuntimeCompatibility current = RuleRuntimeCompatibility.current();
    return Stream.of(
        new RuleRuntimeCompatibility("1.4", current.jsonLogicDialectVersion(), current.jsonLogicCorpusSha256()),
        new RuleRuntimeCompatibility(current.engineContractVersion(), "9.9", current.jsonLogicCorpusSha256()),
        new RuleRuntimeCompatibility(current.engineContractVersion(), current.jsonLogicDialectVersion(), "A".repeat(64)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hash", "envelope", "payload", "manifest", "partial-manifest",
      "approval", "approval-role", "approval-hash", "approval-key", "test-evidence"})
  void corruptIncompatibleHistoryIsInvalidBeforeCompatibilityClassification(String corruption) throws Exception {
    DomainRuleSnapshot stored = storedSnapshot(withCompatibility(publishedSnapshot(), incompatibleTuples().findFirst().orElseThrow()));
    switch (corruption) {
      case "hash" -> stored.setContentHash("0".repeat(64));
      case "envelope" -> stored.setRuleSetVersion(7);
      case "payload" -> stored.setSnapshotPayload("{broken");
      case "manifest" -> stored.setCompositionManifest("{\"changed\":true}");
      case "partial-manifest" -> stored.setCompositionDigest(null);
      case "approval", "approval-role", "approval-hash", "approval-key" -> {
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(stored.getSnapshotPayload());
        var approval = (com.fasterxml.jackson.databind.node.ObjectNode) payload.path("approvals").get(2);
        switch (corruption) {
          case "approval" -> approval.put("actorRef", "composition-a");
          case "approval-role" -> approval.put("role", "RULE_DEFINITION_APPROVER");
          case "approval-hash" -> approval.put("evidenceHash", "0".repeat(64));
          case "approval-key" -> approval.put("approvalKey", "composition-a");
          default -> throw new AssertionError(corruption);
        }
        stored.setSnapshotPayload(objectMapper.writeValueAsString(payload));
        stored.setContentHash(org.praxisplatform.rules.digest.PraxisCanonicalJson.sha256(payload));
      }
      case "test-evidence" -> {
        String manifest = "{\"compositionContractVersion\":\"praxis-rule-composition/2\",\"testEvidence\":[{\"satisfied\":false}]}";
        String digest = org.praxisplatform.rules.digest.PraxisCanonicalJson.sha256(objectMapper.readTree(manifest));
        stored.setCompositionManifest(manifest);
        stored.setCompositionDigest(digest);
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(stored.getSnapshotPayload());
        for (var approval : payload.path("approvals")) {
          if ("RULE_COMPOSITION_APPROVER".equals(approval.path("role").asText())) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) approval).put("evidenceHash", digest);
          }
        }
        stored.setSnapshotPayload(objectMapper.writeValueAsString(payload));
        stored.setContentHash(org.praxisplatform.rules.digest.PraxisCanonicalJson.sha256(payload));
      }
      default -> throw new AssertionError(corruption);
    }
    stubSelectedSnapshot(stored);

    assertHistoricalState(stored, "INVALID", false, "UNAVAILABLE");
    assertThatThrownBy(() -> service.findSnapshot("tenant-a", "prod", stored.getSnapshotKey()))
        .isInstanceOf(IllegalStateException.class);
    verify(implementationCatalog, never()).allowedImplementations(any());
  }

  @Test
  void revokedCurrentCatalogBlocksExecutionButPreservesHistoricalDetail() throws Exception {
    PublishedRuleSnapshot compiled = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.planning(
        List.of(new RuleImplementationRef("benefits:amount", "1.0.0"))))
        .compile(publishedSnapshot("2026-07-13T20:00:00Z", null, javaRuleSet()), "quickstart/1.0").snapshot();
    DomainRuleSnapshot stored = storedSnapshot(compiled);
    stubSelectedSnapshot(stored);
    when(implementationCatalog.allowedImplementations(any())).thenReturn(List.of());

    assertThat(service.findSnapshot("tenant-a", "prod", stored.getSnapshotKey()).orElseThrow().snapshot())
        .isEqualTo(compiled);
    verify(implementationCatalog, never()).allowedImplementations(any());
    assertHistoricalState(stored, "INVALID", false, "UNAVAILABLE");
    assertThatThrownBy(() -> service.findActive("tenant-a", "prod", "extraordinary-grant"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no longer admitted");
    assertMutationBlocked(stored);
    verify(headRepository, never()).save(any());
    verify(eventRepository, never()).save(any());
  }

  @Test
  void currentAdmittedSnapshotIsReadyAndItsSelectedActionIsActive() throws Exception {
    DomainRuleSnapshot stored = storedSnapshot(publishedSnapshot());
    stubSelectedSnapshot(stored);
    assertHistoricalState(stored, "READY", true, "ACTIVE");
    assertThat(service.findSnapshot("tenant-a", "prod", stored.getSnapshotKey()).orElseThrow().snapshotContentHash())
        .isEqualTo(stored.getContentHash());
    assertThat(service.findActive("tenant-a", "prod", "extraordinary-grant").orElseThrow().snapshotContentHash())
        .isEqualTo(stored.getContentHash());
  }

  @Test
  void preManifestNeedsVerifiedIntegrityAndNeverExposesDetail() throws Exception {
    DomainRuleSnapshot stored = storedSnapshot(publishedSnapshot());
    stored.setCompositionManifest(null);
    stored.setCompositionDigest(null);
    stubSelectedSnapshot(stored);
    assertHistoricalState(stored, "REPUBLICATION_REQUIRED", false, "UNAVAILABLE");
    assertThatThrownBy(() -> service.findSnapshot("tenant-a", "prod", stored.getSnapshotKey()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("composition manifest");
    stored.setContentHash("0".repeat(64));
    assertHistoricalState(stored, "INVALID", false, "UNAVAILABLE");
    assertThatThrownBy(() -> service.findSnapshot("tenant-a", "prod", stored.getSnapshotKey()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("hash verification");
    verify(implementationCatalog, never()).allowedImplementations(any());
  }

  @Test
  void historicalReadsNeverFallBackAcrossTenantOrEnvironment() throws Exception {
    DomainRuleSnapshot stored = storedSnapshot(publishedSnapshot());
    stubSelectedSnapshot(stored);
    assertThat(service.findSnapshot("tenant-b", "prod", stored.getSnapshotKey())).isEmpty();
    assertThat(service.findSnapshot("tenant-a", "dev", stored.getSnapshotKey())).isEmpty();
    assertThat(service.findHeadStatus("tenant-b", "prod", "extraordinary-grant")).isEmpty();
    assertThat(service.listVersions("tenant-b", "prod", "extraordinary-grant", 25)).isEmpty();
    verify(snapshotRepository).findByTenantIdAndEnvironmentAndSnapshotKey("tenant-b", "prod", stored.getSnapshotKey());
    verify(snapshotRepository).findByTenantIdAndEnvironmentAndSnapshotKey("tenant-a", "dev", stored.getSnapshotKey());
    verify(snapshotRepository, never()).saveAndFlush(any());
    verify(implementationCatalog, never()).allowedImplementations(any());
  }

  private void assertHistoricalState(DomainRuleSnapshot stored, String state, boolean ready, String action) {
    var status = service.findHeadStatus("tenant-a", "prod", "extraordinary-grant").orElseThrow();
    assertThat(status.governanceState()).isEqualTo(state);
    assertThat(status.executionReady()).isEqualTo(ready);
    var version = service.listVersions("tenant-a", "prod", "extraordinary-grant", 25).getFirst();
    assertThat(version.governanceState()).isEqualTo(state);
    assertThat(version.availableAction()).isEqualTo(action);
    assertThat(version.active()).isTrue();
    assertThat(version.snapshotContentHash()).isEqualTo(stored.getContentHash());
  }

  private DomainRuleSnapshotHead stubSelectedSnapshot(DomainRuleSnapshot stored) {
    DomainRuleSnapshotHead head = rollbackHead(stored.getId(), UUID.randomUUID());
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey("tenant-a", "prod", stored.getSnapshotKey()))
        .thenReturn(Optional.of(stored));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey("tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(stored.getId(), "tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(Optional.of(stored));
    when(snapshotRepository.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        eq("tenant-a"), eq("prod"), eq("extraordinary-grant"), any(Pageable.class))).thenReturn(List.of(stored));
    return head;
  }

  private void assertMutationBlocked(DomainRuleSnapshot target) {
    UUID activeId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    DomainRuleSnapshot active = DomainRuleSnapshot.builder().id(activeId).publicationRevision(2).build();
    DomainRuleSnapshotHead head = rollbackHead(activeId, etag);
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey("tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(activeId, "tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(Optional.of(active));
    assertThatThrownBy(() -> service.rollback(target.getSnapshotKey(), "operator-a", "tenant-a", "prod", "\"" + etag + "\""))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no longer admitted");
    active.setPublicationRevision(0);
    assertThatThrownBy(() -> service.activatePublished(target.getSnapshotKey(),  "operator-a",  "tenant-a",  "prod",  "\"" + etag + "\"",  null,  null))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no longer admitted");
    assertThat(head.getHeadEtag()).isEqualTo(etag);
    assertThat(head.getActivationRevision()).isEqualTo(2L);
  }

  private PublishedRuleSnapshot withCompatibility(PublishedRuleSnapshot snapshot, RuleRuntimeCompatibility compatibility) {
    RuleSetDefinition definition = snapshot.ruleSet();
    return new PublishedRuleSnapshot(
        snapshot.snapshotContractVersion(), snapshot.snapshotKey(), snapshot.tenantId(), snapshot.environment(),
        snapshot.ownerServiceKey(), snapshot.publicationRevision(), snapshot.publishedAtUtc(), snapshot.supersedesSnapshotKey(),
        snapshot.requiredHostContractVersion(), snapshot.validFromUtc(), snapshot.validUntilUtc(), snapshot.sources(), snapshot.approvals(),
        new RuleSetDefinition(definition.ref(), definition.availableRoots(), definition.slots(), definition.bindings(), compatibility, definition.failPolicy()));
  }

  private DomainRuleSnapshot storedSnapshot(PublishedRuleSnapshot snapshot) throws Exception {
    return DomainRuleSnapshot.builder().id(UUID.randomUUID()).tenantId(snapshot.tenantId()).environment(snapshot.environment())
        .snapshotKey(snapshot.snapshotKey()).ruleSetKey(snapshot.ruleSet().ref().ruleSetKey()).ruleSetVersion(snapshot.ruleSet().ref().version())
        .publicationRevision(snapshot.publicationRevision()).snapshotPayload(objectMapper.writeValueAsString(snapshot))
        .contentHash(org.praxisplatform.rules.digest.PraxisCanonicalJson.sha256(objectMapper.valueToTree(snapshot)))
        .compositionManifest(compositionManifestJson()).compositionDigest(compositionDigest()).publishedBy("release-manager")
        .publishedAt(Instant.parse(snapshot.publishedAtUtc())).build();
  }

  @Test
  void versionCatalogIsBoundedNewestFirstAndMarksTheActiveSnapshot() throws Exception {
    UUID newestId = UUID.randomUUID();
    DomainRuleSnapshot newest = catalogSnapshot(
        newestId, "snapshot-2", 2, 2, "publisher-b",
        Instant.parse("2026-07-14T10:00:00Z"));
    DomainRuleSnapshot previous = catalogSnapshot(
        UUID.randomUUID(), "snapshot-1", 1, 1, "publisher-a",
        Instant.parse("2026-07-13T10:00:00Z"));
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(Optional.of(DomainRuleSnapshotHead.builder()
            .activeSnapshotId(newestId)
            .build()));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        newestId, "tenant-a", "prod", "extraordinary-grant"))
        .thenReturn(Optional.of(newest));
    when(snapshotRepository.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        eq("tenant-a"), eq("prod"), eq("extraordinary-grant"), any(Pageable.class)))
        .thenReturn(List.of(newest, previous));

    var versions = service.listVersions("tenant-a", "prod", "extraordinary-grant", 25);

    assertThat(versions).extracting(value -> value.snapshotKey())
        .containsExactly("snapshot-2", "snapshot-1");
    assertThat(versions).extracting(value -> value.active())
        .containsExactly(true, false);
    assertThat(versions).extracting(value -> value.governanceState())
        .containsOnly("REPUBLICATION_REQUIRED");
    assertThat(versions).extracting(value -> value.availableAction())
        .containsExactly("UNAVAILABLE", "UNAVAILABLE");
  }

  @Test
  void versionCatalogOffersOnlyReadyDirectionsAndKeepsBlockedInactiveHistoryUnavailable() throws Exception {
    DomainRuleSnapshot previous = storedSnapshot(publishedSnapshot());
    DomainRuleSnapshot active = catalogSnapshot(UUID.randomUUID(), "snapshot-2", 2, 2,
        "publisher-b", Instant.parse("2026-07-14T10:00:00Z"));
    DomainRuleSnapshot next = catalogSnapshot(UUID.randomUUID(), "snapshot-3", 3, 3,
        "publisher-c", Instant.parse("2026-07-15T10:00:00Z"));
    active.setCompositionManifest(compositionManifestJson());
    active.setCompositionDigest(compositionDigest());
    next.setCompositionManifest(compositionManifestJson());
    next.setCompositionDigest(compositionDigest());
    DomainRuleSnapshot incompatible = storedSnapshot(withCompatibility(publishedSnapshot(), incompatibleTuples().findFirst().orElseThrow()));
    DomainRuleSnapshot preManifest = catalogSnapshot(UUID.randomUUID(), "snapshot-legacy", 5, 5,
        "publisher-e", Instant.parse("2026-07-17T10:00:00Z"));
    stubSelectedSnapshot(active);
    when(snapshotRepository.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        eq("tenant-a"), eq("prod"), eq("extraordinary-grant"), any(Pageable.class)))
        .thenReturn(List.of(preManifest, incompatible, next, active, previous));

    var versions = service.listVersions("tenant-a", "prod", "extraordinary-grant", 25);

    assertThat(versions).extracting(value -> value.governanceState())
        .containsExactly("REPUBLICATION_REQUIRED", "RUNTIME_INCOMPATIBLE", "READY", "READY", "READY");
    assertThat(versions).extracting(value -> value.availableAction())
        .containsExactly("UNAVAILABLE", "UNAVAILABLE", "ACTIVATE", "ACTIVE", "ROLLBACK");
    assertThat(versions).extracting(value -> value.active()).containsExactly(false, false, false, true, false);
  }

  @Test
  void versionCatalogRejectsUnboundedRequests() {
    assertThatThrownBy(() -> service.listVersions(
        "tenant-a", "prod", "extraordinary-grant", 101))
        .isInstanceOfSatisfying(
            DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void publicationRejectsPublisherThatApprovedComposition() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<UUID> ids = List.of(firstId, secondId);
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(definitionRepository.findAllById(ids)).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    DomainRuleSnapshotPublicationRequest approved = publicationRequest(ruleSet(), ids);
    DomainRuleSnapshotPublicationRequest conflicted = new DomainRuleSnapshotPublicationRequest(
        approved.ruleSet(), ids, approved.ownerServiceKey(), approved.requiredHostContractVersion(),
        approved.validFromUtc(), approved.validUntilUtc(), approved.compositionDigest());

    assertThatThrownBy(() -> service.publish(
        conflicted, "tenant-a", "prod", null, "*", "composition-approver-a"))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class)
        .hasMessageContaining("publisher cannot approve");
  }

  @Test
  void manifestRejectsJavaCoordinateOutsideHostAdmissionCatalog() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<UUID> ids = List.of(firstId, secondId);
    when(definitionRepository.findAllById(ids)).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));

    assertThatThrownBy(() -> service.prepareCompositionManifest(
        new DomainRuleCompositionManifestRequest(
            javaRuleSet("9.9.9"), ids, "quickstart", "quickstart/1.0",
            "2026-07-13T20:00:00Z", null),
        "tenant-a", "prod"))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(exception.getMessage()).contains("PLAN_COMPATIBILITY_INVALID");
            });
  }

  @Test
  void manifestRejectsDefinitionWithoutApprovalForItsCurrentContentHash() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<UUID> ids = List.of(firstId, secondId);
    DomainRuleDefinition first = approvedDefinition(firstId, "grant:eligibility", "approver-a");
    DomainRuleDefinition second = approvedDefinition(secondId, "grant:amount", "approver-b");
    definitionApprovers.remove(secondId);
    when(definitionRepository.findAllById(ids)).thenReturn(List.of(first, second));

    assertThatThrownBy(() -> service.prepareCompositionManifest(
        new DomainRuleCompositionManifestRequest(
            ruleSet(), ids, "quickstart", "quickstart/1.0",
            "2026-07-13T20:00:00Z", null),
        "tenant-a", "prod"))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(exception.getMessage()).contains("exact content hash");
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void compositionManifestBindsReviewedSnapshotEvidenceByDigest() {
    ObjectProvider<DomainRuleDefinitionEvidenceGateService> provider = mock(ObjectProvider.class);
    DomainRuleDefinitionEvidenceGateService gate = mock(DomainRuleDefinitionEvidenceGateService.class);
    when(provider.getIfAvailable()).thenReturn(gate);
    service = new DomainRuleSnapshotService(
        definitionRepository,  snapshotRepository,  headRepository,  eventRepository,
        compositionApprovalRepository,  definitionApprovalRepository,
        new DomainRuleDefinitionFingerprint(objectMapper),  objectMapper,  implementationCatalog,
        mock(DomainRuleSnapshotActivationGate.class),  provider,  scopeLock, entityRefresh);
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<UUID> ids = List.of(firstId, secondId);
    DomainRuleDefinition first = approvedDefinition(firstId, "grant:eligibility", "approver-a");
    DomainRuleDefinition second = approvedDefinition(secondId, "grant:amount", "approver-b");
    when(definitionRepository.findAllById(ids)).thenReturn(List.of(first, second));
    when(gate.decision(eq("SNAPSHOT"), any(), any())).thenAnswer(invocation -> {
      DomainRuleDefinition definition = invocation.getArgument(1);
      return new DomainRuleDefinitionEvidenceDecision(
          definition.getId(), "SNAPSHOT", true, UUID.randomUUID(), UUID.randomUUID(),
          "A".repeat(64), 7L, "B".repeat(64), List.of());
    });
    when(gate.decision(eq("ACTIVATE"), any(), any())).thenAnswer(invocation -> {
      DomainRuleDefinition definition = invocation.getArgument(1);
      return new DomainRuleDefinitionEvidenceDecision(
          definition.getId(), "ACTIVATE", false, null, null, null, null, null, List.of());
    });

    var response = service.prepareCompositionManifest(new DomainRuleCompositionManifestRequest(
        ruleSet(), ids, "quickstart", "quickstart/1.0",
        "2026-07-13T20:00:00Z", null), "tenant-a", "prod");

    assertThat(response.compositionContractVersion()).isEqualTo("praxis-rule-composition/2");
    assertThat(response.manifest().path("testEvidence")).hasSize(2);
    assertThat(response.manifest().path("testEvidence").get(0).path("satisfied").asBoolean()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void compositionFailsClosedWhenRequiredSnapshotEvidenceIsBlocked() {
    ObjectProvider<DomainRuleDefinitionEvidenceGateService> provider = mock(ObjectProvider.class);
    DomainRuleDefinitionEvidenceGateService gate = mock(DomainRuleDefinitionEvidenceGateService.class);
    when(provider.getIfAvailable()).thenReturn(gate);
    service = new DomainRuleSnapshotService(
        definitionRepository,  snapshotRepository,  headRepository,  eventRepository,
        compositionApprovalRepository,  definitionApprovalRepository,
        new DomainRuleDefinitionFingerprint(objectMapper),  objectMapper,  implementationCatalog,
        mock(DomainRuleSnapshotActivationGate.class),  provider,  scopeLock, entityRefresh);
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<UUID> ids = List.of(firstId, secondId);
    when(definitionRepository.findAllById(ids)).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    when(gate.decision(eq("SNAPSHOT"), any(), any())).thenAnswer(invocation -> {
      DomainRuleDefinition definition = invocation.getArgument(1);
      return new DomainRuleDefinitionEvidenceDecision(
          definition.getId(), "SNAPSHOT", true, null, null, null, null, null,
          List.of(new org.praxisplatform.config.dto.DomainRuleWorkspaceBlocker(
              "BOUND_TEST_RUN_REQUIRED", "SNAPSHOT", "Reviewed Test Run required")));
    });

    assertThatThrownBy(() -> service.prepareCompositionManifest(
        new DomainRuleCompositionManifestRequest(
            ruleSet(), ids, "quickstart", "quickstart/1.0",
            "2026-07-13T20:00:00Z", null), "tenant-a", "prod"))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo("TEST_EVIDENCE_BLOCKED");
              assertThat(exception.blockers()).singleElement().satisfies(blocker -> {
                assertThat(blocker.code()).isEqualTo("BOUND_TEST_RUN_REQUIRED");
                assertThat(blocker.stage()).isEqualTo("SNAPSHOT");
                assertThat(ids).contains(blocker.definitionId());
              });
            });
  }

  private DomainRuleSnapshotPublicationRequest publicationRequest(
      RuleSetDefinition definition, List<UUID> sourceIds) {
    var manifest = service.prepareCompositionManifest(new DomainRuleCompositionManifestRequest(
        definition, sourceIds, "quickstart", "quickstart/1.0",
        "2026-07-13T20:00:00Z", null), "tenant-a", "prod");
    Instant decidedAt = Instant.now().minusSeconds(1);
    when(compositionApprovalRepository
        .findByTenantIdAndEnvironmentAndCompositionDigestOrderByApprovedAtAsc(
            "tenant-a", "prod", manifest.compositionDigest()))
        .thenReturn(List.of(
            compositionApproval("composition-approver-a", manifest.compositionDigest(), decidedAt),
            compositionApproval("composition-approver-b", manifest.compositionDigest(), decidedAt)));
    return new DomainRuleSnapshotPublicationRequest(
        definition, sourceIds, "quickstart", "quickstart/1.0",
        "2026-07-13T20:00:00Z", null, manifest.compositionDigest());
  }

  private DomainRuleSnapshotPublicationRequest unapprovedRequest(
      RuleSetDefinition definition, List<UUID> sourceIds) {
    return new DomainRuleSnapshotPublicationRequest(
        definition, sourceIds, "quickstart", "quickstart/1.0",
        "2026-07-13T20:00:00Z", null, "0".repeat(64));
  }

  @Test
  void compositionApprovalIsServerTimedIamBoundAndIdempotentPerActor() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<UUID> ids = List.of(firstId, secondId);
    when(definitionRepository.findAllById(ids)).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    DomainRuleCompositionManifestRequest request = new DomainRuleCompositionManifestRequest(
        ruleSet(), ids, "quickstart", "quickstart/1.0",
        "2026-07-13T20:00:00Z", null);
    when(compositionApprovalRepository
        .findByTenantIdAndEnvironmentAndCompositionDigestAndActorRef(
            any(), any(), any(), any()))
        .thenReturn(Optional.empty())
        .thenAnswer(invocation -> Optional.of(compositionApproval(
            invocation.getArgument(3), invocation.getArgument(2), Instant.now())));

    var first = service.approveComposition(request, "tenant-a", "prod", "iam-approver-a");
    DomainRuleCompositionApproval stored = compositionApproval(
        first.actorRef(), first.evidenceHash(), Instant.parse(first.decidedAtUtc()));
    stored.setId(UUID.fromString(first.approvalKey()));
    when(compositionApprovalRepository
        .findByTenantIdAndEnvironmentAndCompositionDigestAndActorRef(
            "tenant-a", "prod", first.evidenceHash(), "iam-approver-a"))
        .thenReturn(Optional.of(stored));

    var repeated = service.approveComposition(request, "tenant-a", "prod", "iam-approver-a");

    assertThat(first.actorRef()).isEqualTo("iam-approver-a");
    assertThat(first.role()).isEqualTo("RULE_COMPOSITION_APPROVER");
    assertThat(first.evidenceHash()).matches("[A-F0-9]{64}");
    assertThat(repeated).isEqualTo(first);
    verify(compositionApprovalRepository, times(1)).insertIfAbsent(
        any(), any(), any(), any(), any(), any(), any());
  }

  private DomainRuleCompositionApproval compositionApproval(
      String actor, String digest, Instant approvedAt) {
    return DomainRuleCompositionApproval.builder()
        .id(UUID.randomUUID())
        .tenantId("tenant-a")
        .environment("prod")
        .compositionDigest(digest)
        .actorRef(actor)
        .role("RULE_COMPOSITION_APPROVER")
        .manifest("{}")
        .approvedAt(approvedAt)
        .build();
  }

  private DomainRuleSnapshot catalogSnapshot(
      UUID id,
      String snapshotKey,
      int ruleSetVersion,
      int publicationRevision,
      String publishedBy,
      Instant publishedAt) throws Exception {
    PublishedRuleSnapshot template = publishedSnapshot();
    PublishedRuleSnapshot snapshot = new PublishedRuleSnapshot(
        template.snapshotContractVersion(), snapshotKey, template.tenantId(), template.environment(),
        template.ownerServiceKey(), publicationRevision, publishedAt.toString(), null,
        template.requiredHostContractVersion(), template.validFromUtc(), template.validUntilUtc(),
        template.sources(), template.approvals(), ruleSet(ruleSetVersion));
    DomainRuleSnapshot stored = storedSnapshot(snapshot);
    stored.setId(id);
    stored.setPublishedBy(publishedBy);
    stored.setCompositionManifest(null);
    stored.setCompositionDigest(null);
    return stored;
  }

  private DomainRuleDefinition approvedDefinition(UUID id, String ruleKey, String approver) {
    DomainRuleDefinition definition = DomainRuleDefinition.builder()
        .id(id)
        .tenantId("tenant-a")
        .environment("prod")
        .ruleKey(ruleKey)
        .version(1)
        .status("approved")
        .definition("{\"kind\":\"policy\",\"key\":\"" + ruleKey + "\"}")
        .parameters("{}")
        .governance("{\"classification\":\"corporate\"}")
        .createdByType("authenticated")
        .createdBy("definition-author")
        .approvedBy(approver)
        .approvedAt(Instant.parse("2026-07-13T19:00:00Z"))
        .build();
    definitionApprovers.put(id, approver);
    return definition;
  }

  private RuleSetDefinition ruleSet() {
    return ruleSet(1);
  }

  private RuleSetDefinition ruleSet(int version) {
    var expression = objectMapper.createObjectNode();
    expression.put("var", "request.eligible");
    return new RuleSetDefinition(
        new RuleSetRef("benefits", "extraordinary-grants", "extraordinary-grant", "evaluate", version),
        List.of("request"),
        List.of(new DecisionSlot(
            "eligibility", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
            OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null)),
        List.of(new DecisionBinding(
            "eligibility", "eligibility", DecisionSource.PRODUCT, null,
            RuleExecutorRef.jsonLogic(expression), List.of(), 10, true,
            RuleDecision.DENY, "NOT_ELIGIBLE", List.of("request.eligible"), null)),
        RuleRuntimeCompatibility.current(),
        RuleFailPolicy.FAIL_CLOSED);
  }

  private RuleSetDefinition javaRuleSet() {
    return javaRuleSet("1.0.0");
  }

  private RuleSetDefinition payrollReactiveDeterminations(int version) {
    var netSalary = objectMapper.createObjectNode();
    netSalary.put("var", "payroll.salarioBruto");
    var paymentDate = objectMapper.createObjectNode();
    paymentDate.put("var", "payroll.ano");
    String netKey = "human-resources.payroll.net-salary";
    String paymentKey = "human-resources.payroll.payment-date";
    return new RuleSetDefinition(
        new RuleSetRef(
            "human-resources", "human-resources.payroll",
            "human-resources.payroll.reactive-determinations",
            "determine-payroll-derived-fields", version),
        List.of("payroll"),
        List.of(
            new DecisionSlot(
                netKey, DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null),
            new DecisionSlot(
                paymentKey, DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null)),
        List.of(
            new DecisionBinding(
                netKey, netKey, DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(netSalary), List.of(), 10, true,
                RuleDecision.INCONCLUSIVE, "NET_SALARY_UNAVAILABLE",
                List.of("payroll.salarioBruto", "payroll.totalDescontos"), null),
            new DecisionBinding(
                paymentKey, paymentKey, DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(paymentDate), List.of(netKey), 20, true,
                RuleDecision.INCONCLUSIVE, "PAYMENT_DATE_UNAVAILABLE",
                List.of("payroll.ano", "payroll.mes", "payroll.salarioLiquido"), null)),
        RuleRuntimeCompatibility.current(),
        RuleFailPolicy.FAIL_CLOSED);
  }

  private RuleSetDefinition javaRuleSet(String implementationVersion) {
    var eligibilityExpression = objectMapper.createObjectNode();
    eligibilityExpression.put("var", "request.eligible");
    return new RuleSetDefinition(
        new RuleSetRef("benefits", "extraordinary-grants", "extraordinary-grant", "evaluate", 1),
        List.of("request"),
        List.of(
            new DecisionSlot(
                "eligibility", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null),
            new DecisionSlot(
                "calculation", DecisionStage.TRANSFORMATION_INTENT, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null)),
        List.of(
            new DecisionBinding(
                "eligibility", "eligibility", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(eligibilityExpression), List.of(), 10, true,
                RuleDecision.DENY, "NOT_ELIGIBLE", List.of("request.eligible"), null),
            new DecisionBinding(
                "calculation", "calculation", DecisionSource.PRODUCT, null,
                RuleExecutorRef.java("benefits:amount", implementationVersion), List.of("eligibility"), 10, true,
                null, null, List.of("request.amount"), null)),
        RuleRuntimeCompatibility.current(),
        RuleFailPolicy.FAIL_CLOSED);
  }

  private RuleSetDefinition customerJavaRuleSet() {
    return new RuleSetDefinition(
        new RuleSetRef("benefits", "extraordinary-grants", "extraordinary-grant", "evaluate", 1),
        List.of("request"),
        List.of(new DecisionSlot(
            "customer.eligibility", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
            OverridePolicy.REPLACEABLE, DecisionAggregationPolicy.SINGLE_RESULT, null)),
        List.of(new DecisionBinding(
            "customer.eligibility", "customer.eligibility", DecisionSource.CUSTOMER,
            CompositionPolicy.REPLACE_EXACT,
            RuleExecutorRef.java("customer:benefit-eligibility", "1.0.0"),
            List.of(), 10, true, null, null, List.of(), null)),
        RuleRuntimeCompatibility.current(),
        RuleFailPolicy.FAIL_CLOSED);
  }

  private void prepareFirstPublication(UUID firstId, UUID secondId) {
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
  }

  private DomainRuleSnapshotPublicationRequest publication(
      RuleSetDefinition definition,
      UUID firstId,
      UUID secondId) {
    return publicationRequest(definition, List.of(firstId, secondId));
  }

  private PublishedRuleSnapshot publishedSnapshot() {
    return publishedSnapshot("2026-07-13T20:00:00Z", null);
  }

  private PublishedRuleSnapshot publishedSnapshot(String validFromUtc, String validUntilUtc) {
    return publishedSnapshot(validFromUtc, validUntilUtc, ruleSet());
  }

  private PublishedRuleSnapshot publishedSnapshot(
      String validFromUtc, String validUntilUtc, RuleSetDefinition definition) {
    String hash = "B".repeat(64);
    return new PublishedRuleSnapshot(
        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
        UUID.randomUUID().toString(),
        "tenant-a",
        "prod",
        "quickstart",
        1,
        "2026-07-13T20:00:00Z",
        null,
        "quickstart/1.0",
        validFromUtc,
        validUntilUtc,
        List.of(new RuleSnapshotSource("definition-a", "grant:eligibility", 1, hash)),
        List.of(
            new RuleSnapshotApproval("approval-a", "RULE_DEFINITION_APPROVER", "approver-a",
                "2026-07-13T19:00:00Z", hash),
            new RuleSnapshotApproval("composition-a", "RULE_COMPOSITION_APPROVER", "composition-a",
                "2026-07-13T19:30:00Z", compositionDigest()),
            new RuleSnapshotApproval("composition-b", "RULE_COMPOSITION_APPROVER", "composition-b",
                "2026-07-13T19:30:00Z", compositionDigest())),
        definition);
  }

  private PublishedRuleSnapshot publishedSnapshotWithRevision(int publicationRevision) {
    PublishedRuleSnapshot snapshot = publishedSnapshot();
    return new PublishedRuleSnapshot(
        snapshot.snapshotContractVersion(), snapshot.snapshotKey(), snapshot.tenantId(),
        snapshot.environment(), snapshot.ownerServiceKey(), publicationRevision,
        snapshot.publishedAtUtc(), snapshot.supersedesSnapshotKey(),
        snapshot.requiredHostContractVersion(), snapshot.validFromUtc(), snapshot.validUntilUtc(),
        snapshot.sources(), snapshot.approvals(), snapshot.ruleSet());
  }

  private String compositionManifestJson() {
    return "{\"test\":true}";
  }

  private String compositionDigest() {
    try {
      return org.praxisplatform.rules.digest.PraxisCanonicalJson.sha256(
          objectMapper.readTree(compositionManifestJson()));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private DomainRuleSnapshotHead rollbackHead(UUID activeId, UUID etag) {
    return DomainRuleSnapshotHead.builder()
        .id(UUID.randomUUID())
        .tenantId("tenant-a")
        .environment("prod")
        .ruleSetKey("extraordinary-grant")
        .activeSnapshotId(activeId)
        .activationRevision(2L)
        .headEtag(etag)
        .updatedAt(Instant.now())
        .rowVersion(0L)
        .build();
  }

  private String snapshotHash(PublishedRuleSnapshot snapshot) {
    return new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.empty())
        .compile(snapshot, snapshot.requiredHostContractVersion())
        .snapshotContentHash();
  }

  private org.mockito.ArgumentMatcher<org.praxisplatform.config.domain.DomainRuleSnapshotEvent>
      argThatEvent(String type, long revision) {
    return event -> event != null
        && type.equals(event.getEventType())
        && revision == event.getActivationRevision();
  }
}
