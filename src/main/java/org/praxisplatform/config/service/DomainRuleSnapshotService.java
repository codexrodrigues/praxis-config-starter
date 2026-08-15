package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleCompositionApproval;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleDefinitionApproval;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotEvent;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.dto.DomainRuleCompositionApprovalResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestRequest;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotHeadStatusResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotPublicationRequest;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotVersionResponse;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.repository.DomainRuleCompositionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.plan.RulePlanException;
import org.praxisplatform.rules.snapshot.CompiledRuleSnapshot;
import org.praxisplatform.rules.snapshot.PraxisRuleSnapshotCompiler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

/** Governed publication, activation and rollback of immutable RuleSet snapshots. */
public class DomainRuleSnapshotService {
  private static final Set<String> PUBLISHABLE_STATUSES = Set.of("approved", "active");
  private static final int MINIMUM_DISTINCT_APPROVERS = 2;
  private static final String COMPOSITION_CONTRACT_VERSION = "praxis-rule-composition/2";

  private final DomainRuleDefinitionRepository definitionRepository;
  private final DomainRuleSnapshotRepository snapshotRepository;
  private final DomainRuleSnapshotHeadRepository headRepository;
  private final DomainRuleSnapshotEventRepository eventRepository;
  private final DomainRuleCompositionApprovalRepository compositionApprovalRepository;
  private final DomainRuleDefinitionApprovalRepository definitionApprovalRepository;
  private final DomainRuleDefinitionFingerprint definitionFingerprint;
  private final ObjectMapper objectMapper;
  private final DomainRuleImplementationCatalog implementationCatalog;
  private final DomainRuleSnapshotActivationGate activationGate;
  private final ObjectProvider<DomainRuleDefinitionEvidenceGateService> evidenceGate;

  public DomainRuleSnapshotService(
      DomainRuleDefinitionRepository definitionRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotEventRepository eventRepository,
      DomainRuleCompositionApprovalRepository compositionApprovalRepository,
      DomainRuleDefinitionApprovalRepository definitionApprovalRepository,
      DomainRuleDefinitionFingerprint definitionFingerprint,
      ObjectMapper objectMapper,
      DomainRuleImplementationCatalog implementationCatalog) {
    this(definitionRepository, snapshotRepository, headRepository, eventRepository,
        compositionApprovalRepository, definitionApprovalRepository, definitionFingerprint,
        objectMapper, implementationCatalog, DomainRuleSnapshotActivationGate.allowAll(), null);
  }

  public DomainRuleSnapshotService(
      DomainRuleDefinitionRepository definitionRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotEventRepository eventRepository,
      DomainRuleCompositionApprovalRepository compositionApprovalRepository,
      DomainRuleDefinitionApprovalRepository definitionApprovalRepository,
      DomainRuleDefinitionFingerprint definitionFingerprint,
      ObjectMapper objectMapper,
      DomainRuleImplementationCatalog implementationCatalog,
      DomainRuleSnapshotActivationGate activationGate) {
    this(definitionRepository, snapshotRepository, headRepository, eventRepository,
        compositionApprovalRepository, definitionApprovalRepository, definitionFingerprint,
        objectMapper, implementationCatalog, activationGate, null);
  }

