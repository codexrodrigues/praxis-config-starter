package org.praxisplatform.config.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.praxisplatform.config.domain.DomainRuleExecutionObservation;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationBatchRequest;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationBatchResponse;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationRequest;
import org.praxisplatform.config.dto.DomainRuleExecutionSummaryResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainRuleExecutionObservationRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.transaction.annotation.Transactional;

/** Validates and persists redacted host evidence without entering the evaluation transaction. */
public class DomainRuleExecutionObservationService {
  private static final int MAX_BATCH_SIZE = 100;
  private static final long MAX_DURATION_MICROS = 300_000_000L;
  private static final Set<String> OUTCOMES = Set.of(
      "ALLOW", "DENY", "NOT_APPLICABLE", "INCONCLUSIVE", "TECHNICAL_ERROR");

  private final DomainRuleSnapshotRepository snapshotRepository;
  private final DomainRuleSnapshotEventRepository snapshotEventRepository;
  private final DomainRuleExecutionObservationRepository observationRepository;
  private final Clock clock;

  public DomainRuleExecutionObservationService(
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotEventRepository snapshotEventRepository,
      DomainRuleExecutionObservationRepository observationRepository,
      Clock clock) {
    this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository is required");
    this.snapshotEventRepository = Objects.requireNonNull(
        snapshotEventRepository, "snapshotEventRepository is required");
    this.observationRepository = Objects.requireNonNull(
        observationRepository, "observationRepository is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleExecutionObservationBatchResponse ingest(
      DomainRuleExecutionObservationBatchRequest request,
      DomainRuleGovernancePrincipal principal) {
    requirePrincipal(principal);
    List<DomainRuleExecutionObservationRequest> observations = request == null
        ? null
        : request.observations();
    if (observations == null || observations.isEmpty() || observations.size() > MAX_BATCH_SIZE) {
      throw new ConfigurationIngestionException("observations must contain between 1 and 100 entries");
    }

    int accepted = 0;
    int duplicates = 0;
    Instant receivedAt = clock.instant();
    for (DomainRuleExecutionObservationRequest observation : observations) {
      ValidatedObservation validated = validate(observation, principal, receivedAt);
      int inserted = observationRepository.insertIfAbsent(
          validated.observation().observationId(),
          principal.tenantId(),
          principal.environment(),
          validated.snapshot().getRuleSetKey(),
          validated.snapshot().getId(),
          validated.snapshot().getSnapshotKey(),
          validated.snapshot().getContentHash(),
          validated.snapshot().getRuleSetVersion(),
          observation.activationRevision(),
          validated.outcome(),
          observation.durationMicros(),
          observation.observedAtUtc(),
          principal.actorRef(),
          receivedAt);
      if (inserted == 1) {
        accepted++;
      } else {
        DomainRuleExecutionObservation existing = observationRepository
            .findById(observation.observationId())
            .orElseThrow(() -> new ConfigurationIngestionException(
                "Execution observation idempotency state could not be resolved"));
        requireExactDuplicate(existing, validated, principal);
        duplicates++;
      }
    }
    return new DomainRuleExecutionObservationBatchResponse(accepted, duplicates);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public DomainRuleExecutionSummaryResponse summary(
      String snapshotKey,
      DomainRuleGovernancePrincipal principal) {
    requirePrincipal(principal);
    String requiredSnapshotKey = requireText(snapshotKey, "snapshotKey", 128);
    DomainRuleSnapshot snapshot = snapshotRepository
        .findByTenantIdAndEnvironmentAndSnapshotKey(
            principal.tenantId(), principal.environment(), requiredSnapshotKey)
        .orElseThrow(() -> new ConfigurationIngestionException("Rule snapshot was not found"));

    Map<String, Long> outcomes = new LinkedHashMap<>();
    OUTCOMES.stream().sorted().forEach(outcome -> outcomes.put(outcome, 0L));
    observationRepository.countOutcomes(
        principal.tenantId(), principal.environment(), requiredSnapshotKey)
        .forEach(count -> outcomes.put(count.getOutcome(), count.getTotal()));
    var window = observationRepository.observationWindow(
        principal.tenantId(), principal.environment(), requiredSnapshotKey);
    return new DomainRuleExecutionSummaryResponse(
        snapshot.getRuleSetKey(),
        snapshot.getSnapshotKey(),
        snapshot.getContentHash(),
        snapshot.getRuleSetVersion(),
        observationRepository.countByTenantIdAndEnvironmentAndSnapshotKey(
            principal.tenantId(), principal.environment(), requiredSnapshotKey),
        observationRepository.countDistinctHosts(
            principal.tenantId(), principal.environment(), requiredSnapshotKey),
        outcomes,
        window == null ? null : window.getFirstObservedAt(),
        window == null ? null : window.getLastObservedAt());
  }

  private ValidatedObservation validate(
      DomainRuleExecutionObservationRequest observation,
      DomainRuleGovernancePrincipal principal,
      Instant receivedAt) {
    if (observation == null || observation.observationId() == null) {
      throw new ConfigurationIngestionException("Every execution observation requires observationId");
    }
    String snapshotKey = requireText(observation.snapshotKey(), "snapshotKey", 128);
    String contentHash = requireHash(observation.snapshotContentHash());
    String outcome = requireOutcome(observation.outcome());
    if (observation.activationRevision() < 1) {
      throw new ConfigurationIngestionException("activationRevision must be positive");
    }
    if (observation.durationMicros() < 0 || observation.durationMicros() > MAX_DURATION_MICROS) {
      throw new ConfigurationIngestionException("durationMicros is outside the governed limit");
    }
    if (observation.observedAtUtc() == null
        || observation.observedAtUtc().isAfter(receivedAt.plus(Duration.ofMinutes(5)))) {
      throw new ConfigurationIngestionException("observedAtUtc is required and cannot be in the future");
    }
    DomainRuleSnapshot snapshot = snapshotRepository
        .findByTenantIdAndEnvironmentAndSnapshotKey(
            principal.tenantId(), principal.environment(), snapshotKey)
        .orElseThrow(() -> new ConfigurationIngestionException("Rule snapshot was not found"));
    if (!snapshot.getContentHash().equals(contentHash)) {
      throw new ConfigurationIngestionException("snapshotContentHash does not match immutable snapshot content");
    }
    if (!snapshotEventRepository.existsByTenantIdAndEnvironmentAndToSnapshotIdAndActivationRevision(
        principal.tenantId(), principal.environment(), snapshot.getId(), observation.activationRevision())) {
      throw new ConfigurationIngestionException(
          "activationRevision does not select the observed snapshot in this governed scope");
    }
    return new ValidatedObservation(snapshot, observation, outcome);
  }

  private void requireExactDuplicate(
      DomainRuleExecutionObservation existing,
      ValidatedObservation validated,
      DomainRuleGovernancePrincipal principal) {
    DomainRuleExecutionObservationRequest requested = validated.observation();
    DomainRuleSnapshot snapshot = validated.snapshot();
    boolean exact = principal.tenantId().equals(existing.getTenantId())
        && principal.environment().equals(existing.getEnvironment())
        && principal.actorRef().equals(existing.getHostActorRef())
        && snapshot.getId().equals(existing.getSnapshotId())
        && snapshot.getSnapshotKey().equals(existing.getSnapshotKey())
        && snapshot.getContentHash().equals(existing.getSnapshotContentHash())
        && snapshot.getRuleSetKey().equals(existing.getRuleSetKey())
        && snapshot.getRuleSetVersion().equals(existing.getRuleSetVersion())
        && requested.activationRevision() == existing.getActivationRevision()
        && validated.outcome().equals(existing.getOutcome())
        && requested.durationMicros() == existing.getDurationMicros()
        && requested.observedAtUtc().equals(existing.getObservedAt());
    if (!exact) {
      throw new ConfigurationIngestionException(
          "observationId was already used with different execution evidence");
    }
  }

  private void requirePrincipal(DomainRuleGovernancePrincipal principal) {
    if (principal == null) {
      throw new ConfigurationIngestionException("Governed execution-observation principal is required");
    }
    requireText(principal.tenantId(), "tenantId", 128);
    requireText(principal.environment(), "environment", 128);
    requireText(principal.actorRef(), "actorRef", 255);
  }

  private String requireHash(String value) {
    String normalized = requireText(value, "snapshotContentHash", 64);
    if (!normalized.matches("[A-F0-9]{64}")) {
      throw new ConfigurationIngestionException("snapshotContentHash must be an uppercase SHA-256 digest");
    }
    return normalized;
  }

  private String requireOutcome(String value) {
    String normalized = requireText(value, "outcome", 32);
    if (!OUTCOMES.contains(normalized)) {
      throw new ConfigurationIngestionException("outcome is not supported");
    }
    return normalized;
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

  private record ValidatedObservation(
      DomainRuleSnapshot snapshot,
      DomainRuleExecutionObservationRequest observation,
      String outcome) {}
}
