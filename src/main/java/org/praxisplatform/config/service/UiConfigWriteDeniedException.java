package org.praxisplatform.config.service;

import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exceção HTTP estruturada para recusas da borda de escrita em {@code ui_user_config},
 * preservando um {@code errorCode} de plataforma nas respostas JSON/{@code ProblemDetail}.
 */
public class UiConfigWriteDeniedException extends ResponseStatusException {

    private final String errorCode;

    public UiConfigWriteDeniedException(HttpStatusCode status, String errorCode, String reason) {
        super(status, reason);
        this.errorCode = StringUtils.hasText(errorCode) ? errorCode.trim() : null;
        if (this.errorCode != null) {
            getBody().setProperty("errorCode", this.errorCode);
        }
    }

    public UiConfigWriteDeniedException(
            HttpStatusCode status, String errorCode, String reason, Throwable cause) {
        super(status, reason, cause);
        this.errorCode = StringUtils.hasText(errorCode) ? errorCode.trim() : null;
        if (this.errorCode != null) {
            getBody().setProperty("errorCode", this.errorCode);
        }
    }

    public String getErrorCode() {
        return errorCode;
    }
}
