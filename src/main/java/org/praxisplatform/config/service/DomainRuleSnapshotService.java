package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotEvent;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotPublicationRequest;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.plan.RulePlanException;
import org.praxisplatform.rules.snapshot.CompiledRuleSnapshot;
import org.praxisplatform.rules.snapshot.PraxisRuleSnapshotCompiler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/** Governed publication, activation and rollback of immutable RuleSet snapshots. */
@RequiredArgsConstructor
public class DomainRuleSnapshotService implements DomainRuleSnapshotReader {
  private static final Set<String> PUBLISHABLE_STATUSES = Set.of("approved", "active");
  private static final int MINIMUM_DISTINCT_APPROVERS = 2;

  private final DomainRuleDefinitionRepository definitionRepository;
  private final DomainRuleSnapshotRepository snapshotRepository;
  private final DomainRuleSnapshotHeadRepository headRepository;
  private final DomainRuleSnapshotEventRepository eventRepository;
  private final ObjectMapper objectMapper;
  private final DomainRuleImplementationCatalog implementationCatalog;

  /**
   * Source-compatible constructor with a fail-closed Java implementation catalog.
   *
   * <p>Hosts that publish Java-backed RuleSets must use auto-configuration or the complete
   * constructor and provide an external {@link DomainRuleImplementationCatalog}.</p>
   */
  public DomainRuleSnapshotService(
      DomainRuleDefinitionRepository definitionRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotEventRepository eventRepository,
      ObjectMapper objectMapper) {
    this(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        objectMapper,
        DomainRuleImplementationCatalog.denyAll());
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleSnapshotActivationResponse publish(
      DomainRuleSnapshotPublicationRequest request,
      String tenantId,
      String environment,
      String ifMatch,
      String ifNoneMatch) {
    requireRequest(request);
    String tenant = requireText(tenantId, "X-Tenant-ID");
    String env = requireText(environment, "X-Env");
    String ruleSetKey = request.ruleSet().ref().ruleSetKey();
    Optional<DomainRuleSnapshotHead> currentHead =
        headRepository.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(tenant, env, ruleSetKey);
    verifyPublicationPrecondition(currentHead, ifMatch, ifNoneMatch);

    List<DomainRuleDefinition> sources = resolveSources(request.sourceDefinitionIds(), tenant, env);
    List<RuleSnapshotSource> provenance = new ArrayList<>();
    List<RuleSnapshotApproval> approvals = new ArrayList<>();
    Set<String> approvers = new LinkedHashSet<>();
    for (DomainRuleDefinition source : sources) {
      String sourceHash = sourceHash(source);
      provenance.add(new RuleSnapshotSource(
          source.getId().toString(), source.getRuleKey(), source.getVersion(), sourceHash));
      approvers.add(source.getApprovedBy().trim());
      approvals.add(new RuleSnapshotApproval(
          source.getId() + ":approval",
          "RULE_DEFINITION_APPROVER",
          source.getApprovedBy().trim(),
          source.getApprovedAt().toString(),
          sourceHash));
    }
    if (approvers.size() < MINIMUM_DISTINCT_APPROVERS) {
      throw badRequest("Snapshot publication requires at least two distinct rule approvers");
    }

    List<DomainRuleSnapshot> history = snapshotRepository
        .findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(tenant, env, ruleSetKey);
    if (history.stream().anyMatch(stored ->
        stored.getRuleSetVersion().equals(request.ruleSet().ref().version()))) {
      throw conflict("RuleSet versions are immutable; publish a new version or reactivate the existing snapshot");
    }
    int publicationRevision = history.isEmpty() ? 1 : history.get(0).getPublicationRevision() + 1;
    DomainRuleSnapshot previous = history.isEmpty() ? null : history.get(0);
    String ownerServiceKey = requireText(request.ownerServiceKey(), "ownerServiceKey");
    if (previous != null) {
      verifyStableRuleSetIdentity(readSnapshot(previous), request.ruleSet(), ownerServiceKey);
    }
    Instant now = Instant.now();
    String snapshotKey = UUID.randomUUID().toString();
    PublishedRuleSnapshot candidate = new PublishedRuleSnapshot(
        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
        snapshotKey,
        tenant,
        env,
        ownerServiceKey,
        publicationRevision,
        now.toString(),
        previous == null ? null : previous.getSnapshotKey(),
        requireText(request.requiredHostContractVersion(), "requiredHostContractVersion"),
        requireText(request.validFromUtc(), "validFromUtc"),
        normalize(request.validUntilUtc()),
        provenance,
        approvals,
        request.ruleSet());
    CompiledRuleSnapshot compiled = compileCandidate(candidate);

    DomainRuleSnapshot persisted = snapshotRepository.save(DomainRuleSnapshot.builder()
        .id(UUID.randomUUID())
        .tenantId(tenant)
        .environment(env)
        .snapshotKey(snapshotKey)
        .ruleSetKey(ruleSetKey)
        .ruleSetVersion(candidate.ruleSet().ref().version())
        .publicationRevision(publicationRevision)
        .snapshotPayload(writeSnapshot(compiled.snapshot()))
        .contentHash(compiled.snapshotContentHash())
        .supersedesSnapshotId(previous == null ? null : previous.getId())
        .publishedBy(requireText(request.publishedBy(), "publishedBy"))
        .publishedAt(now)
        .build());

    DomainRuleSnapshotHead head = currentHead.orElseGet(() -> DomainRuleSnapshotHead.builder()
        .id(UUID.randomUUID())
        .tenantId(tenant)
        .environment(env)
        .ruleSetKey(ruleSetKey)
        .activationRevision(0L)
        .rowVersion(0L)
        .build());
    UUID fromSnapshotId = head.getActiveSnapshotId();
    activate(head, persisted.getId(), now);
    try {
      headRepository.saveAndFlush(head);
    } catch (DataIntegrityViolationException exception) {
      throw preconditionFailed("RuleSet head changed while the publication was being committed");
    }
    appendEvent("PUBLISHED", head, fromSnapshotId, persisted.getId(), request.publishedBy(), now);
    return activation(compiled.snapshot(), compiled.snapshotContentHash(), head, "PUBLISHED");
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleSnapshotActivationResponse rollback(
      String snapshotKey,
      String activatedBy,
      String tenantId,
      String environment,
      String ifMatch) {
    String tenant = requireText(tenantId, "X-Tenant-ID");
    String env = requireText(environment, "X-Env");
    DomainRuleSnapshot target = snapshotRepository
        .findByTenantIdAndEnvironmentAndSnapshotKey(tenant, env, requireText(snapshotKey, "snapshotKey"))
        .orElseThrow(() -> notFound("Rule snapshot was not found in the requested scope"));
    DomainRuleSnapshotHead head = headRepository
        .findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(tenant, env, target.getRuleSetKey())
        .orElseThrow(() -> notFound("RuleSet head was not found"));
    verifyStrongMatch(ifMatch, head.getHeadEtag().toString());
    if (target.getId().equals(head.getActiveSnapshotId())) {
      throw conflict("The requested snapshot is already active");
    }
    UUID fromSnapshotId = head.getActiveSnapshotId();
    Instant now = Instant.now();
    activate(head, target.getId(), now);
    headRepository.save(head);
    appendEvent("ROLLED_BACK", head, fromSnapshotId, target.getId(), activatedBy, now);
    PublishedRuleSnapshot snapshot = readSnapshot(target);
    return activation(snapshot, target.getContentHash(), head, "ROLLED_BACK");
  }

  @Override
  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRuleSnapshotActivationResponse> findActive(
      String tenantId, String environment, String ruleSetKey) {
    String tenant = requireText(tenantId, "X-Tenant-ID");
    String env = requireText(environment, "X-Env");
    return headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
            tenant, env, requireText(ruleSetKey, "ruleSetKey"))
        .map(head -> {
          DomainRuleSnapshot stored = snapshotRepository.findById(head.getActiveSnapshotId())
              .orElseThrow(() -> new IllegalStateException("Snapshot head references missing immutable content"));
          return activation(readSnapshot(stored), stored.getContentHash(), head, "ACTIVE");
        });
  }

  @Override
  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRuleSnapshotStoredResponse> findSnapshot(
      String tenantId, String environment, String snapshotKey) {
    return snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
            requireText(tenantId, "X-Tenant-ID"),
            requireText(environment, "X-Env"),
            requireText(snapshotKey, "snapshotKey"))
        .map(stored -> new DomainRuleSnapshotStoredResponse(readSnapshot(stored), stored.getContentHash()));
  }

