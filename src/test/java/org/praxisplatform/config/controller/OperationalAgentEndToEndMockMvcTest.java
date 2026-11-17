package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AgentToolCall;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamRequest;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityCatalogContributor;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@MockBean(classes = {
        org.praxisplatform.config.controller.GovernedColorPaletteController.class,
        org.praxisplatform.config.controller.DomainRuleChangeWorkspaceController.class,
        org.praxisplatform.config.controller.DomainRuleRolloutController.class,
        org.praxisplatform.config.controller.DomainRuleRolloutPolicyController.class,
        org.praxisplatform.config.controller.DomainRuleSnapshotController.class,
        org.praxisplatform.config.controller.DomainRuleHostStatusController.class,
        org.praxisplatform.config.controller.DomainRuleExecutionObservationController.class,
        org.praxisplatform.config.controller.DomainRuleController.class,
})
@SpringBootTest(
        classes = {TestApplication.class, OperationalAgentEndToEndMockMvcTest.StubDomainConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:operational_agent_e2e_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:ai-stream-it-schema.sql",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
                        + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
                "spring.ai.openai.api-key=dummy",
                "spring.ai.vectorstore.pgvector.initialize-schema=false",
                "spring.ai.vectorstore.pgvector.vector-table-validations-enabled=false",
                "praxis.domain-360.enabled=false",
                "praxis.domain-federation.enabled=false",
                "praxis.domain-knowledge.change-sets.enabled=false",
                "praxis.ai.agent.http-enabled=true",
                "praxis.ai.rag.vector-store.enabled=false",
                "praxis.ai.registry.bootstrap.enabled=false",
                "praxis.ai.security.corporate-mode=true",
                "praxis.ai.stream.heartbeat-seconds=0"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {
        GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
        GoogleGenAiTextEmbeddingAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class
})
@Tag("integration")
class OperationalAgentEndToEndMockMvcTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-a";
    private static final String ENV = "prod";

    public static class EmployeeDto {
        public Long id;
        @UISchema(label = "Nome")
        public String fullName;
        @UISchema(label = "CPF", preset = UISchemaPreset.CPF_CNPJ)
        @DomainGovernance(classification = DomainClassification.CONFIDENTIAL, aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK))
        public String taxId;
        public String positionTitle;

        public EmployeeDto() {}

        public EmployeeDto(Long id, String fullName, String taxId, String positionTitle) {
            this.id = id;
            this.fullName = fullName;
            this.taxId = taxId;
            this.positionTitle = positionTitle;
        }

        public Long getId() { return id; }
        public String getFullName() { return fullName; }
        public String getTaxId() { return taxId; }
        public String getPositionTitle() { return positionTitle; }
    }

    public static class EmployeeFilter implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "CPF", controlType = FieldControlType.CPF_CNPJ_INPUT)
        private String taxId;
        @Filterable(operation = Filterable.FilterOperation.LIKE, relations = {"fullName", "positionTitle"})
        private String searchTerm;

        public String getTaxId() { return taxId; }
        public void setTaxId(String taxId) { this.taxId = taxId; }
        public String getSearchTerm() { return searchTerm; }
        public void setSearchTerm(String searchTerm) { this.searchTerm = searchTerm; }
    }

    public static class PositionDto {
        public Long id;
        @UISchema(label = "Cargo")
        public String title;
        public String careerTrack;

        public PositionDto() {}
        public PositionDto(Long id, String title, String careerTrack) {
            this.id = id;
            this.title = title;
            this.careerTrack = careerTrack;
        }
        public Long getId() { return id; }
        public String getTitle() { return title; }
        public String getCareerTrack() { return careerTrack; }
    }

    public static class PositionFilter implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.LIKE)
        private String title;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    @ApiResource(
            value = "/api/human-resources/employees",
            resourceKey = "hr.employees",
            title = "Colaboradores",
            identity = @ResourceIdentity(keyField = "id", titleField = "fullName", subtitleField = "positionTitle"))
    public static class StubEmployeeController extends AbstractResourceQueryController<EmployeeDto, Long, EmployeeFilter> {
        private final List<EmployeeDto> data = List.of(
                new EmployeeDto(1001L, "Maria Beatriz Costa", "52998224725", "Analista de Sistemas"),
                new EmployeeDto(1002L, "Maria Clara Souza", "11144477735", "Coordenadora de RH"));

        private final BaseResourceQueryService<EmployeeDto, Long, EmployeeFilter> service = new SimpleQueryService<>() {
            @Override public Sort getDefaultSort() { return Sort.by("fullName"); }
            @Override public ResourceRepresentationResult<EmployeeDto> findById(Long id) {
                return data.stream().filter(e -> e.id.equals(id)).findFirst()
                        .map(ResourceRepresentationResult::unversioned).orElse(null);
            }
            @Override public Page<EmployeeDto> filter(EmployeeFilter f, Pageable p, Collection<Long> ids) {
                if (f != null && f.getTaxId() != null) {
                    return new PageImpl<>(data.stream().filter(e -> f.getTaxId().equals(e.taxId)).toList());
                }
                if (f != null && f.getSearchTerm() != null) {
                    String q = f.getSearchTerm().toLowerCase();
                    return new PageImpl<>(data.stream().filter(e ->
                            e.fullName.toLowerCase().contains(q) || e.positionTitle.toLowerCase().contains(q)).toList());
                }
                return Page.empty();
            }
        };

        @Override protected BaseResourceQueryService<EmployeeDto, Long, EmployeeFilter> getService() { return service; }
        @Override protected Long getResponseId(EmployeeDto dto) { return dto.id; }
    }

    @ApiResource(
            value = "/api/human-resources/positions",
            resourceKey = "hr.positions",
            title = "Cargos",
            identity = @ResourceIdentity(keyField = "id", titleField = "title", subtitleField = "careerTrack"))
    public static class StubPositionController extends AbstractResourceQueryController<PositionDto, Long, PositionFilter> {
        private final List<PositionDto> data = List.of(
                new PositionDto(301L, "Analista Judiciario", "Carreira Superior"));

        private final BaseResourceQueryService<PositionDto, Long, PositionFilter> service = new SimpleQueryService<>() {
            @Override public Sort getDefaultSort() { return Sort.by("title"); }
            @Override public ResourceRepresentationResult<PositionDto> findById(Long id) {
                return data.stream().filter(e -> e.id.equals(id)).findFirst()
                        .map(ResourceRepresentationResult::unversioned).orElse(null);
            }
            @Override public Page<PositionDto> filter(PositionFilter f, Pageable p, Collection<Long> ids) {
                if (f != null && f.getTitle() != null) {
                    String q = f.getTitle().toLowerCase();
                    return new PageImpl<>(data.stream().filter(e -> e.title.toLowerCase().contains(q)).toList());
                }
                return Page.empty();
            }
        };

        @Override protected BaseResourceQueryService<PositionDto, Long, PositionFilter> getService() { return service; }
        @Override protected Long getResponseId(PositionDto dto) { return dto.id; }
    }

    abstract static class SimpleQueryService<R, ID, F extends GenericFilterDTO> implements BaseResourceQueryService<R, ID, F> {
        @Override public String getIdFieldName() { return "id"; }
        @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
        @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsFieldRegistry getStatsFieldRegistry() { return null; }
        @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
        @Override public boolean hasOptionSource(String k) { return false; }
        @Override public OptionSourceDescriptor resolveOptionSource(String k) { return null; }
        @Override public List<R> findAll() { return List.of(); }
        @Override public List<R> findAllById(Collection<ID> ids) { return List.of(); }
        @Override public CursorPage<R> filterByCursor(F f, Sort s, String a, String c, int l) { return null; }
        @Override public java.util.OptionalLong locate(F f, Sort s, ID id) { return java.util.OptionalLong.empty(); }
        @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<ID>> filterOptions(F f, Pageable p) { return Page.empty(); }
        @Override public List<org.praxisplatform.uischema.dto.OptionDTO<ID>> byIdsOptions(Collection<ID> ids) { return List.of(); }
        @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<F> r, Pageable p) { return Page.empty(); }
        @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
        @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<F> r) { return null; }
        @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<F> r) { return null; }
        @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<F> r) { return null; }
        @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<F> r) { return null; }
    }

    @TestConfiguration
    static class StubDomainConfig {

        @Bean
        StubEmployeeController stubEmployeeController() {
            return new StubEmployeeController();
        }

        @Bean
        StubPositionController stubPositionController() {
            return new StubPositionController();
        }

        @Bean
        AgentEntityCatalogContributor stubEntityCatalogContributor() {
            return ctx -> List.of(
                    new AgentEntityDescriptor("hr.employees", "hr.employees", "/api/human-resources/employees",
                            "Colaboradores", "Cadastro de colaboradores", "badge", "id", "fullName", "positionTitle",
                            List.of("positionTitle"), "taxId", "searchTerm", null, "/employees/{id}", "hr", 100),
                    new AgentEntityDescriptor("hr.positions", "hr.positions", "/api/human-resources/positions",
                            "Cargos", "Estrutura de cargos", "work", "id", "title", "careerTrack",
                            List.of("careerTrack"), null, "title", null, "/positions/{id}", "hr", 50));
        }

        @Bean
        AgentProactiveAlertContributor vacationAlertContributor() {
            return new AgentProactiveAlertContributor() {
                @Override
                public boolean supportsEntity(String entityKey) {
                    return "hr.employees".equals(entityKey);
                }

                @Override
                public List<AgentProactiveAlert> evaluateAlerts(
                        AgentExecutionContext context, String entityKey, String entityId, Map<String, Object> record) {
                    if ("1001".equals(entityId)) {
                        return List.of(new AgentProactiveAlert(
                                "LEAVE_EXPIRING", "warning",
                                "Periodo de ferias prestes a vencer",
                                "Existe saldo pendente com vencimento nos proximos 30 dias.",
                                entityKey, "/api/human-resources/employees", entityId,
                                "schedule-leave", "Agendar ferias", Map.of()));
                    }
                    return List.of();
                }
            };
        }

        @Bean
        @Primary
        AiToolCallingProvider aiToolCallingProvider() {
            return new AiToolCallingProvider() {
                @Override
                public String providerId() {
                    return "gemini";
                }

                @Override
                public AgentModelTurnResponse generateTurn(
                        AgentModelTurnRequest request, AgentModelStreamListener listener) {
                    String firstUserPrompt = request.conversation().get(0).text();
                    boolean hasToolResult = request.conversation().stream()
                            .anyMatch(item -> !item.toolResults().isEmpty());
                    if (!hasToolResult) {
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                List.of(new AgentToolCall("call-1", "search_and_resolve_entities",
                                        Map.of("query", firstUserPrompt), "c2ln", null)),
                                new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-1", Map.of());
                    }
                    var lastToolResults = request.conversation().get(request.conversation().size() - 1).toolResults();
                    var firstResult = lastToolResults.get(0);
                    if ("search_and_resolve_entities".equals(firstResult.name())) {
                        int count = ((Number) firstResult.output().getOrDefault("matchCount", 0)).intValue();
                        if (count == 1) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> candidates = (List<Map<String, Object>>) firstResult.output().get("candidates");
                            Map<String, Object> only = candidates.get(0);
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-2", "get_entity_360_dossier",
                                            Map.of("entityKey", only.get("entityKey"), "entityId", only.get("entityId")),
                                            "c2lnMg==", null)),
                                    new AgentTokenUsage(40, 20, 60), "tool_calls", "resp-2", Map.of());
                        }
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                                "Encontrei mais de um registro correspondente. Selecione qual deseja abrir:",
                                List.of(), new AgentTokenUsage(35, 25, 60), "stop", "resp-2", Map.of());
                    }
                    return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                            "Localizei o colaborador e consolidei o dossie operacional.",
                            List.of(), new AgentTokenUsage(60, 30, 90), "stop", "resp-3", Map.of());
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ActionCatalogService actionCatalogService;

    @BeforeEach
    void cleanDatabase() {
        ActionCatalogItem scheduleLeave = new ActionCatalogItem(
                "schedule-leave", "hr.employees", ActionScope.ITEM,
                "Agendar ferias", "Inicia o agendamento de ferias para o colaborador",
                "scheduleLeave", "/api/human-resources/employees/{id}/actions/schedule-leave", "POST",
                "ScheduleLeaveRequest", "/schemas/filtered?path=/api/human-resources/employees/{id}/actions/schedule-leave&schemaType=request",
                null, null, List.of("ACTIVE"), AvailabilityDecision.allowAll(), 1,
                "Ferias agendadas com sucesso.", List.of("ferias"), ActionExecutionContract.defaults(ActionScope.ITEM));
        when(actionCatalogService.findItemActions("hr.employees", 1001L)).thenReturn(new ActionCatalogResponse(
                "hr.employees", "/api/human-resources/employees", "hr", 1001L, List.of(scheduleLeave)));

        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("set referential_integrity true");
    }

    @Test
    void scenarioA_exactCpfResolvesDossierAlertAndActionsWithoutPersistingRawTaxId() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("529.982.247-25", "turn-cpf-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        List<String> storedPrompts = jdbcTemplate.queryForList("select title from ai_thread", String.class);
        assertThat(storedPrompts).hasSize(1);
        assertThat(storedPrompts.get(0)).contains("[TAX_ID_1]").doesNotContain("52998224725").doesNotContain("529.982.247-25");

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        JsonNode payload = resultEvent.getPayload();
        assertThat(payload.toString()).doesNotContain("[REDACTED]");
        assertThat(payload.path("assistantContent").path("version").asText()).isEqualTo("praxis.assistant-content/v2");

        JsonNode blocks = payload.path("assistantContent").path("blocks");
        List<String> blockTypes = new ArrayList<>();
        blocks.forEach(b -> blockTypes.add(b.path("type").asText()));
        assertThat(blockTypes).containsExactly("paragraph", "alert-list", "widget-block", "action-bar");
        assertThat(blocks.get(1).path("items").get(0).path("code").asText()).isEqualTo("LEAVE_EXPIRING");
        assertThat(blocks.get(2).path("widgetType").asText()).isEqualTo("praxis-rich-content");
        assertThat(blocks.get(2).path("widget").path("id").asText()).isEqualTo("praxis-rich-content");
        assertThat(blocks.get(2).path("widget").path("inputs").path("layout").asText()).isEqualTo("block");
        JsonNode richDoc = blocks.get(2).path("widget").path("inputs").path("document");
        assertThat(richDoc.path("kind").asText()).isEqualTo("praxis.rich-content");
        assertThat(richDoc.path("version").asText()).isEqualTo("1.0.0");
        assertThat(richDoc.path("nodes").get(0).path("type").asText()).isEqualTo("recordSummary");
        assertThat(blocks.get(3).path("items").get(0).path("actionId").asText()).isEqualTo("schedule-leave");
        assertThat(blocks.get(3).path("items").get(0).path("formMode").asText()).isEqualTo("dialog");
    }

    @Test
    void scenarioB_homonymCollisionEmitsDisambiguationAndQuickReplies() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("Maria", "turn-maria-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        JsonNode payload = resultEvent.getPayload();
        assertThat(payload.toString()).doesNotContain("[REDACTED]");
        JsonNode blocks = payload.path("assistantContent").path("blocks");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("compact-collection");
        assertThat(blocks.get(1).path("variant").asText()).isEqualTo("disambiguation");
        assertThat(blocks.get(1).path("rows")).hasSize(2);
        assertThat(payload.path("quickReplies")).hasSize(2);
        assertThat(payload.path("quickReplies").get(0).path("contextHints").path("candidateRef").asText())
                .startsWith("hr.employees:");
    }

    @Test
    void scenarioC_polymorphicCollisionGroupsAcrossEmployeesAndPositions() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("Analista", "turn-poly-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        JsonNode rows = resultEvent.getPayload().path("assistantContent").path("blocks").get(1).path("rows");
        List<String> entityKeys = new ArrayList<>();
        rows.forEach(r -> entityKeys.add(r.path("entityKey").asText()));
        assertThat(entityKeys).containsExactlyInAnyOrder("hr.employees", "hr.positions");
    }

    private AgenticAuthoringTurnStreamStartResponse startStream(String prompt, String clientTurnId) throws Exception {
        OperationalAgentTurnStreamRequest req = new OperationalAgentTurnStreamRequest(
                prompt, null, clientTurnId, null, null, null, List.of(), "gemini", "gemini-2.5-flash", null, null);
        MvcResult result = mockMvc.perform(post("/api/praxis/config/ai/agent/turn/stream/start")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                AgenticAuthoringTurnStreamStartResponse.class);
    }

    private List<AiTurnEventEnvelope> readSseEvents(UUID streamId) throws Exception {
        awaitTerminalEvent(streamId);
        MvcResult asyncResult = mockMvc.perform(get("/api/praxis/config/ai/agent/turn/stream/{streamId}", streamId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<AiTurnEventEnvelope> events = new ArrayList<>();
        StringBuilder dataBuf = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                if (dataBuf.length() > 0) {
                    dataBuf.append('\n');
                }
                dataBuf.append(line.substring(5).trim());
            } else if (line.isBlank() && dataBuf.length() > 0) {
                events.add(objectMapper.readValue(dataBuf.toString(), AiTurnEventEnvelope.class));
                dataBuf.setLength(0);
            }
        }
        if (dataBuf.length() > 0) {
            events.add(objectMapper.readValue(dataBuf.toString(), AiTurnEventEnvelope.class));
        }
        return events;
    }

    private void awaitTerminalEvent(UUID streamId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from ai_turn_event where stream_id = ? and event_type in ('result', 'error', 'cancelled')",
                    Integer.class,
                    streamId);
            if (count != null && count > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("Timed out waiting for terminal operational agent event for stream " + streamId);
    }
}
