package org.praxisplatform.config.controller;

import org.praxisplatform.config.service.UiLayoutResolutionException;
import org.praxisplatform.config.service.UiLayoutLifecycleException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;

/** Maps resolution failures to stable sanitized HTTP outcomes. */
@RestControllerAdvice(assignableTypes = {EffectiveUiLayoutController.class, EffectiveUiLayoutCompositionController.class, UiLayoutLifecycleController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UiLayoutResolutionExceptionHandler {
    @ExceptionHandler(UiLayoutRevisionRequestBodyAdvice.BodyTooLarge.class)
    public ResponseEntity<ProblemDetail> revisionBodyTooLarge(UiLayoutRevisionRequestBodyAdvice.BodyTooLarge failure) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "The revision request body exceeds the supported byte budget.");
        body.setTitle("UI layout revision request rejected");
        body.setProperty("code", "REVISION_BODY_TOO_LARGE");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).cacheControl(CacheControl.noStore()).body(body);
    }
    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> missingInput(Exception failure) {
        return invalidRequest(new IllegalArgumentException("Required layout request input is missing."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(IllegalArgumentException failure) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The layout target or conditional request is invalid.");
        body.setTitle("Effective UI layout request rejected");
        body.setProperty("code", "INVALID_LAYOUT_REQUEST");
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(UiLayoutResolutionException.class)
    public ResponseEntity<ProblemDetail> handle(UiLayoutResolutionException failure) {
        HttpStatus status = switch (failure.code()) {
            case SESSION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case CONTEXT_ACCESS_DENIED, LAYOUT_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case CONTEXT_STALE -> HttpStatus.CONFLICT;
            case LAYOUT_RESOLUTION_AMBIGUOUS, PROTECTED_PRESENTATION_INVARIANT ->
                HttpStatus.UNPROCESSABLE_ENTITY;
            case AUDIENCE_SOURCE_UNAVAILABLE, LAYOUT_SOURCE_UNAVAILABLE, LAYOUT_REVISION_INTEGRITY ->
                HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, safeDetail(failure.code()));
        body.setTitle("Effective UI layout resolution failed");
        body.setProperty("code", failure.code().name());
        if (failure.path() != null) body.setProperty("path", failure.path());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(UiLayoutLifecycleException.class)
    public ResponseEntity<ProblemDetail> lifecycle(UiLayoutLifecycleException failure) {
        HttpStatus status = switch (failure.getCode()) {
            case SESSION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONTEXT_STALE, INVALID_STATE -> HttpStatus.CONFLICT;
            case PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INVALID_RELEASE, VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case SOURCE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, lifecycleDetail(failure.getCode()));
        body.setTitle("UI layout lifecycle request failed");
        body.setProperty("code", failure.getCode().name());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private String safeDetail(UiLayoutResolutionException.Code code) {
        return switch (code) {
            case SESSION_REQUIRED -> "Authenticate again before resolving this layout.";
            case CONTEXT_ACCESS_DENIED -> "Select an authorized enterprise context before resolving this layout.";
            case LAYOUT_ACCESS_DENIED -> "The current enterprise context cannot consume this layout.";
            case CONTEXT_STALE -> "Refresh the runtime context before resolving this layout.";
            case LAYOUT_RESOLUTION_AMBIGUOUS -> "Published layout inputs are ambiguous.";
            case PROTECTED_PRESENTATION_INVARIANT -> "A published layout violates presentation policy.";
            case AUDIENCE_SOURCE_UNAVAILABLE -> "The authoritative audience source is unavailable.";
            case LAYOUT_SOURCE_UNAVAILABLE -> "The authoritative layout source is unavailable.";
            case LAYOUT_REVISION_INTEGRITY -> "A published layout revision failed integrity verification.";
        };
    }

    private String lifecycleDetail(UiLayoutLifecycleException.Code code) {
        return switch (code) {
            case SESSION_REQUIRED -> "Authenticate again before changing the layout lifecycle.";
            case CONTEXT_STALE -> "Refresh the runtime context before changing the layout lifecycle.";
            case SOURCE_UNAVAILABLE -> "The authoritative lifecycle source is unavailable.";
            case DENIED -> "The current enterprise context cannot perform this lifecycle operation.";
            case NOT_FOUND -> "The requested lifecycle state is unavailable in the authorized scope.";
            case PRECONDITION_FAILED -> "Refresh the mutable lifecycle representation before retrying.";
            case INVALID_REQUEST -> "The lifecycle request is malformed or contains unsupported fields.";
            case INVALID_STATE -> "The lifecycle operation is not valid for the current state.";
            case INVALID_RELEASE, VALIDATION_FAILED -> "The lifecycle command does not satisfy the published layout policy.";
        };
    }
}
