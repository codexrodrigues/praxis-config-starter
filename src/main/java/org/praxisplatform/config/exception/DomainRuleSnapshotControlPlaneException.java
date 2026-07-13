package org.praxisplatform.config.exception;

import org.springframework.http.HttpStatus;

/** Explicit control-plane failure with stable HTTP semantics. */
public class DomainRuleSnapshotControlPlaneException extends RuntimeException {
  private final HttpStatus status;

  public DomainRuleSnapshotControlPlaneException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
