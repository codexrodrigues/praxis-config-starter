package org.praxisplatform.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.ComponentDefinition;
import org.praxisplatform.config.repository.ComponentDefinitionRepository;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RegistryIngestionServiceTest {

    @Autowired
    private RegistryIngestionService registryIngestionService;

    @Autowired
    private ComponentDefinitionRepository repository;

    private static final String COMPONENT_ID = "demo-component";

    @BeforeEach
    void cleanBefore() {
        repository.deleteById(COMPONENT_ID);
    }

    @AfterEach
    void cleanAfter() {
        repository.deleteById(COMPONENT_ID);
    }

    @Test
    void shouldIngestRegistryAndPersistVector() {
        String registryJson = """
                {
                  "components": {
                    "demo-component": {
                      "description": "Demo component for ingestion test",
                      "inputs": {
                        "title": { "type": "string", "required": true }
                      },
                      "outputs": {}
                    }
                  }
                }
                """;

        registryIngestionService.ingestRegistry(registryJson);

        Optional<ComponentDefinition> stored = repository.findById(COMPONENT_ID);
        assertThat(stored).isPresent();
        assertThat(stored.get().getEmbedding()).isNotNull();
        assertThat(stored.get().getEmbedding()).isNotEmpty();
    }
}