  private void requireRequest(DomainRuleSnapshotPublicationRequest request) {
    if (request == null || request.ruleSet() == null) {
      throw badRequest("ruleSet is required");
    }
  }

  private List<DomainRuleDefinition> resolveSources(List<UUID> requestedIds, String tenant, String environment) {
    if (requestedIds == null || requestedIds.isEmpty() || new LinkedHashSet<>(requestedIds).size() != requestedIds.size()) {
      throw badRequest("sourceDefinitionIds must contain distinct governed definitions");
    }
    List<DomainRuleDefinition> sources = definitionRepository.findAllById(requestedIds);
    if (sources.size() != requestedIds.size()) {
      throw badRequest("Every sourceDefinitionId must resolve to a governed definition");
    }
    for (DomainRuleDefinition source : sources) {
      if (!tenant.equals(source.getTenantId()) || !environment.equals(source.getEnvironment())) {
        throw badRequest("Every source definition must belong to the snapshot tenant and environment");
      }
      if (!PUBLISHABLE_STATUSES.contains(source.getStatus())
          || source.getApprovedBy() == null || source.getApprovedBy().isBlank()
          || source.getApprovedAt() == null) {
        throw badRequest("Every source definition must be approved with actor and decision time evidence");
      }
    }
    return sources.stream().sorted(java.util.Comparator.comparing(DomainRuleDefinition::getId)).toList();
  }

