package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;

@Tag("unit")
class RagPublicationFailureClassifierTest {

    @Test
    void preservesProviderTaxonomyRetryabilityAndRetryAfter() {
        Instant retryAfter = Instant.parse("2026-09-01T07:00:00Z");
        RuntimeException failure = new RuntimeException(AiProviderCallException.fromHttpStatus(
                "gemini", 429, "rate_limit", retryAfter, null));

        assertThat(RagPublicationFailureClassifier.classify(failure))
                .isEqualTo(new RagPublicationFailureClassifier.FailureEvidence(
                        "rate_limit", true, retryAfter));
    }

    @Test
    void classifiesVectorStoreIntegrityFailuresAsTerminal() {
        RuntimeException failure = new RuntimeException(
                "wrapped",
                new DataIntegrityViolationException("canonical identity collision"));

        assertThat(RagPublicationFailureClassifier.classify(failure))
                .isEqualTo(new RagPublicationFailureClassifier.FailureEvidence(
                        "vector_store_integrity", false, null));
    }

    @Test
    void retriesOnlyKnownTransientVectorStoreFailures() {
        RuntimeException failure = new TransientDataAccessResourceException("database unavailable");

        assertThat(RagPublicationFailureClassifier.classify(failure))
                .isEqualTo(new RagPublicationFailureClassifier.FailureEvidence(
                        "vector_store_transient", true, null));
    }

    @Test
    void classifiesUntypedFailuresAsTerminalWithoutLeakingMessages() {
        RuntimeException failure = new RuntimeException("sensitive internal details");

        assertThat(RagPublicationFailureClassifier.classify(failure))
                .isEqualTo(new RagPublicationFailureClassifier.FailureEvidence(
                        "rag_publication_internal", false, null));
    }

    @Test
    void preservesTransportRetryabilityAcrossWrappedCauses() {
        RuntimeException failure = new RuntimeException(new ConnectException("refused"));

        assertThat(RagPublicationFailureClassifier.classify(failure))
                .isEqualTo(new RagPublicationFailureClassifier.FailureEvidence(
                        "transport", true, null));
    }
}
