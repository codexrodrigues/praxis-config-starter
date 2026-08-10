package org.praxisplatform.config.service;

import java.util.Objects;
import java.util.Optional;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHead;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadActivationType;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadReader;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadScope;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;

/** Adapts the governed Config Starter head to the framework-neutral host read port. */
public final class DomainRuleSnapshotHeadReaderAdapter implements PublishedRuleSnapshotHeadReader {
  private final DomainRuleSnapshotService snapshotService;

  public DomainRuleSnapshotHeadReaderAdapter(DomainRuleSnapshotService snapshotService) {
    this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService is required");
  }

  @Override
  public Optional<PublishedRuleSnapshotHead> findActive(PublishedRuleSnapshotHeadScope scope) {
    Objects.requireNonNull(scope, "scope is required");
    return snapshotService.findActive(scope.tenantId(), scope.environment(), scope.ruleSetKey())
        .map(DomainRuleSnapshotHeadReaderAdapter::toPublishedHead);
  }

  private static PublishedRuleSnapshotHead toPublishedHead(
      DomainRuleSnapshotActivationResponse response) {
    return new PublishedRuleSnapshotHead(
        response.snapshot(),
        response.snapshotContentHash(),
        response.headEtag(),
        response.activationRevision(),
        PublishedRuleSnapshotHeadActivationType.valueOf(response.activationType()));
  }
}
