package org.praxisplatform.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RegistryIngestionServiceTest {

    @Autowired
    private RegistryIngestionService registryIngestionService;

    @Autowired
    private AiRegistryRepository repository;

    private static final String COMPONENT_ID = "demo-component";
    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";

    @BeforeEach
    void cleanBefore() {
        deleteExisting();
    }

    @AfterEach
    void cleanAfter() {
        deleteExisting();
    }

    private void deleteExisting() {
        Optional<AiRegistry> existing =
                repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        REGISTRY_TYPE_COMPONENT_DEF,
                        COMPONENT_ID,
                        COMPONENT_DEF_COMPONENT_TYPE,
                        Scope.SYSTEM,
                        SYSTEM_SCOPE_KEY);
        existing.ifPresent(repository::delete);
    }

    @Test
    void shouldIngestRegistryAndPersistVector() {
        // Build the DTO manually for the test
        RegistryIngestionRequest.IoEntry inputIo = RegistryIngestionRequest.IoEntry.builder()
            .name("title")
            .type("string")
            .required(true)
            .build();

        RegistryIngestionRequest.ComponentEntry componentEntry = RegistryIngestionRequest.ComponentEntry.builder()
            .description("Demo component for ingestion test")
            .inputs(List.of(inputIo))
            .outputs(List.of()) // Assuming outputs is a list, based on the DTO
            .build();

        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
            .components(Map.of(COMPONENT_ID, componentEntry))
            .build();

        registryIngestionService.ingestRegistry(request); // Chamada atualizada

        Optional<AiRegistry> stored =
                repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        REGISTRY_TYPE_COMPONENT_DEF,
                        COMPONENT_ID,
                        COMPONENT_DEF_COMPONENT_TYPE,
                        Scope.SYSTEM,
                        SYSTEM_SCOPE_KEY);
        assertThat(stored).isPresent();
        assertThat(stored.get().getEmbedding()).isNotNull();
        assertThat(stored.get().getEmbedding()).isNotEmpty();
    }
}