  private String sourceHash(DomainRuleDefinition source) {
    try {
      ObjectNode content = objectMapper.createObjectNode();
      content.put("definitionId", source.getId().toString());
      content.put("definitionKey", source.getRuleKey());
      content.put("version", source.getVersion());
      content.set("definition", objectMapper.readTree(source.getDefinition()));
      content.set("parameters", objectMapper.readTree(source.getParameters()));
      content.set("condition", source.getCondition() == null ? objectMapper.nullNode() : objectMapper.readTree(source.getCondition()));
      content.set("governance", objectMapper.readTree(source.getGovernance()));
      return PraxisCanonicalJson.sha256(content);
    } catch (JsonProcessingException exception) {
      throw badRequest("A source definition contains invalid governed JSON");
    }
  }

  private CompiledRuleSnapshot compileCandidate(PublishedRuleSnapshot snapshot) {
    DomainRuleImplementationScope scope = new DomainRuleImplementationScope(
        snapshot.tenantId(), snapshot.environment(), snapshot.ownerServiceKey());
    var allowed = implementationCatalog.allowedImplementations(scope);
    if (allowed == null) {
      throw new IllegalStateException("DomainRuleImplementationCatalog must not return null");
    }
    try {
      RuleBindingExecutorRegistry registry = RuleBindingExecutorRegistry.planning(allowed);
      return new PraxisRuleSnapshotCompiler(registry)
          .compile(snapshot, snapshot.requiredHostContractVersion());
    } catch (RulePlanException exception) {
      throw badRequest("RuleSet publication failed [" + exception.getCode() + "]");
    }
  }

  private void verifyStableRuleSetIdentity(
      PublishedRuleSnapshot previous,
      RuleSetDefinition next,
      String ownerServiceKey) {
    var priorRef = previous.ruleSet().ref();
    var nextRef = next.ref();
    if (!previous.ownerServiceKey().equals(ownerServiceKey)
        || !priorRef.domainKey().equals(nextRef.domainKey())
        || !priorRef.boundedContextKey().equals(nextRef.boundedContextKey())
        || !priorRef.operationKey().equals(nextRef.operationKey())) {
      throw conflict("A RuleSet key cannot change its owner, domain, bounded context or operation identity");
    }
  }

