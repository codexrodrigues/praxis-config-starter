package org.praxisplatform.config.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** Maps provider failures to a small operational taxonomy without exposing failure messages. */
public final class AiProviderFailureClassifier {

    private AiProviderFailureClassifier() {
    }

    public static String classify(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AiProviderCallException providerError && providerError.getKind() != null) {
                return providerError.getKind().name().toLowerCase(Locale.ROOT);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        Throwable cause = rootCause(error);
        if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
            return "timeout";
        }
        if (cause instanceof ConnectException) {
            return "transport";
        }
        return "unknown";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
