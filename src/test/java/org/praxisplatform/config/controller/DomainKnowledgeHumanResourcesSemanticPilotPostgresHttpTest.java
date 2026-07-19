package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.repository.DomainKnowledgeAliasRepository;
import org.praxisplatform.config.repository.DomainKnowledgeBindingRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.praxisplatform.config.repository.DomainKnowledgeRelationshipRepository;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringProjectKnowledgeQuery;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringProjectKnowledgeService;
import org.praxisplatform.config.service.DomainKnowledgeChangeSetService;
import org.praxisplatform.config.service.DomainKnowledgeChangeSetValidator;
import org.praxisplatform.config.service.AiSensitiveDataRedactor;
import org.praxisplatform.config.service.ProjectKnowledgeDerivedIndexService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = {
                TestApplication.class,
                DomainKnowledgeHumanResourcesSemanticPilotPostgresHttpTest.DomainKnowledgeTestConfiguration.class
        },
        properties = {
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=false",
                "spring.ai.openai.api-key=dummy",
                "spring.autoconfigure.exclude="
                        + "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration,"
                        + "org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration,"
                        + "org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration",
                "spring.ai.vectorstore.pgvector.initialize-schema=false",
                "spring.ai.vectorstore.pgvector.vector-table-validations-enabled=false",
                "praxis.domain-360.enabled=false",
                "praxis.domain-federation.enabled=false",
                "praxis.ai.rag.vector-store.enabled=false",
                "praxis.ai.registry.bootstrap.enabled=false"
        })
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("integration")
class DomainKnowledgeHumanResourcesSemanticPilotPostgresHttpTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class DomainKnowledgeTestConfiguration {

        @Bean
        DomainKnowledgeChangeSetService domainKnowledgeChangeSetService(
                DomainKnowledgeChangeSetRepository changeSetRepository,
                DomainKnowledgeConceptRepository conceptRepository,
                DomainKnowledgeAliasRepository aliasRepository,
                DomainKnowledgeBindingRepository bindingRepository,
                DomainKnowledgeRelationshipRepository relationshipRepository,
                DomainKnowledgeEvidenceRepository evidenceRepository,
                DomainKnowledgeChangeSetValidator validator,
                ObjectMapper objectMapper,
                ObjectProvider<ProjectKnowledgeDerivedIndexService> derivedIndexService) {
            return new DomainKnowledgeChangeSetService(
                    changeSetRepository,
                    conceptRepository,
                    aliasRepository,
                    bindingRepository,
                    relationshipRepository,
                    evidenceRepository,
                    validator,
                    objectMapper,
                    derivedIndexService);
        }

