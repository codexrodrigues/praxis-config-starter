package org.praxisplatform.config.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Classifies terminal RAG publication failures without exposing provider, JDBC, or document
 * contents to operational consumers.
 */
final class RagPublicationFailureClassifier {

    private RagPublicationFailureClassifier() {
    }

    static FailureEvidence classify(Throwable failure) {
        AiProviderCallException providerFailure = findCause(failure, AiProviderCallException.class);
        if (providerFailure != null) {
            boolean retryable = switch (providerFailure.getKind()) {
                case RATE_LIMIT, CAPACITY, SERVER_ERROR, TRANSPORT, TIMEOUT -> true;
                case QUOTA_EXHAUSTED, AUTH, CLIENT_ERROR, UNKNOWN -> false;
            };
            return new FailureEvidence(
                    providerFailure.getKind().name().toLowerCase(Locale.ROOT),
                    retryable,
                    providerFailure.getRetryAfter());
        }

        if (findCause(failure, DataIntegrityViolationException.class) != null) {
            return new FailureEvidence("vector_store_integrity", false, null);
        }
        if (findCause(failure, TransientDataAccessException.class) != null
                || findCause(failure, RecoverableDataAccessException.class) != null) {
            return new FailureEvidence("vector_store_transient", true, null);
        }
        if (findCause(failure, DataAccessException.class) != null) {
            return new FailureEvidence("vector_store_failure", false, null);
        }

        Throwable rootCause = rootCause(failure);
        if (rootCause instanceof TimeoutException || rootCause instanceof SocketTimeoutException) {
            return new FailureEvidence("timeout", true, null);
        }
        if (rootCause instanceof ConnectException) {
            return new FailureEvidence("transport", true, null);
        }
        if (rootCause instanceof IllegalArgumentException || rootCause instanceof IllegalStateException) {
            return new FailureEvidence("rag_publication_contract", false, null);
        }
        return new FailureEvidence("rag_publication_internal", false, null);
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    record FailureEvidence(String kind, boolean retryable, Instant retryAfter) {
    }
}
