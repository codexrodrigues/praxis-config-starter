package org.praxisplatform.config.exception;

import java.util.List;
import org.praxisplatform.config.dto.DomainRuleSnapshotBlocker;
import org.springframework.http.HttpStatus;

/** Explicit control-plane failure with stable HTTP semantics. */
public class DomainRuleSnapshotControlPlaneException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final List<DomainRuleSnapshotBlocker> blockers;

  public DomainRuleSnapshotControlPlaneException(HttpStatus status, String message) {
    this(status, status.name(), message, List.of());
  }

  public DomainRuleSnapshotControlPlaneException(
      HttpStatus status,
      String code,
      String message,
      List<DomainRuleSnapshotBlocker> blockers) {
    super(message);
    this.status = status;
    this.code = code;
    this.blockers = blockers == null ? List.of() : List.copyOf(blockers);
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<DomainRuleSnapshotBlocker> blockers() {
    return blockers;
  }
}