        @Bean
        AgenticAuthoringProjectKnowledgeService agenticAuthoringProjectKnowledgeService(
                DomainKnowledgeConceptRepository conceptRepository,
                DomainKnowledgeEvidenceRepository evidenceRepository,
                ObjectMapper objectMapper,
                AiSensitiveDataRedactor redactor) {
            return new AgenticAuthoringProjectKnowledgeService(
                    conceptRepository, evidenceRepository, objectMapper, redactor);
        }
    }

    private static final String TENANT = "desenv";
    private static final String ENVIRONMENT = "local";
    private static final Path PILOT = Path.of(
            "docs",
            "ai",
            "agentic-authoring",
            "proofs",
            "human-resources-semantic-pilot-change-set.v0.1.json");
    private static final List<String> DOMAIN_KNOWLEDGE_MIGRATIONS = List.of(
            "V18__create_domain_knowledge_layer.sql",
            "V19__align_domain_knowledge_constraints_with_catalog_v02.sql",
            "V26__add_domain_knowledge_evidence_lifecycle.sql",
            "V38__expand_domain_knowledge_semantic_ir_relationships.sql");
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AgenticAuthoringProjectKnowledgeService projectKnowledgeService;
    @Autowired private DomainKnowledgeBindingRepository bindingRepository;
    @Autowired private DomainKnowledgeEvidenceRepository evidenceRepository;

    @MockBean private ProjectKnowledgeDerivedIndexService projectKnowledgeDerivedIndexService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Test
    void completesCreateValidateReviewApplyAndQueryAgainstIsolatedPostgres() throws Exception {
        JsonNode created = objectMapper.readTree(mockMvc.perform(post(
                                "/api/praxis/config/domain-knowledge/change-sets")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-Env", ENVIRONMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(PILOT)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("proposed"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        UUID changeSetId = UUID.fromString(created.path("id").asText());

        mockMvc.perform(post("/api/praxis/config/domain-knowledge/change-sets/{id}/validate", changeSetId)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-Env", ENVIRONMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.nonExecutableOperationTypes").isEmpty());

        mockMvc.perform(patch("/api/praxis/config/domain-knowledge/change-sets/{id}/status", changeSetId)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-Env", ENVIRONMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"approved","reviewerId":"reviewer:semantic-governance"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        mockMvc.perform(post("/api/praxis/config/domain-knowledge/change-sets/{id}/apply", changeSetId)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-Env", ENVIRONMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"));

        mockMvc.perform(get("/api/praxis/config/domain-knowledge/change-sets/{id}", changeSetId)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-Env", ENVIRONMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"));
        mockMvc.perform(get("/api/praxis/config/domain-knowledge/change-sets/{id}", changeSetId)
                        .header("X-Tenant-ID", "other-tenant")
                        .header("X-Env", ENVIRONMENT))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/praxis/config/domain-knowledge/change-sets/{id}/timeline", changeSetId)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-Env", "other-environment"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/praxis/config/domain-knowledge/change-sets/{id}/timeline", changeSetId)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-Env", ENVIRONMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventType").value("change_set.created"));

        assertThat(count("domain_knowledge_concept", "context_key", "human-resources")).isEqualTo(3);
        assertThat(count("domain_knowledge_alias", "normalized_alias", "recursos humanos")).isEqualTo(1);
        assertThat(bindingRepository.findByTenantIdAndEnvironmentAndBindingTypeAndBindingKey(
                        TENANT, ENVIRONMENT, "api_resource", "human-resources.funcionarios"))
                .hasSize(1);
        assertThat(count("domain_knowledge_relationship", "relationship_type", "measured_by")).isEqualTo(1);
        assertThat(evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKey(
                        TENANT,
                        ENVIRONMENT,
                        "claim:human-resources:capability:workforce-management:v0.1"))
                .hasSize(1);

        assertThat(projectKnowledgeService.retrieve(new AgenticAuthoringProjectKnowledgeQuery(
                        TENANT, ENVIRONMENT, null, null, List.of("context"), "context", 4)))
                .singleElement()
                .satisfies(projection -> {
                    assertThat(projection.conceptKey()).isEqualTo("human-resources.context");
                    assertThat(projection.kind()).isEqualTo("context");
                });
        assertThat(projectKnowledgeService.retrieve(new AgenticAuthoringProjectKnowledgeQuery(
                        TENANT,
                        ENVIRONMENT,
                        "human-resources",
                        null,
                        List.of("context", "business_capability", "metric"),
                        null,
                        8)))
                .extracting(projection -> projection.kind())
                .containsExactlyInAnyOrder("context", "business_capability", "metric");
    }

    private Integer count(String table, String field, String value) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table
                        + " where tenant_id = ? and environment = ? and " + field + " = ?",
                Integer.class,
                TENANT,
                ENVIRONMENT,
                value);
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                    .setCleanDataDirectory(true)
                    .setRegisterShutdownHook(false)
                    .start();
            prepareSchema(postgres);
            return postgres;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void prepareSchema(EmbeddedPostgres postgres) throws Exception {
        String jdbcUrl = postgres.getJdbcUrl("postgres", "postgres");
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE domain_catalog_release (
                        id UUID PRIMARY KEY,
                        release_key VARCHAR(255) NOT NULL,
                        schema_version VARCHAR(64) NOT NULL,
                        service_key VARCHAR(255),
                        service_name VARCHAR(255),
                        service_version VARCHAR(64),
                        resource_key VARCHAR(255),
                        generated_at TIMESTAMPTZ,
                        source_hash VARCHAR(128),
                        tenant_id VARCHAR(128),
                        environment VARCHAR(128),
                        raw_payload JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
        }
        Path migrations = Files.createTempDirectory("praxis-domain-knowledge-migrations-");
        for (String migration : DOMAIN_KNOWLEDGE_MIGRATIONS) {
            try (var source = DomainKnowledgeHumanResourcesSemanticPilotPostgresHttpTest.class
                    .getResourceAsStream("/db/migration/" + migration)) {
                if (source == null) {
                    throw new IllegalStateException("Missing canonical migration " + migration);
                }
                Files.copy(source, migrations.resolve(migration));
            }
        }
        Flyway.configure()
                .dataSource(jdbcUrl, "postgres", "")
                .locations("filesystem:" + migrations.toAbsolutePath())
                .baselineOnMigrate(true)
                .baselineVersion("17")
                .load()
                .migrate();
    }
}
