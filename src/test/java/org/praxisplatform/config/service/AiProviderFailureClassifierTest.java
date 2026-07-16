package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AiProviderFailureClassifierTest {

    @Test
    void preservesNormalizedProviderFailureAcrossWrappedCauses() {
        RuntimeException wrapped = new RuntimeException(
                "outer",
                AiProviderCallException.transport("openai", new ConnectException("refused")));

        assertThat(AiProviderFailureClassifier.classify(wrapped)).isEqualTo("transport");
    }
}