  private void verifyPublicationPrecondition(
      Optional<DomainRuleSnapshotHead> currentHead, String ifMatch, String ifNoneMatch) {
    if (currentHead.isEmpty()) {
      HttpEntityTagCondition createCondition = parseCondition(ifNoneMatch);
      if (!createCondition.wildcard()) {
        throw preconditionRequired("Initial publication requires If-None-Match: *");
      }
      return;
    }
    verifyStrongMatch(ifMatch, currentHead.get().getHeadEtag().toString());
  }

  private void verifyStrongMatch(String ifMatch, String currentEtag) {
    HttpEntityTagCondition condition = parseCondition(ifMatch);
    if (condition.isEmpty()) {
      throw preconditionRequired("Mutation of an existing RuleSet head requires If-Match");
    }
    if (condition.wildcard() || !condition.matchesStrong(currentEtag)) {
      throw preconditionFailed("RuleSet head changed; reload the current head before retrying");
    }
  }

  private HttpEntityTagCondition parseCondition(String value) {
    try {
      return HttpEntityTagCondition.parse(value);
    } catch (IllegalArgumentException exception) {
      throw badRequest(exception.getMessage());
    }
  }

  private void activate(DomainRuleSnapshotHead head, UUID target, Instant now) {
    head.setActiveSnapshotId(target);
    head.setActivationRevision(head.getActivationRevision() + 1);
    head.setHeadEtag(UUID.randomUUID());
    head.setUpdatedAt(now);
  }

  private void appendEvent(
      String eventType,
      DomainRuleSnapshotHead head,
      UUID fromSnapshotId,
      UUID toSnapshotId,
      String actor,
      Instant now) {
    eventRepository.save(DomainRuleSnapshotEvent.builder()
        .id(UUID.randomUUID())
        .tenantId(head.getTenantId())
        .environment(head.getEnvironment())
        .ruleSetKey(head.getRuleSetKey())
        .eventType(eventType)
        .fromSnapshotId(fromSnapshotId)
        .toSnapshotId(toSnapshotId)
        .activationRevision(head.getActivationRevision())
        .headEtag(head.getHeadEtag())
        .actor(requireText(actor, eventType.equals("PUBLISHED") ? "publishedBy" : "activatedBy"))
        .createdAt(now)
        .build());
  }

  private DomainRuleSnapshotActivationResponse activation(
      PublishedRuleSnapshot snapshot,
      String contentHash,
      DomainRuleSnapshotHead head,
      String type) {
    return new DomainRuleSnapshotActivationResponse(
        snapshot, contentHash, head.getHeadEtag().toString(), head.getActivationRevision(), type);
  }

  private String writeSnapshot(PublishedRuleSnapshot snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot persist compiled RuleSet snapshot", exception);
    }
  }

  private PublishedRuleSnapshot readSnapshot(DomainRuleSnapshot stored) {
    try {
      return objectMapper.readValue(stored.getSnapshotPayload(), PublishedRuleSnapshot.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Persisted RuleSet snapshot is unreadable", exception);
    }
  }

  private String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw badRequest(field + " is required");
    }
    return value.trim();
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private DomainRuleSnapshotControlPlaneException badRequest(String message) {
    return new DomainRuleSnapshotControlPlaneException(HttpStatus.BAD_REQUEST, message);
  }

  private DomainRuleSnapshotControlPlaneException notFound(String message) {
    return new DomainRuleSnapshotControlPlaneException(HttpStatus.NOT_FOUND, message);
  }

  private DomainRuleSnapshotControlPlaneException conflict(String message) {
    return new DomainRuleSnapshotControlPlaneException(HttpStatus.CONFLICT, message);
  }

  private DomainRuleSnapshotControlPlaneException preconditionRequired(String message) {
    return new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_REQUIRED, message);
  }

  private DomainRuleSnapshotControlPlaneException preconditionFailed(String message) {
    return new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_FAILED, message);
  }
}
