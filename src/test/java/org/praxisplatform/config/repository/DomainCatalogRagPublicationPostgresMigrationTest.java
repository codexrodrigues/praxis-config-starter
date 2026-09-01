package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.rag.RagEmbeddingProfile;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("integration")
class DomainCatalogRagPublicationPostgresMigrationTest {

    @Test
    void installsPublicationLifecycleConstraintsAndReleaseCascadeOnRealPostgres() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setCleanDataDirectory(true)
                .setRegisterShutdownHook(false)
                .start();
                Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table domain_catalog_release (id uuid primary key)");
            statement.execute(Files.readString(Path.of(
                    "src/main/resources/db/migration/V60__create_domain_catalog_rag_publication_state.sql")));

            UUID releaseId = UUID.randomUUID();
            statement.executeUpdate("insert into domain_catalog_release (id) values ('" + releaseId + "')");
            statement.executeUpdate("""
                    insert into domain_catalog_rag_publication_state (
                        release_id, status, requested_at, updated_at
                    ) values (
                        '%s', 'PENDING', current_timestamp, current_timestamp
                    )
                    """.formatted(releaseId));

            assertThat(count(statement, "domain_catalog_rag_publication_state")).isEqualTo(1L);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    update domain_catalog_rag_publication_state
                    set status = 'UNKNOWN'
                    where release_id = '%s'
                    """.formatted(releaseId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_domain_catalog_rag_publication_status");

            statement.executeUpdate("delete from domain_catalog_release where id = '" + releaseId + "'");
            assertThat(count(statement, "domain_catalog_rag_publication_state")).isZero();
        }
    }

    @Test
    void reconcilesLegacyPhysicalIdBeforeCanonicalVectorIdentityUpsertOnRealPostgres() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setCleanDataDirectory(true)
                .setRegisterShutdownHook(false)
                .start();
                Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table vector_store (
                        id text primary key,
                        content text,
                        metadata jsonb
                    )
                    """);
            statement.execute(Files.readString(Path.of(
                    "src/main/resources/db/migration/V16__harden_vector_store_release_hash_uniques.sql")));

            JdbcTemplate jdbcTemplate = new JdbcTemplate(postgres.getPostgresDatabase());
            insertVectorDocument(jdbcTemplate, "legacy-order-derived-id", "prod");
            insertVectorDocument(jdbcTemplate, "other-environment-id", "staging");

            assertThatThrownBy(() -> insertVectorDocument(jdbcTemplate, "canonical-id", "prod"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("idx_vector_store_scope_release_hash_chunk_unique");

            NamedParameterJdbcTemplate namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
            ObjectProvider<VectorStore> vectorStoreProvider = objectProviderReturning(mock(VectorStore.class));
            ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider = objectProviderReturning(namedJdbcTemplate);
            RagVectorStoreService service = new RagVectorStoreService(
                    vectorStoreProvider,
                    jdbcTemplateProvider,
                    new RagEmbeddingProfile(),
                    "vector_store");
            Document replacement = canonicalDocument("canonical-id", "prod");

            service.deleteDocumentByCanonicalContentIdentity(replacement);

            assertThat(vectorDocumentIds(jdbcTemplate)).containsExactly("other-environment-id");
            insertVectorDocument(jdbcTemplate, "canonical-id", "prod");

            service.deleteDocumentByCanonicalContentIdentity(replacement);

            assertThat(vectorDocumentIds(jdbcTemplate))
                    .containsExactly("canonical-id", "other-environment-id");
        }
    }

    private void insertVectorDocument(JdbcTemplate jdbcTemplate, String id, String environment) {
        jdbcTemplate.update("""
                insert into vector_store (id, content, metadata)
                values (
                    ?,
                    'governed catalog content',
                    jsonb_build_object(
                        'tenantId', 'tenant-a',
                        'environment', ?,
                        'releaseId', 'release-normalized',
                        'componentId', 'operations.missoes',
                        'docType', 'domain_catalog',
                        'contentHash', 'content-hash',
                        'chunkIndex', 0
                    )
                )
                """, id, environment);
    }

    private Document canonicalDocument(String id, String environment) {
        return Document.builder()
                .id(id)
                .text("governed catalog content")
                .metadata(Map.of(
                        RagMetadataKeys.TENANT_ID, "tenant-a",
                        RagMetadataKeys.ENVIRONMENT, environment,
                        RagMetadataKeys.RELEASE_ID, "release-normalized",
                        RagMetadataKeys.COMPONENT_ID, "operations.missoes",
                        RagMetadataKeys.DOC_TYPE, RagResourceTypes.DOMAIN_CATALOG,
                        RagMetadataKeys.CONTENT_HASH, "content-hash",
                        RagMetadataKeys.CHUNK_INDEX, 0))
                .build();
    }

    private List<String> vectorDocumentIds(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList("select id from vector_store order by id", String.class);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> objectProviderReturning(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private long count(Statement statement, String table) throws SQLException {
        try (ResultSet result = statement.executeQuery("select count(*) from " + table)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
