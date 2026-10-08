package org.praxisplatform.config.service;

/** Lifecycle failure vocabulary mapped to sanitized HTTP outcomes by the Config boundary. */
public class UiLayoutLifecycleException extends RuntimeException {
  public enum Code { SESSION_REQUIRED, CONTEXT_STALE, SOURCE_UNAVAILABLE, DENIED, NOT_FOUND, PRECONDITION_FAILED, INVALID_REQUEST, INVALID_STATE, INVALID_RELEASE, VALIDATION_FAILED }
  private final Code code;

  public UiLayoutLifecycleException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public Code getCode() { return code; }
}