  public DomainRuleSnapshotService(
      DomainRuleDefinitionRepository definitionRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotEventRepository eventRepository,
      DomainRuleCompositionApprovalRepository compositionApprovalRepository,
      DomainRuleDefinitionApprovalRepository definitionApprovalRepository,
      DomainRuleDefinitionFingerprint definitionFingerprint,
      ObjectMapper objectMapper,
      DomainRuleImplementationCatalog implementationCatalog,
      DomainRuleSnapshotActivationGate activationGate,
      ObjectProvider<DomainRuleDefinitionEvidenceGateService> evidenceGate) {
    this.definitionRepository = definitionRepository;
    this.snapshotRepository = snapshotRepository;
    this.headRepository = headRepository;
    this.eventRepository = eventRepository;
    this.compositionApprovalRepository = compositionApprovalRepository;
    this.definitionApprovalRepository = definitionApprovalRepository;
    this.definitionFingerprint = definitionFingerprint;
    this.objectMapper = objectMapper;
    this.implementationCatalog = implementationCatalog;
    this.activationGate = activationGate;
    this.evidenceGate = evidenceGate;
  }

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
      DomainRuleCompositionApprovalRepository compositionApprovalRepository,
      DomainRuleDefinitionApprovalRepository definitionApprovalRepository,
      DomainRuleDefinitionFingerprint definitionFingerprint,
      ObjectMapper objectMapper) {
    this(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        compositionApprovalRepository,
        definitionApprovalRepository,
        definitionFingerprint,
        objectMapper,
        DomainRuleImplementationCatalog.denyAll(),
        DomainRuleSnapshotActivationGate.allowAll(),
        null);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public DomainRuleCompositionManifestResponse prepareCompositionManifest(
      DomainRuleCompositionManifestRequest request, String tenantId, String environment) {
    if (request == null || request.ruleSet() == null) throw badRequest("ruleSet is required");
    String tenant = requireText(tenantId, "X-Tenant-ID");
    String env = requireText(environment, "X-Env");
    List<DomainRuleDefinition> sources = resolveSources(request.sourceDefinitionIds(), tenant, env);
    return prepareComposition(request.ruleSet(), sources, tenant, env,
        requireText(request.ownerServiceKey(), "ownerServiceKey"),
        requireText(request.requiredHostContractVersion(), "requiredHostContractVersion"),
        requireText(request.validFromUtc(), "validFromUtc"), normalize(request.validUntilUtc()))
        .manifest();
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleCompositionApprovalResponse approveComposition(
      DomainRuleCompositionManifestRequest request,
      String tenantId,
      String environment,
      String actorRef) {
    DomainRuleCompositionManifestResponse prepared =
        prepareCompositionManifest(request, tenantId, environment);
    String tenant = requireText(tenantId, "X-Tenant-ID");
    String env = requireText(environment, "X-Env");
    String actor = requireText(actorRef, "authenticated actor");
    Optional<DomainRuleCompositionApproval> existing = compositionApprovalRepository
        .findByTenantIdAndEnvironmentAndCompositionDigestAndActorRef(
            tenant, env, prepared.compositionDigest(), actor);
    if (existing.isPresent()) return approvalResponse(existing.get());
    UUID approvalId = UUID.randomUUID();
    Instant approvedAt = Instant.now();
    compositionApprovalRepository.insertIfAbsent(
        approvalId,
        tenant,
        env,
        prepared.compositionDigest(),
        actor,
        writeJson(prepared.manifest(), "Cannot persist composition approval manifest"),
        approvedAt);
    DomainRuleCompositionApproval approval = compositionApprovalRepository
        .findByTenantIdAndEnvironmentAndCompositionDigestAndActorRef(
            tenant, env, prepared.compositionDigest(), actor)
        .orElseThrow(() -> new IllegalStateException("Composition approval was not persisted"));
    return approvalResponse(approval);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleSnapshotActivationResponse publish(
      DomainRuleSnapshotPublicationRequest request,
      String tenantId,
      String environment,
      String ifMatch,
      String ifNoneMatch,
      String publishedBy) {
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
    for (DomainRuleDefinition source : sources) {
      String sourceHash = definitionFingerprint.sha256(source);
      DomainRuleDefinitionApproval sourceApproval = currentApproval(source, sourceHash);
      provenance.add(new RuleSnapshotSource(
          source.getId().toString(), source.getRuleKey(), source.getVersion(), sourceHash));
      approvals.add(new RuleSnapshotApproval(
          sourceApproval.getId().toString(),
          sourceApproval.getRole(),
          sourceApproval.getActorRef(),
          sourceApproval.getApprovedAt().toString(),
          sourceHash));
    }
    String ownerServiceKey = requireText(request.ownerServiceKey(), "ownerServiceKey");
    PreparedComposition composition = prepareComposition(
        request.ruleSet(), sources, tenant, env, ownerServiceKey,
        requireText(request.requiredHostContractVersion(), "requiredHostContractVersion"),
        requireText(request.validFromUtc(), "validFromUtc"), normalize(request.validUntilUtc()));
    DomainRuleCompositionManifestResponse manifest = composition.manifest();
    List<RuleSnapshotApproval> compositionApprovals = verifyCompositionApprovals(
        request, sources, manifest.compositionDigest(), publishedBy, tenant, env);
    approvals.addAll(compositionApprovals);

    if (snapshotRepository.existsByTenantIdAndEnvironmentAndRuleSetKeyAndRuleSetVersion(
        tenant, env, ruleSetKey, request.ruleSet().ref().version())) {
      throw conflict("RuleSet versions are immutable; publish a new version or reactivate the existing snapshot");
    }
    Integer maximumRevision = snapshotRepository.findMaximumPublicationRevision(tenant, env, ruleSetKey);
    int publicationRevision = maximumRevision == null ? 1 : maximumRevision + 1;
    DomainRuleSnapshot previous = snapshotRepository
        .findTopByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(tenant, env, ruleSetKey)
        .orElse(null);
    if (previous != null) {
      verifyStableRuleSetIdentity(readSnapshotForSupersession(previous), request.ruleSet(), ownerServiceKey);
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
    CompiledRuleSnapshot compiled = compileCandidate(candidate, composition.implementations());

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
        .compositionManifest(writeJson(manifest.manifest(), "Cannot persist composition approval manifest"))
        .compositionDigest(manifest.compositionDigest())
        .supersedesSnapshotId(previous == null ? null : previous.getId())
        .publishedBy(requireText(publishedBy, "authenticated publisher"))
        .publishedAt(now)
        .build());

    DomainRuleSnapshotHead head = currentHead.orElseGet(() -> DomainRuleSnapshotHead.builder()
        .id(UUID.randomUUID())
        .tenantId(tenant)
        .environment(env)
        .ruleSetKey(ruleSetKey)
        .activationRevision(0L)
        .build());
    UUID fromSnapshotId = head.getActiveSnapshotId();
    activate(head, persisted.getId(), now);
    try {
      headRepository.saveAndFlush(head);
    } catch (DataIntegrityViolationException exception) {
      throw conflict("Snapshot publication violated a persistent integrity constraint; reload scoped state before retrying");
    }
    appendEvent("PUBLISHED", head, fromSnapshotId, persisted.getId(), publishedBy, now);
    return activation(compiled.snapshot(), compiled.snapshotContentHash(), head, "PUBLISHED");
  }

  DomainRuleSnapshotActivationResponse publish(
      DomainRuleSnapshotPublicationRequest request,
      String tenantId,
      String environment,
      String ifMatch,
      String ifNoneMatch) {
    return publish(request, tenantId, environment, ifMatch, ifNoneMatch, "release-manager");
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
    DomainRuleSnapshot active = snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
            head.getActiveSnapshotId(), tenant, env, target.getRuleSetKey())
        .orElseThrow(() -> new IllegalStateException("Snapshot head references missing immutable content"));
    if (target.getPublicationRevision() >= active.getPublicationRevision()) {
      throw conflict("Rollback requires a snapshot older than the current active publication");
    }
    return selectExistingSnapshot(target, head, activatedBy, "ROLLED_BACK");
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleSnapshotActivationResponse activatePublished(
      String snapshotKey,
      String activatedBy,
      String tenantId,
      String environment,
      String ifMatch) {
    return activatePublished(snapshotKey, activatedBy, tenantId, environment, ifMatch, null);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleSnapshotActivationResponse activatePublished(
      String snapshotKey,
      String activatedBy,
      String tenantId,
      String environment,
      String ifMatch,
      UUID rolloutId) {
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
    DomainRuleSnapshot active = snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
            head.getActiveSnapshotId(), tenant, env, target.getRuleSetKey())
        .orElseThrow(() -> new IllegalStateException("Snapshot head references missing immutable content"));
    if (target.getPublicationRevision() <= active.getPublicationRevision()) {
      throw conflict("Activation requires a snapshot newer than the current active publication; use rollback for an older version");
    }
    activationGate.requireAllowed(rolloutId, target, head, activatedBy);
    DomainRuleSnapshotActivationResponse response =
        selectExistingSnapshot(target, head, activatedBy, "ACTIVATED");
    activationGate.activationCompleted(rolloutId, target, activatedBy);
    return response;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRuleSnapshotActivationResponse> findActive(
      String tenantId, String environment, String ruleSetKey) {
    String tenant = requireText(tenantId, "X-Tenant-ID");
    String env = requireText(environment, "X-Env");
    return headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
            tenant, env, requireText(ruleSetKey, "ruleSetKey"))
        .map(head -> {
          DomainRuleSnapshot stored = snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
                  head.getActiveSnapshotId(), tenant, env, head.getRuleSetKey())
              .orElseThrow(() -> new IllegalStateException("Snapshot head references missing immutable content"));
          return activation(readVerifiedSnapshot(stored), stored.getContentHash(), head, "ACTIVE");
        });
  }

  /** Returns safe head metadata even when preserved beta content is intentionally not executable. */
  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRuleSnapshotHeadStatusResponse> findHeadStatus(
      String tenantId, String environment, String ruleSetKey) {
    String tenant = requireText(tenantId, "X-Tenant-ID");
    String env = requireText(environment, "X-Env");
    return headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
            tenant, env, requireText(ruleSetKey, "ruleSetKey"))
        .map(head -> {
          DomainRuleSnapshot stored = snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
                  head.getActiveSnapshotId(), tenant, env, head.getRuleSetKey())
              .orElseThrow(() -> new IllegalStateException("Snapshot head references missing immutable content"));
          String governanceState;
          boolean executionReady;
          if (isPreManifestSnapshot(stored)) {
            governanceState = "REPUBLICATION_REQUIRED";
            executionReady = false;
          } else {
            try {
              readVerifiedSnapshot(stored);
              governanceState = "READY";
              executionReady = true;
            } catch (RuntimeException invalid) {
              governanceState = "INVALID";
              executionReady = false;
            }
          }
          return new DomainRuleSnapshotHeadStatusResponse(
              head.getRuleSetKey(), stored.getSnapshotKey(), stored.getRuleSetVersion(),
              stored.getPublicationRevision(), head.getActivationRevision(), head.getHeadEtag().toString(),
              executionReady, governanceState);
        });
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRuleSnapshotStoredResponse> findSnapshot(
      String tenantId, String environment, String snapshotKey) {
    return snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
            requireText(tenantId, "X-Tenant-ID"),
            requireText(environment, "X-Env"),
            requireText(snapshotKey, "snapshotKey"))
        .map(stored -> new DomainRuleSnapshotStoredResponse(readVerifiedSnapshot(stored), stored.getContentHash()));
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<DomainRuleSnapshotVersionResponse> listVersions(
      String tenantId, String environment, String ruleSetKey, int limit) {
    String tenant = requireText(tenantId, "X-Tenant-ID");
    String env = requireText(environment, "X-Env");
    String key = requireText(ruleSetKey, "ruleSetKey");
    if (limit < 1 || limit > 100) {
      throw badRequest("limit must be between 1 and 100");
    }
    DomainRuleSnapshotHead activeHead = headRepository
        .findByTenantIdAndEnvironmentAndRuleSetKey(tenant, env, key)
        .orElse(null);
    UUID activeSnapshotId = activeHead == null ? null : activeHead.getActiveSnapshotId();
    Integer activePublicationRevision = activeHead == null
        ? null
        : snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
            activeSnapshotId, tenant, env, key)
            .map(DomainRuleSnapshot::getPublicationRevision)
            .orElseThrow(() -> new IllegalStateException(
                "Snapshot head references missing immutable content"));
    return snapshotRepository
        .findByTenantIdAndEnvironmentAndRuleSetKeyOrderByPublicationRevisionDesc(
            tenant, env, key, PageRequest.of(0, limit))
        .stream()
        .map(snapshot -> versionResponse(snapshot, activeSnapshotId, activePublicationRevision))
        .toList();
  }

  private DomainRuleSnapshotVersionResponse versionResponse(
      DomainRuleSnapshot snapshot, UUID activeSnapshotId, Integer activePublicationRevision) {
    String governanceState;
    if (isPreManifestSnapshot(snapshot)) {
      governanceState = "REPUBLICATION_REQUIRED";
    } else {
      try {
        readVerifiedSnapshot(snapshot);
        governanceState = "READY";
      } catch (IllegalStateException invalid) {
        governanceState = "INVALID";
      }
    }
    boolean active = snapshot.getId().equals(activeSnapshotId);
    String availableAction;
    if (active) {
      availableAction = "ACTIVE";
    } else if (!"READY".equals(governanceState) || activePublicationRevision == null) {
      availableAction = "UNAVAILABLE";
    } else if (snapshot.getPublicationRevision() < activePublicationRevision) {
      availableAction = "ROLLBACK";
    } else {
      availableAction = "ACTIVATE";
    }
    return new DomainRuleSnapshotVersionResponse(
        snapshot.getSnapshotKey(),
        snapshot.getRuleSetKey(),
        snapshot.getRuleSetVersion(),
        snapshot.getPublicationRevision(),
        snapshot.getContentHash(),
        snapshot.getPublishedBy(),
        snapshot.getPublishedAt().toString(), active, governanceState, availableAction);
  }

  private DomainRuleSnapshotActivationResponse selectExistingSnapshot(
      DomainRuleSnapshot target,
      DomainRuleSnapshotHead head,
      String activatedBy,
      String operation) {
    PublishedRuleSnapshot snapshot = readVerifiedSnapshot(target);
    Instant now = Instant.now();
    Instant validFrom = Instant.parse(snapshot.validFromUtc());
    Instant validUntil = snapshot.validUntilUtc() == null
        ? null
        : Instant.parse(snapshot.validUntilUtc());
    if (now.isBefore(validFrom) || (validUntil != null && !now.isBefore(validUntil))) {
      throw conflict(operation + " target is outside its governed validity interval");
    }
    UUID fromSnapshotId = head.getActiveSnapshotId();
    activate(head, target.getId(), now);
    headRepository.save(head);
    appendEvent(operation, head, fromSnapshotId, target.getId(), activatedBy, now);
    return activation(snapshot, target.getContentHash(), head, operation);
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
      if (!PUBLISHABLE_STATUSES.contains(source.getStatus())) {
        throw badRequest("Every source definition must have an approved or active status");
      }
      if (!"authenticated".equals(source.getCreatedByType())
          || source.getCreatedBy() == null || source.getCreatedBy().isBlank()) {
        throw badRequest("Every source definition requires server-authenticated author evidence");
      }
      currentApproval(source, definitionFingerprint.sha256(source));
    }
    return sources.stream().sorted(java.util.Comparator.comparing(DomainRuleDefinition::getId)).toList();
  }

  private DomainRuleDefinitionApproval currentApproval(
      DomainRuleDefinition source, String definitionHash) {
    return definitionApprovalRepository
        .findByTenantIdAndEnvironmentAndDefinitionIdAndDefinitionHashOrderByApprovedAtAsc(
            source.getTenantId(), source.getEnvironment(), source.getId(), definitionHash)
        .stream()
        .filter(approval -> "RULE_DEFINITION_APPROVER".equals(approval.getRole()))
        .filter(approval -> !approval.getActorRef().equals(source.getCreatedBy()))
        .findFirst()
        .orElseThrow(() -> badRequest(
            "Every source definition requires authenticated approval for its exact content hash"));
  }

  private CompiledRuleSnapshot compileCandidate(
      PublishedRuleSnapshot snapshot, Collection<RuleImplementationRef> implementations) {
    try {
      RuleBindingExecutorRegistry registry = RuleBindingExecutorRegistry.planning(implementations);
      return new PraxisRuleSnapshotCompiler(registry)
          .compile(snapshot, snapshot.requiredHostContractVersion());
    } catch (RulePlanException exception) {
      throw badRequest("RuleSet publication failed [" + exception.getCode() + "]");
    }
  }

  private PreparedComposition prepareComposition(
      RuleSetDefinition ruleSet, List<DomainRuleDefinition> sources,
      String tenant, String environment, String ownerServiceKey,
      String requiredHostContractVersion, String validFromUtc, String validUntilUtc) {
    var sourceEvidence = sources.stream().map(source -> new RuleSnapshotSource(
        source.getId().toString(), source.getRuleKey(), source.getVersion(),
        definitionFingerprint.sha256(source))).toList();
    List<RuleImplementationRef> implementations = allowedImplementations(
        tenant, environment, ownerServiceKey);
    ObjectNode catalog = objectMapper.createObjectNode();
    catalog.put("tenantId", tenant);
    catalog.put("environment", environment);
    catalog.put("ownerServiceKey", ownerServiceKey);
    catalog.set("implementations", objectMapper.valueToTree(implementations));
    String catalogDigest = DomainRuleImplementationCatalogFingerprint.sha256(
        objectMapper, new DomainRuleImplementationScope(tenant, environment, ownerServiceKey), implementations);
    ObjectNode manifest = objectMapper.createObjectNode();
    manifest.put("compositionContractVersion", COMPOSITION_CONTRACT_VERSION);
    manifest.put("snapshotContractVersion", PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION);
    manifest.put("tenantId", tenant);
    manifest.put("environment", environment);
    manifest.put("ownerServiceKey", ownerServiceKey);
    manifest.put("requiredHostContractVersion", requiredHostContractVersion);
    manifest.put("validFromUtc", validFromUtc);
    if (validUntilUtc == null) manifest.putNull("validUntilUtc"); else manifest.put("validUntilUtc", validUntilUtc);
    manifest.set("sources", objectMapper.valueToTree(sourceEvidence));
    manifest.set("testEvidence", evidenceManifest(sources, tenant, environment));
    manifest.set("implementationCatalog", catalog);
    manifest.put("implementationCatalogDigest", catalogDigest);
    manifest.set("ruleSet", objectMapper.valueToTree(ruleSet));
    compileCandidate(new PublishedRuleSnapshot(
        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION, "manifest-preview", tenant, environment,
        ownerServiceKey, 1, Instant.EPOCH.toString(), null, requiredHostContractVersion,
        validFromUtc, validUntilUtc, sourceEvidence,
        List.of(new RuleSnapshotApproval("manifest-preview", "RULE_COMPOSITION_APPROVER",
            "manifest-preview", Instant.EPOCH.toString(), "0".repeat(64))), ruleSet), implementations);
    return new PreparedComposition(
        new DomainRuleCompositionManifestResponse(
            COMPOSITION_CONTRACT_VERSION, PraxisCanonicalJson.sha256(manifest), catalogDigest, manifest),
        implementations);
  }

  private ArrayNode evidenceManifest(
      List<DomainRuleDefinition> sources, String tenant, String environment) {
    ArrayNode evidence = objectMapper.createArrayNode();
    DomainRuleDefinitionEvidenceGateService gate = evidenceGate == null
        ? null : evidenceGate.getIfAvailable();
    DomainRuleGovernancePrincipal principal =
        new DomainRuleGovernancePrincipal(tenant, "snapshot-composer", environment);
    for (DomainRuleDefinition source : sources) {
      for (String stage : List.of("SNAPSHOT", "ACTIVATE")) {
        if (gate == null) {
          if (declaresEvidenceStage(source, stage)) {
            throw badRequest("Test evidence gate is unavailable for governed " + stage + " stage");
          }
          continue;
        }
        DomainRuleDefinitionEvidenceDecision decision = gate.decision(stage, source, principal);
        if (!decision.required()) continue;
        if (!decision.satisfied()) {
          String codes = decision.blockers().stream()
              .map(blocker -> blocker.code()).sorted().distinct()
              .reduce((left, right) -> left + "," + right).orElse("TEST_EVIDENCE_REQUIRED");
          throw badRequest("RuleSet composition blocked by reviewed Test Run evidence ["
              + stage + ":" + codes + "]");
        }
        ObjectNode item = evidence.addObject();
        item.put("definitionId", decision.definitionId().toString());
        item.put("stage", stage);
        item.put("workspaceId", decision.workspaceId().toString());
        item.put("testRunId", decision.testRunId().toString());
        item.put("requestHash", decision.requestHash());
        item.put("workspaceRevision", decision.workspaceRevision());
        item.put("evidenceDigest", decision.evidenceDigest());
        item.put("satisfied", true);
      }
    }
    return evidence;
  }

  private boolean declaresEvidenceStage(DomainRuleDefinition source, String stage) {
    if (source.getGovernance() == null || source.getGovernance().isBlank()) return false;
    try {
      var stages = objectMapper.readTree(source.getGovernance())
          .path("testEvidencePolicy").path("stages");
      return stages.isObject() && stages.has(stage) && !stages.get(stage).isNull();
    } catch (JsonProcessingException exception) {
      throw badRequest("Source definition governance is not valid JSON");
    }
  }

  private List<RuleImplementationRef> allowedImplementations(
      String tenant, String environment, String ownerServiceKey) {
    DomainRuleImplementationScope scope = new DomainRuleImplementationScope(
        tenant, environment, ownerServiceKey);
    Collection<RuleImplementationRef> allowed = implementationCatalog.allowedImplementations(scope);
    if (allowed == null) {
      throw new IllegalStateException("DomainRuleImplementationCatalog must not return null");
    }
    return allowed.stream()
        .sorted(Comparator.comparing(RuleImplementationRef::implementationKey)
            .thenComparing(RuleImplementationRef::implementationVersion))
        .toList();
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

  private List<RuleSnapshotApproval> verifyCompositionApprovals(
      DomainRuleSnapshotPublicationRequest request,
      List<DomainRuleDefinition> sources,
      String calculatedDigest,
      String publishedBy,
      String tenant,
      String environment) {
    if (request.compositionDigest() == null
        || !calculatedDigest.equalsIgnoreCase(request.compositionDigest().trim())) {
      throw badRequest("compositionDigest does not match the server-canonicalized publication candidate");
    }
    List<DomainRuleCompositionApproval> persisted = compositionApprovalRepository
        .findByTenantIdAndEnvironmentAndCompositionDigestOrderByApprovedAtAsc(
            tenant, environment, calculatedDigest);
    if (persisted.isEmpty()) throw badRequest("Persisted composition approvals are required");
    List<RuleSnapshotApproval> values = persisted.stream()
        .map(value -> new RuleSnapshotApproval(
            value.getId().toString(),
            value.getRole(),
            value.getActorRef(),
            value.getApprovedAt().toString(),
            value.getCompositionDigest()))
        .toList();
    Set<String> actors = new LinkedHashSet<>();
    Set<String> keys = new LinkedHashSet<>();
    Instant latestSourceApproval = sources.stream().map(DomainRuleDefinition::getApprovedAt)
        .max(Instant::compareTo).orElseThrow();
    Instant now = Instant.now();
    String publisher = requireText(publishedBy, "authenticated publisher");
    for (RuleSnapshotApproval approval : values) {
      if (approval == null
          || !"RULE_COMPOSITION_APPROVER".equals(approval.role())
          || !calculatedDigest.equals(approval.evidenceHash())) {
        throw badRequest("Every composition approval must use role RULE_COMPOSITION_APPROVER and the exact composition digest");
      }
      Instant decidedAt = Instant.parse(approval.decidedAtUtc());
      if (decidedAt.isBefore(latestSourceApproval) || decidedAt.isAfter(now)) {
        throw badRequest("Composition approvals must follow all source approvals and cannot be future-dated");
      }
      actors.add(approval.actorRef());
      if (!keys.add(approval.approvalKey())) throw badRequest("composition approval keys must be distinct");
    }
    if (actors.size() < MINIMUM_DISTINCT_APPROVERS) {
      throw badRequest("Snapshot publication requires at least two distinct composition approvers");
    }
    if (actors.contains(publisher)) {
      throw badRequest("The publisher cannot approve the same RuleSet composition");
    }
    return values;
  }

  private DomainRuleCompositionApprovalResponse approvalResponse(
      DomainRuleCompositionApproval approval) {
    return new DomainRuleCompositionApprovalResponse(
        approval.getId().toString(),
        approval.getRole(),
        approval.getActorRef(),
        approval.getApprovedAt().toString(),
        approval.getCompositionDigest());
  }

  private PublishedRuleSnapshot readVerifiedSnapshot(DomainRuleSnapshot stored) {
    PublishedRuleSnapshot snapshot = readEnvelopeAndVerifyContent(stored);
    verifyStoredComposition(stored, snapshot);
    return snapshot;
  }

  private PublishedRuleSnapshot readSnapshotForSupersession(DomainRuleSnapshot stored) {
    PublishedRuleSnapshot previous = readSnapshot(stored);
    verifyStoredEnvelope(stored, previous);
    String canonicalHash = PraxisCanonicalJson.sha256(objectMapper.valueToTree(previous));
    if (!stored.getContentHash().equals(canonicalHash)) {
      throw new IllegalStateException("Persisted RuleSet snapshot content hash verification failed");
    }
    if (!isPreManifestSnapshot(stored)) {
      verifyStoredComposition(stored, previous);
    }
    return previous;
  }

  private PublishedRuleSnapshot readEnvelopeAndVerifyContent(DomainRuleSnapshot stored) {
    PublishedRuleSnapshot snapshot = readSnapshot(stored);
    verifyStoredEnvelope(stored, snapshot);
    CompiledRuleSnapshot compiled;
    try {
      compiled = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.planning(
          allowedImplementations(snapshot.tenantId(), snapshot.environment(), snapshot.ownerServiceKey())))
          .compile(snapshot, snapshot.requiredHostContractVersion());
    } catch (RulePlanException exception) {
      throw new IllegalStateException(
          "Persisted RuleSet snapshot is no longer admitted by the host implementation catalog", exception);
    }
    if (!stored.getContentHash().equals(compiled.snapshotContentHash())) {
      throw new IllegalStateException("Persisted RuleSet snapshot content hash verification failed");
    }
    return compiled.snapshot();
  }

  private void verifyStoredEnvelope(DomainRuleSnapshot stored, PublishedRuleSnapshot snapshot) {
    if (!stored.getSnapshotKey().equals(snapshot.snapshotKey())
        || !stored.getTenantId().equals(snapshot.tenantId())
        || !stored.getEnvironment().equals(snapshot.environment())
        || !stored.getRuleSetKey().equals(snapshot.ruleSet().ref().ruleSetKey())
        || !stored.getRuleSetVersion().equals(snapshot.ruleSet().ref().version())
        || !stored.getPublicationRevision().equals(snapshot.publicationRevision())) {
      throw new IllegalStateException("Persisted RuleSet snapshot identity does not match its immutable envelope");
    }
  }

  private boolean isPreManifestSnapshot(DomainRuleSnapshot stored) {
    return stored.getCompositionManifest() == null && stored.getCompositionDigest() == null;
  }

  private void verifyStoredComposition(DomainRuleSnapshot stored, PublishedRuleSnapshot snapshot) {
    if (stored.getCompositionManifest() == null || stored.getCompositionManifest().isBlank()
        || stored.getCompositionDigest() == null || stored.getCompositionDigest().isBlank()) {
      throw new IllegalStateException("Persisted RuleSet composition manifest is unreadable");
    }
    try {
      var manifest = objectMapper.readTree(stored.getCompositionManifest());
      String digest = PraxisCanonicalJson.sha256(manifest);
      long approvers = snapshot.approvals().stream()
          .filter(approval -> "RULE_COMPOSITION_APPROVER".equals(approval.role()))
          .filter(approval -> digest.equals(approval.evidenceHash()))
          .map(RuleSnapshotApproval::actorRef).distinct().count();
      if (!digest.equals(stored.getCompositionDigest()) || approvers < MINIMUM_DISTINCT_APPROVERS) {
        throw new IllegalStateException("Persisted RuleSet composition approval verification failed");
      }
      if (COMPOSITION_CONTRACT_VERSION.equals(manifest.path("compositionContractVersion").asText())) {
        var testEvidence = manifest.get("testEvidence");
        if (testEvidence == null || !testEvidence.isArray()) {
          throw new IllegalStateException("Persisted RuleSet test evidence manifest is unreadable");
        }
        for (var item : testEvidence) {
          if (!item.path("satisfied").asBoolean(false)
              || item.path("definitionId").asText().isBlank()
              || item.path("stage").asText().isBlank()
              || item.path("workspaceId").asText().isBlank()
              || item.path("testRunId").asText().isBlank()
              || item.path("requestHash").asText().isBlank()
              || item.path("evidenceDigest").asText().isBlank()) {
            throw new IllegalStateException("Persisted RuleSet test evidence manifest is invalid");
          }
        }
      }
    } catch (JsonProcessingException | NullPointerException exception) {
      throw new IllegalStateException("Persisted RuleSet composition manifest is unreadable", exception);
    }
  }

  private String writeJson(Object value, String message) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(message, exception);
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

  private record PreparedComposition(
      DomainRuleCompositionManifestResponse manifest,
      List<RuleImplementationRef> implementations) {}
}
