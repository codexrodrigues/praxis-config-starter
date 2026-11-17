package org.praxisplatform.config.service;

/** Lifecycle failure vocabulary mapped to sanitized HTTP outcomes by the Config boundary. */
public class UiLayoutLifecycleException extends RuntimeException {
  public enum Code { SESSION_REQUIRED, CONTEXT_STALE, SOURCE_UNAVAILABLE, DENIED, NOT_FOUND, PRECONDITION_FAILED, INVALID_REQUEST, INVALID_STATE, INVALID_RELEASE, VALIDATION_FAILED }
  private final Code code;
  private final String errorCode;

  public UiLayoutLifecycleException(Code code, String message) {
    this(code, null, message, null);
  }

  public UiLayoutLifecycleException(Code code, String errorCode, String message) {
    this(code, errorCode, message, null);
  }

  public UiLayoutLifecycleException(Code code, String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.errorCode = normalizeErrorCode(errorCode);
  }

  public Code getCode() { return code; }

  public String getErrorCode() { return errorCode; }

  private static String normalizeErrorCode(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
