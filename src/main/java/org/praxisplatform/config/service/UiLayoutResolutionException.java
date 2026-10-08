package org.praxisplatform.config.service;

public final class UiLayoutResolutionException extends RuntimeException {
    private final Code code;
    private final String path;

    public UiLayoutResolutionException(Code code, String message, String path) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public Code code() {
        return code;
    }

    public String path() {
        return path;
    }

    public enum Code {
        SESSION_REQUIRED,
        CONTEXT_ACCESS_DENIED,
        LAYOUT_ACCESS_DENIED,
        CONTEXT_STALE,
        AUDIENCE_SOURCE_UNAVAILABLE,
        LAYOUT_SOURCE_UNAVAILABLE,
        LAYOUT_RESOLUTION_AMBIGUOUS,
        LAYOUT_REVISION_INTEGRITY,
        PROTECTED_PRESENTATION_INVARIANT
    }
}
