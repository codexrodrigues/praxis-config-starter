package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainRuleCompositionApproval;
import org.praxisplatform.config.domain.DomainRuleDefinition;
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
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.repository.DomainRuleCompositionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/** Governed publication, activation and rollback of immutable RuleSet snapshots. */
@RequiredArgsConstructor
public class DomainRuleSnapshotService implements DomainRuleSnapshotReader {
  private static final Set<String> PUBLISHABLE_STATUSES = Set.of("approved", "active");
  private static final int MINIMUM_DISTINCT_APPROVERS = 2;
  private static final String COMPOSITION_CONTRACT_VERSION = "praxis-rule-composition/1";

  private final DomainRuleDefinitionRepository definitionRepository;
  private final DomainRuleSnapshotRepository snapshotRepository;
  private final DomainRuleSnapshotHeadRepository headRepository;
  private final DomainRuleSnapshotEventRepository eventRepository;
  private final DomainRuleCompositionApprovalRepository compositionApprovalRepository;
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
      DomainRuleCompositionApprovalRepository compositionApprovalRepository,
      ObjectMapper objectMapper) {
    this(
        definitionRepository,
        snapshotRepository,
        headRepository,
        eventRepository,
        compositionApprovalRepository,
        objectMapper,
        DomainRuleImplementationCatalog.denyAll());
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
      String sourceHash = sourceHash(source);
      provenance.add(new RuleSnapshotSource(
          source.getId().toString(), source.getRuleKey(), source.getVersion(), sourceHash));
      approvals.add(new RuleSnapshotApproval(
          source.getId() + ":approval",
          "RULE_DEFINITION_APPROVER",
          source.getApprovedBy().trim(),
          source.getApprovedAt().toString(),
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
        .rowVersion(0L)
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
    PublishedRuleSnapshot snapshot = readVerifiedSnapshot(target);
    Instant now = Instant.now();
    Instant validFrom = Instant.parse(snapshot.validFromUtc());
    Instant validUntil = snapshot.validUntilUtc() == null
        ? null
        : Instant.parse(snapshot.validUntilUtc());
    if (now.isBefore(validFrom) || (validUntil != null && !now.isBefore(validUntil))) {
      throw conflict("Rollback target is outside its governed validity interval");
    }
    UUID fromSnapshotId = head.getActiveSnapshotId();
    activate(head, target.getId(), now);
    headRepository.save(head);
    appendEvent("ROLLED_BACK", head, fromSnapshotId, target.getId(), activatedBy, now);
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

  @Override
  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRuleSnapshotStoredResponse> findSnapshot(
      String tenantId, String environment, String snapshotKey) {
    return snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
            requireText(tenantId, "X-Tenant-ID"),
            requireText(environment, "X-Env"),
            requireText(snapshotKey, "snapshotKey"))
        .map(stored -> new DomainRuleSnapshotStoredResponse(readVerifiedSnapshot(stored), stored.getContentHash()));
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
        source.getId().toString(), source.getRuleKey(), source.getVersion(), sourceHash(source))).toList();
    List<RuleImplementationRef> implementations = allowedImplementations(
        tenant, environment, ownerServiceKey);
    ObjectNode catalog = objectMapper.createObjectNode();
    catalog.put("tenantId", tenant);
    catalog.put("environment", environment);
    catalog.put("ownerServiceKey", ownerServiceKey);
    catalog.set("implementations", objectMapper.valueToTree(implementations));
    String catalogDigest = PraxisCanonicalJson.sha256(catalog);
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
    if (isPreManifestSnapshot(stored)) {
      PublishedRuleSnapshot legacy = readSnapshot(stored);
      verifyStoredEnvelope(stored, legacy);
      return legacy;
    }
    return readVerifiedSnapshot(stored);
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
