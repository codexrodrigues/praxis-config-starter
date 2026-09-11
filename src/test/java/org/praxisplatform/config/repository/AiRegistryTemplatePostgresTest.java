package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Collections;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiRegistryTemplateService;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.praxisplatform.config.service.EmbeddingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/** Real Flyway/JPA/pgvector proof on the explicitly disposable workflow database. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never"})
@ContextConfiguration(classes = PraxisConfigStarterApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AiRegistryTemplatePostgresTest.Config.class, AiRegistryTemplateService.class,
    CanonicalJsonHashService.class})
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_EPHEMERAL", matches = "true")
@Tag("integration")
class AiRegistryTemplatePostgresTest {
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> required("POSTGRES_TEST_URL"));
    properties.add("spring.datasource.username", () -> required("POSTGRES_TEST_USER"));
    properties.add("spring.datasource.password", () -> required("POSTGRES_TEST_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("spring.flyway.enabled", () -> "true");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
    return value;
  }

  @TestConfiguration
  static class Config {
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    @Bean(name = {"transactionManager", "configTransactionManager"})
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }
  }

  @Autowired AiRegistryTemplateService service;
  @Autowired AiRegistryRepository repository;
  @Autowired EntityManager entityManager;
  @Autowired ObjectMapper mapper;
  @MockBean EmbeddingService embeddings;

  @Test
  void persistsUnavailableTemplateThenRecoversItsSearchProjection() {
    var vector = Collections.nCopies(768, 0.1f);
    when(embeddings.embed(anyString()))
        .thenThrow(AiProviderCallException.fromHttpStatus("openai", 503, "unavailable"))
        .thenReturn(vector);
    when(embeddings.embed(anyString(), org.mockito.ArgumentMatchers.isNull())).thenReturn(vector);
    String key = "postgres-template-recovery";
    var first = service.upsertTemplate(key, mapper.createObjectNode(), "Recovery", null);
    repository.flush();
    var id = first.getId();
    var etag = first.getEtag();
    entityManager.clear();
    var missing = service.getTemplate(key).orElseThrow();
    assertThat(missing.getEmbedding()).isNull();
    assertThat(service.searchTemplates("Recovery", key, 5)).isEmpty();
    assertThat(missing.getVersion()).isEqualTo(1L);
    assertThat(missing.getEtag()).isEqualTo(etag);

    service.upsertTemplate(key, mapper.createObjectNode(), "Recovery", null);
    repository.flush();
    entityManager.clear();
    var recovered = service.getTemplate(key).orElseThrow();
    assertThat(recovered.getId()).isEqualTo(id);
    assertThat(recovered.getVersion()).isEqualTo(2L);
    assertThat(recovered.getEtag()).isNotEqualTo(etag);
    assertThat(service.searchTemplates("Recovery", key, 5)).singleElement()
        .satisfies(result -> assertThat(result.getComponentId()).isEqualTo(key));
    var stableEtag = recovered.getEtag();
    service.upsertTemplate(key, mapper.createObjectNode(), "Recovery", null);
    repository.flush();
    entityManager.clear();
    assertThat(service.getTemplate(key).orElseThrow().getEtag()).isEqualTo(stableEtag);
  }
}
