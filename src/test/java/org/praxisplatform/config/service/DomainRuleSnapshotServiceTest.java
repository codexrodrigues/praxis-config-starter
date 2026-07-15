package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.dto.DomainRuleSnapshotPublicationRequest;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
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
import org.springframework.http.HttpStatus;

@Tag("unit")
class DomainRuleSnapshotServiceTest {
  private final DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
  private final DomainRuleSnapshotRepository snapshotRepository = mock(DomainRuleSnapshotRepository.class);
  private final DomainRuleSnapshotHeadRepository headRepository = mock(DomainRuleSnapshotHeadRepository.class);
  private final DomainRuleSnapshotEventRepository eventRepository = mock(DomainRuleSnapshotEventRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DomainRuleImplementationCatalog implementationCatalog = mock(
      DomainRuleImplementationCatalog.class);
  private DomainRuleSnapshotService service;

  @BeforeEach
  void setUp() {
    service = new DomainRuleSnapshotService(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        objectMapper,
        implementationCatalog);
    when(implementationCatalog.allowedImplementations(any())).thenReturn(List.of(
        new RuleImplementationRef("benefits:amount", "1.0.0")));
    when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(headRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void firstPublicationCompilesGovernedSnapshotAndCreatesOpaqueHead() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    when(headRepository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(snapshotRepository.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(List.of());
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));

    var response = service.publish(
        new DomainRuleSnapshotPublicationRequest(
            ruleSet(),
            List.of(firstId, secondId),
            "quickstart",
            "quickstart/1.0",
            "2026-07-13T20:00:00Z",
            null,
            "release-manager"),
        "tenant-a",
        "prod",
        null,
        "*");

    assertThat(response.activationType()).isEqualTo("PUBLISHED");
    assertThat(response.activationRevision()).isEqualTo(1);
    assertThat(response.snapshot().publicationRevision()).isEqualTo(1);
    assertThat(response.snapshot().sources()).hasSize(2);
    assertThat(response.snapshot().approvals()).extracting(RuleSnapshotApproval::actorRef)
        .containsExactlyInAnyOrder("approver-a", "approver-b");
    assertThat(response.snapshotContentHash()).matches("[A-F0-9]{64}");
    assertThat(response.headEtag()).isNotEqualTo(response.snapshotContentHash());
    verify(snapshotRepository).save(any(DomainRuleSnapshot.class));
    verify(eventRepository).save(argThat(argThatEvent("PUBLISHED", 1L)));
  }

  @Test
  void publishedJavaExecutorSurvivesPersistenceRoundTrip() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    when(headRepository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(snapshotRepository.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(List.of());
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));

    service.publish(
        new DomainRuleSnapshotPublicationRequest(
            javaRuleSet(),
            List.of(firstId, secondId),
            "quickstart",
            "quickstart/1.0",
            "2026-07-13T20:00:00Z",
            null,
            "release-manager"),
        "tenant-a",
        "prod",
        null,
        "*");

    ArgumentCaptor<DomainRuleSnapshot> snapshotCaptor = ArgumentCaptor.forClass(DomainRuleSnapshot.class);
    ArgumentCaptor<DomainRuleSnapshotHead> headCaptor = ArgumentCaptor.forClass(DomainRuleSnapshotHead.class);
    verify(snapshotRepository).save(snapshotCaptor.capture());
    verify(headRepository).saveAndFlush(headCaptor.capture());
    DomainRuleSnapshot stored = snapshotCaptor.getValue();
    DomainRuleSnapshotHead head = headCaptor.getValue();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(snapshotRepository.findById(stored.getId())).thenReturn(Optional.of(stored));

    var active = service.findActive("tenant-a", "prod", "extraordinary-grant").orElseThrow();

    assertThat(stored.getSnapshotPayload()).contains("\"expression\":null");
    assertThat(active.snapshot().ruleSet().bindings().getFirst().executor().expression()).isNull();
    verify(implementationCatalog).allowedImplementations(new DomainRuleImplementationScope(
        "tenant-a", "prod", "quickstart"));
  }

  @Test
  void javaPublicationFailsClosedWhenHostProvidesNoExternalCatalog() {
    service = new DomainRuleSnapshotService(
        definitionRepository, snapshotRepository, headRepository, eventRepository, objectMapper);
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

    verify(snapshotRepository, never()).save(any());
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

    assertThat(response.activationType()).isEqualTo("PUBLISHED");
    verify(snapshotRepository).save(any(DomainRuleSnapshot.class));
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

    verify(snapshotRepository, never()).save(any());
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
    when(headRepository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));

    assertThatThrownBy(() -> service.publish(
        new DomainRuleSnapshotPublicationRequest(
            ruleSet(), List.of(UUID.randomUUID()), "quickstart", "quickstart/1.0",
            "2026-07-13T20:00:00Z", null, "release-manager"),
        "tenant-a", "prod", "\"stale\"", null))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));

    verify(snapshotRepository, never()).save(any());
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
    when(headRepository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
    when(snapshotRepository.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(List.of(
            DomainRuleSnapshot.builder().ruleSetVersion(1).publicationRevision(1).build()));

    assertThatThrownBy(() -> service.publish(
        new DomainRuleSnapshotPublicationRequest(
            ruleSet(), List.of(firstId, secondId), "quickstart", "quickstart/1.0",
            "2026-07-13T20:00:00Z", null, "release-manager"),
        "tenant-a", "prod", "\"" + etag + "\"", null))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));

    verify(snapshotRepository, never()).save(any());
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
        .contentHash("A".repeat(64))
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
    when(headRepository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.of(head));

    var response = service.rollback(
        target.getSnapshotKey(), "operator-a", "tenant-a", "prod", "\"" + oldEtag + "\"");

    assertThat(response.activationType()).isEqualTo("ROLLED_BACK");
    assertThat(response.activationRevision()).isEqualTo(3);
    assertThat(response.headEtag()).isNotEqualTo(oldEtag.toString());
    assertThat(response.snapshotContentHash()).isEqualTo("A".repeat(64));
    verify(snapshotRepository, never()).save(any());
    verify(eventRepository).save(argThat(argThatEvent("ROLLED_BACK", 3L)));
  }

  private DomainRuleDefinition approvedDefinition(UUID id, String ruleKey, String approver) {
    return DomainRuleDefinition.builder()
        .id(id)
        .tenantId("tenant-a")
        .environment("prod")
        .ruleKey(ruleKey)
        .version(1)
        .status("approved")
        .definition("{\"kind\":\"policy\",\"key\":\"" + ruleKey + "\"}")
        .parameters("{}")
        .governance("{\"classification\":\"corporate\"}")
        .approvedBy(approver)
        .approvedAt(Instant.parse("2026-07-13T19:00:00Z"))
        .build();
  }

  private RuleSetDefinition ruleSet() {
    var expression = objectMapper.createObjectNode();
    expression.put("var", "request.eligible");
    return new RuleSetDefinition(
        new RuleSetRef("benefits", "extraordinary-grants", "extraordinary-grant", "evaluate", 1),
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
  }

  private RuleSetDefinition javaRuleSet() {
    return new RuleSetDefinition(
        new RuleSetRef("benefits", "extraordinary-grants", "extraordinary-grant", "evaluate", 1),
        List.of("request"),
        List.of(new DecisionSlot(
            "calculation", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
            OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT)),
        List.of(new DecisionBinding(
            "calculation", "calculation", DecisionSource.PRODUCT, null,
            RuleExecutorRef.java("benefits:amount", "1.0.0"), List.of(), 10, true,
            null, null, List.of("request.amount"))),
        RuleRuntimeCompatibility.current(),
        RuleFailPolicy.FAIL_CLOSED);
  }

  private RuleSetDefinition customerJavaRuleSet() {
    return new RuleSetDefinition(
        new RuleSetRef("benefits", "extraordinary-grants", "extraordinary-grant", "evaluate", 1),
        List.of("request"),
        List.of(new DecisionSlot(
            "customer.eligibility", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
            OverridePolicy.REPLACEABLE, DecisionAggregationPolicy.SINGLE_RESULT)),
        List.of(new DecisionBinding(
            "customer.eligibility", "customer.eligibility", DecisionSource.CUSTOMER,
            CompositionPolicy.REPLACE_EXACT,
            RuleExecutorRef.java("customer:benefit-eligibility", "1.0.0"),
            List.of(), 10, true, null, null, List.of())),
        RuleRuntimeCompatibility.current(),
        RuleFailPolicy.FAIL_CLOSED);
  }

  private void prepareFirstPublication(UUID firstId, UUID secondId) {
    when(headRepository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(Optional.empty());
    when(snapshotRepository.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
        "tenant-a", "prod", "extraordinary-grant")).thenReturn(List.of());
    when(definitionRepository.findAllById(List.of(firstId, secondId))).thenReturn(List.of(
        approvedDefinition(firstId, "grant:eligibility", "approver-a"),
        approvedDefinition(secondId, "grant:amount", "approver-b")));
  }

  private DomainRuleSnapshotPublicationRequest publication(
      RuleSetDefinition definition,
      UUID firstId,
      UUID secondId) {
    return new DomainRuleSnapshotPublicationRequest(
        definition,
        List.of(firstId, secondId),
        "quickstart",
        "quickstart/1.0",
        "2026-07-13T20:00:00Z",
        null,
        "release-manager");
  }

  private PublishedRuleSnapshot publishedSnapshot() {
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
        "2026-07-13T20:00:00Z",
        null,
        List.of(new RuleSnapshotSource("definition-a", "grant:eligibility", 1, hash)),
        List.of(new RuleSnapshotApproval(
            "approval-a", "RULE_DEFINITION_APPROVER", "approver-a",
            "2026-07-13T19:00:00Z", hash)),
        ruleSet());
  }

  private org.mockito.ArgumentMatcher<org.praxisplatform.config.domain.DomainRuleSnapshotEvent>
      argThatEvent(String type, long revision) {
    return event -> event != null
        && type.equals(event.getEventType())
        && revision == event.getActivationRevision();
  }
}
