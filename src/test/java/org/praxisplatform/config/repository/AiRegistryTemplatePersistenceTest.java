package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiRegistryTemplateService;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.praxisplatform.config.service.EmbeddingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/** JPA roundtrip using the existing H2 vector fixture; not PostgreSQL similarity proof. */
@DataJpaTest
@ContextConfiguration(classes = PraxisConfigStarterApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:template_recovery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:domain-catalog-scope-repository-test-schema.sql",
    "spring.flyway.enabled=false"
})
@Tag("integration")
class AiRegistryTemplatePersistenceTest {
  @Autowired private AiRegistryRepository repository;
  @Autowired private EntityManager entityManager;

  @Test
  void reloadsUnindexedDocumentAndThenRecoveredProjectionWithStableIdentity() {
    var mapper = new ObjectMapper();
    var embeddings = mock(EmbeddingService.class);
    var service = new AiRegistryTemplateService(repository, mapper, embeddings,
        new CanonicalJsonHashService(mapper));
    when(embeddings.embed(anyString()))
        .thenThrow(AiProviderCallException.fromHttpStatus("openai", 503, "unavailable"))
        .thenReturn(List.of(0.1f, 0.2f));
    var initial = service.upsertTemplate("test-template", mapper.createObjectNode(), "Test", null);
    repository.flush();
    var id = initial.getId();
    var etag = initial.getEtag();
    entityManager.clear();
    var reloaded = service.getTemplate("test-template").orElseThrow();
    assertThat(reloaded.getId()).isEqualTo(id);
    assertThat(reloaded.getEmbedding()).isNull();
    assertThat(reloaded.getVersion()).isEqualTo(1L);
    assertThat(reloaded.getEtag()).isEqualTo(etag);
    assertThat(service.toRecord(reloaded).getConfigJson()).isEqualTo(mapper.createObjectNode());

    service.upsertTemplate("test-template", mapper.createObjectNode(), "Test", null);
    repository.flush();
    entityManager.clear();
    var recovered = service.getTemplate("test-template").orElseThrow();
    assertThat(recovered.getId()).isEqualTo(id);
    assertThat(recovered.getEmbedding()).containsExactly(0.1f, 0.2f);
    assertThat(recovered.getVersion()).isEqualTo(2L);
    assertThat(recovered.getEtag()).isNotEqualTo(etag);
    assertThat(service.toRecord(recovered).getRevision().getConfigSha256())
        .isEqualTo(service.toRecord(reloaded).getRevision().getConfigSha256());
  }
}
