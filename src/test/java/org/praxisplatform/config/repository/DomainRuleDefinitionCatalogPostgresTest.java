package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** PostgreSQL contract proof for the optional full-text catalog filter. */
@DataJpaTest
@ContextConfiguration(classes = PraxisConfigStarterApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class DomainRuleDefinitionCatalogPostgresTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    DomainRuleDefinitionRepository repository;

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Test
    void emptyQuerySentinelEnumeratesScopeWithoutPostgresTypeInferenceFailure() {
        assertThat(repository.searchCatalogCandidates(
                "tenant-a", "prod", "", null, null, null, PageRequest.of(0, 6)))
                .isEmpty();
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
