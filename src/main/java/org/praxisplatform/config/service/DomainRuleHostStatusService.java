package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleHostStatus;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.dto.DomainRuleHostStatusIngestionResponse;
import org.praxisplatform.config.dto.DomainRuleHostStatusRequest;
import org.praxisplatform.config.dto.DomainRuleHostStatusSummaryResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainRuleHostStatusRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.transaction.annotation.Transactional;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;

/** Governs host heartbeats and derives drift against the authoritative active snapshot head. */
public class DomainRuleHostStatusService {
  private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

  private final DomainRuleHostStatusRepository statusRepository;
  private final DomainRuleSnapshotHeadRepository headRepository;
  private final DomainRuleSnapshotRepository snapshotRepository;
  private final DomainRuleSnapshotService snapshotService;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Duration staleAfter;

  public DomainRuleHostStatusService(
      DomainRuleHostStatusRepository statusRepository,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotService snapshotService,
      ObjectMapper objectMapper,
      Clock clock,
      Duration staleAfter) {
    this.statusRepository = Objects.requireNonNull(statusRepository, "statusRepository is required");
    this.headRepository = Objects.requireNonNull(headRepository, "headRepository is required");
    this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository is required");
    this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService is required");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter is required");
    if (staleAfter.isZero() || staleAfter.isNegative()) {
      throw new IllegalArgumentException("staleAfter must be positive");
    }
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleHostStatusIngestionResponse ingest(
      DomainRuleHostStatusRequest request, DomainRuleGovernancePrincipal principal) {
    requirePrincipal(principal);
    if (request == null || request.observedAtUtc() == null) {
      throw new ConfigurationIngestionException("observedAtUtc is required");
    }
    Instant receivedAt = clock.instant();
    if (request.observedAtUtc().isAfter(receivedAt.plus(MAX_FUTURE_SKEW))) {
      throw new ConfigurationIngestionException("observedAtUtc cannot be in the future");
    }
    String ruleSetKey = requireText(request.ruleSetKey(), "ruleSetKey", 512);
    String contractVersion = requireText(request.hostContractVersion(), "hostContractVersion", 64);
    String engineContractVersion = optionalText(
        request.engineContractVersion(), "engineContractVersion", 64);
    String dialectVersion = optionalText(
        request.jsonLogicDialectVersion(), "jsonLogicDialectVersion", 64);
    String corpusSha256 = optionalHash(request.jsonLogicCorpusSha256(), "jsonLogicCorpusSha256");
    String catalogDigest = optionalHash(
        request.implementationCatalogDigest(), "implementationCatalogDigest");
    String snapshotKey = optionalText(request.loadedSnapshotKey(), "loadedSnapshotKey", 128);
    String contentHash = optionalHash(request.loadedSnapshotContentHash());
    Long revision = request.activationRevision();
    if (request.ready() && (snapshotKey == null || contentHash == null || revision == null
        || engineContractVersion == null || dialectVersion == null
        || corpusSha256 == null || catalogDigest == null)) {
      throw new ConfigurationIngestionException(
          "A ready host requires complete snapshot and runtime compatibility coordinates");
    }
    if (revision != null && revision < 1) {
      throw new ConfigurationIngestionException("activationRevision must be positive");
    }
    String failureCode = optionalText(request.failureCode(), "failureCode", 64);
    int changed = statusRepository.upsertIfNewer(
        UUID.randomUUID(), principal.tenantId(), principal.environment(), ruleSetKey,
        principal.actorRef(), snapshotKey, contentHash, revision, request.ready(), contractVersion,
        engineContractVersion, dialectVersion, corpusSha256, catalogDigest,
        failureCode, request.observedAtUtc(), receivedAt);
    return new DomainRuleHostStatusIngestionResponse(changed == 1, request.observedAtUtc());
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public DomainRuleHostStatusSummaryResponse summarizeHead(
      String ruleSetKey, DomainRuleGovernancePrincipal principal) {
    requirePrincipal(principal);
    String requiredRuleSetKey = requireText(ruleSetKey, "ruleSetKey", 512);
    DomainRuleSnapshotHead head = headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
            principal.tenantId(), principal.environment(), requiredRuleSetKey)
        .orElseThrow(() -> new ConfigurationIngestionException("RuleSet head was not found"));
    DomainRuleSnapshot expected = snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
            head.getActiveSnapshotId(), principal.tenantId(), principal.environment(), requiredRuleSetKey)
        .orElseThrow(() -> new IllegalStateException("Snapshot head references missing immutable content"));
    PublishedRuleSnapshot expectedEnvelope = snapshotService.findSnapshot(
            principal.tenantId(), principal.environment(), expected.getSnapshotKey())
        .orElseThrow(() -> new IllegalStateException("Active immutable snapshot could not be verified"))
        .snapshot();
    ExpectedRuntime expectedRuntime = expectedRuntime(expected, expectedEnvelope);
    List<DomainRuleHostStatus> statuses = statusRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        principal.tenantId(), principal.environment(), requiredRuleSetKey);
    Instant staleBefore = clock.instant().minus(staleAfter);
    long stale = statuses.stream().filter(status -> status.getObservedAt().isBefore(staleBefore)).count();
    List<DomainRuleHostStatus> freshReady = statuses.stream()
        .filter(status -> !status.getObservedAt().isBefore(staleBefore))
        .filter(status -> Boolean.TRUE.equals(status.getReady())).toList();
    long unavailable = statuses.stream().filter(status -> !status.getObservedAt().isBefore(staleBefore))
        .filter(status -> !Boolean.TRUE.equals(status.getReady())).count();
    long snapshotDrifted = freshReady.stream()
        .filter(status -> !snapshotAligned(status, expected, head)).count();
    long incompatible = freshReady.stream()
        .filter(status -> snapshotAligned(status, expected, head))
        .filter(status -> !runtimeCompatible(status, expectedRuntime)).count();
    long aligned = freshReady.size() - snapshotDrifted - incompatible;
    Instant lastObserved = statuses.stream().map(DomainRuleHostStatus::getObservedAt)
        .max(Comparator.naturalOrder()).orElse(null);
    return new DomainRuleHostStatusSummaryResponse(
        requiredRuleSetKey, expected.getSnapshotKey(), expected.getContentHash(),
        head.getActivationRevision(), expectedRuntime.hostContractVersion(),
        expectedRuntime.compatibility().engineContractVersion(),
        expectedRuntime.compatibility().jsonLogicDialectVersion(),
        expectedRuntime.compatibility().jsonLogicCorpusSha256(),
        expectedRuntime.implementationCatalogDigest(),
        statuses.size(), aligned, snapshotDrifted, incompatible, unavailable, stale,
        lastObserved, staleBefore);
  }

  private boolean snapshotAligned(
      DomainRuleHostStatus status, DomainRuleSnapshot expected, DomainRuleSnapshotHead head) {
    return expected.getSnapshotKey().equals(status.getLoadedSnapshotKey())
        && expected.getContentHash().equals(status.getLoadedSnapshotContentHash())
        && head.getActivationRevision().equals(status.getActivationRevision());
  }

  private boolean runtimeCompatible(DomainRuleHostStatus status, ExpectedRuntime expected) {
    RuleRuntimeCompatibility compatibility = expected.compatibility();
    return expected.hostContractVersion().equals(status.getHostContractVersion())
        && compatibility.engineContractVersion().equals(status.getEngineContractVersion())
        && compatibility.jsonLogicDialectVersion().equals(status.getJsonLogicDialectVersion())
        && compatibility.jsonLogicCorpusSha256().equals(status.getJsonLogicCorpusSha256())
        && Objects.equals(expected.implementationCatalogDigest(), status.getImplementationCatalogDigest());
  }

  private ExpectedRuntime expectedRuntime(
      DomainRuleSnapshot stored, PublishedRuleSnapshot snapshot) {
    String catalogDigest = null;
    try {
      if (stored.getCompositionManifest() != null) {
        catalogDigest = optionalHash(
            objectMapper.readTree(stored.getCompositionManifest())
                .path("implementationCatalogDigest").asText(null),
            "implementationCatalogDigest");
      }
    } catch (Exception invalid) {
      throw new IllegalStateException("Stored composition manifest could not be read", invalid);
    }
    return new ExpectedRuntime(
        snapshot.requiredHostContractVersion(), snapshot.ruleSet().compatibility(), catalogDigest);
  }

  private void requirePrincipal(DomainRuleGovernancePrincipal principal) {
    if (principal == null) {
      throw new ConfigurationIngestionException("Governed host-status principal is required");
    }
    requireText(principal.tenantId(), "tenantId", 128);
    requireText(principal.environment(), "environment", 128);
    requireText(principal.actorRef(), "actorRef", 255);
  }

  private String optionalHash(String value) {
    return optionalHash(value, "loadedSnapshotContentHash");
  }

  private String optionalHash(String value, String field) {
    String normalized = optionalText(value, field, 64);
    if (normalized != null && !normalized.matches("[A-F0-9]{64}")) {
      throw new ConfigurationIngestionException(
          field + " must be an uppercase SHA-256 digest");
    }
    return normalized;
  }

  private String optionalText(String value, String field, int maximumLength) {
    return value == null || value.isBlank() ? null : requireText(value, field, maximumLength);
  }

  private String requireText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new ConfigurationIngestionException(field + " is required");
    }
    String normalized = value.trim();
    if (normalized.length() > maximumLength) {
      throw new ConfigurationIngestionException(field + " exceeds the governed length");
    }
    return normalized;
  }

  private record ExpectedRuntime(
      String hostContractVersion,
      RuleRuntimeCompatibility compatibility,
      String implementationCatalogDigest) {}
}
