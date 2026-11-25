package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.Session;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.PageRuntimeController;
import org.praxisplatform.config.controller.UiLayoutResolutionExceptionHandler;
import org.praxisplatform.config.domain.UiLayoutAssignmentRevision;
import org.praxisplatform.config.domain.UiLayoutDefinition;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.domain.UiLayoutRevision;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationNode;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;
import org.praxisplatform.config.dto.PageCatalogEntry;
import org.praxisplatform.config.dto.UiLayoutAudienceSelectorCommand;
import org.praxisplatform.config.dto.UiLayoutPageChildSource;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutDraftRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires real {@link CorporatePageRuntimeSupport} SPIs into {@link PageRuntimeReadService} and
 * {@link PageRuntimeController} to prove multi-user and post-context-rotation reads of published
 * corporate multi-widget pages while keeping stale context, audience isolation, permission denial,
 * and tampered historical evidence fail-closed.
 */
@Tag("unit")
class CorporatePageMultiUserRuntimeReadTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-25T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final UUID GROUP_ID = UUID.fromString("10000000-0000-4000-a000-000000000301");
    private static final UUID PAGE_ID = UUID.fromString("10000000-0000-4000-a000-000000000302");
    private static final UUID NAV_ENTRY_ID = UUID.fromString("10000000-0000-4000-a000-000000000303");
    private static final String TABLE_WIDGET_KEY = "20000000-0000-4000-a000-000000000301";
    private static final String ANALYTICS_WIDGET_KEY = "20000000-0000-4000-a000-000000000302";
    private static final UiLayoutTarget PAGE_TARGET =
            new UiLayoutTarget(UiLayoutPageRevisionReproduction.TYPE, PAGE_ID.toString());

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(json);
    private final UiLayoutMetadataCaptureCodec captureCodec = new UiLayoutMetadataCaptureCodec(json, hashes);
    private final UiLayoutDraftWorkspaceCodec workspaceCodec = new UiLayoutDraftWorkspaceCodec(json, hashes);

    private final Map<String, ActorState> actorSessions = new ConcurrentHashMap<>();
    private final List<PageRuntimeStore.Head> heads = new ArrayList<>();
    private final Map<UUID, UiLayoutRelease> releaseRows = new HashMap<>();
    private final Map<UUID, UiLayoutDraft> draftRows = new HashMap<>();
    private final Map<UUID, UiLayoutReleaseMember> memberRows = new HashMap<>();
    private final Map<UUID, UiLayoutRevision> revisionRows = new HashMap<>();
    private final Map<UUID, UiLayoutDefinition> definitionRows = new HashMap<>();
    private final Map<UUID, UiLayoutAssignmentRevision> assignmentRows = new HashMap<>();
    private final Map<UUID, UiLayoutMetadataCapture> captures = new HashMap<>();
    private final Set<UUID> tamperedServiceKeyDrafts = new HashSet<>();

    private jakarta.persistence.EntityManagerFactory factory;
    private CorporatePageRuntimeSupport support;
    private PageRuntimeReadService readService;
    private MockMvc mvc;
    private UiLayoutTarget groupsRoot;

    @BeforeEach
    void setUp() throws Exception {
        Connection connection = mock(Connection.class);
        AtomicBoolean autoCommit = new AtomicBoolean(true);
        when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
        doAnswer(
                        call -> {
                            autoCommit.set(call.getArgument(0));
                            return null;
                        })
                .when(connection)
                .setAutoCommit(anyBoolean());
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet isolation = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT current_setting('transaction_isolation')"))
                .thenReturn(statement);
        when(statement.executeQuery())
                .thenAnswer(
                        call -> {
                            when(isolation.next()).thenReturn(true, false);
                            return isolation;
                        });
        when(isolation.getString(1)).thenReturn("read committed");

        javax.sql.DataSource dataSource = mock(javax.sql.DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        TransactionTemplate jdbcTransactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.EntityTransaction entityTransaction =
                mock(jakarta.persistence.EntityTransaction.class);
        SessionImplementor session = mock(SessionImplementor.class);
        PersistenceContext persistence = mock(PersistenceContext.class);
        when(persistence.getEntitiesByKey()).thenReturn(Map.of());
        when(session.getPersistenceContextInternal()).thenReturn(persistence);
        when(entityManager.getTransaction()).thenReturn(entityTransaction);
        when(entityTransaction.isActive()).thenReturn(true);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(entityManager.unwrap(SessionImplementor.class)).thenReturn(session);
        doAnswer(call -> ((ReturningWork<?>) call.getArgument(0)).execute(connection))
                .when(session)
                .doReturningWork(any());

        factory = mock(jakarta.persistence.EntityManagerFactory.class);
        org.springframework.orm.jpa.EntityManagerHolder holder =
                new org.springframework.orm.jpa.EntityManagerHolder(entityManager);
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(factory, holder);
        org.springframework.orm.jpa.JpaTransactionManager manager =
                new org.springframework.orm.jpa.JpaTransactionManager();
        manager.setEntityManagerFactory(factory);
        manager.setDataSource(dataSource);
        TransactionTemplate transactions =
                new TransactionTemplate(manager) {
                    @Override
                    public <T> T execute(
                            org.springframework.transaction.support.TransactionCallback<T> callback) {
                        return jdbcTransactions.execute(callback);
                    }
                };

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.getJdbcTemplate()).thenReturn(new JdbcTemplate(dataSource));
        doAnswer(
                        call -> {
                            String sql = call.getArgument(0);
                            MapSqlParameterSource parameters = call.getArgument(1);
                            if (sql.contains("FROM ui_page_catalog")) {
                                UUID page = (UUID) parameters.getValue("page");
                                boolean groups = parameters.hasValue("groupRoot");
                                return heads.stream()
                                        .filter(
                                                head ->
                                                        page == null
                                                                ? head.pageId() != null || groups
                                                                : page.equals(head.pageId()))
                                        .sorted(
                                                Comparator.comparing(
                                                        PageRuntimeStore.Head::pageId,
                                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                                        .toList();
                            }
                            if (sql.contains("AS weight")
                                    && !sql.contains("ui_layout_baseline_metadata_evidence")) {
                                @SuppressWarnings("unchecked")
                                Collection<UUID> ids = (Collection<UUID>) parameters.getValue("ids");
                                return ids.stream().map(id -> Map.entry(id, 1024L)).toList();
                            }
                            if (sql.contains("ui_layout_baseline_metadata_evidence")) {
                                if (parameters.hasValue("draft0")) {
                                    List<Map.Entry<Integer, String>> rows = new ArrayList<>();
                                    for (int index = 0; parameters.hasValue("draft" + index); index++) {
                                        UiLayoutMetadataCapture capture =
                                                captures.get((UUID) parameters.getValue("draft" + index));
                                        rows.add(
                                                new AbstractMap.SimpleImmutableEntry<>(
                                                        index,
                                                        capture == null ? null : rawMetadata(capture)));
                                    }
                                    if (sql.contains("AS weight")) {
                                        return rows.stream()
                                                .map(
                                                        row ->
                                                                Map.entry(
                                                                        row.getKey(),
                                                                        row.getValue() == null
                                                                                ? 0L
                                                                                : (long)
                                                                                        row.getValue()
                                                                                                .getBytes(
                                                                                                        java.nio.charset.StandardCharsets.UTF_8)
                                                                                                .length))
                                                .toList();
                                    }
                                    @SuppressWarnings("unchecked")
                                    Collection<Integer> ordinals =
                                            (Collection<Integer>) parameters.getValue("retained_ordinals");
                                    return rows.stream()
                                            .filter(row -> ordinals.contains(row.getKey()))
                                            .toList();
                                }
                                UiLayoutMetadataCapture capture =
                                        captures.get((UUID) parameters.getValue("source_draft_ref"));
                                return capture == null ? List.of() : List.of(rawMetadata(capture));
                            }
                            throw new AssertionError("Unexpected SQL in fixture: " + sql);
                        })
                .when(jdbc)
                .query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));

        UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
        UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
        UiLayoutReleaseMemberRepository members = mock(UiLayoutReleaseMemberRepository.class);
        UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
        UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
        UiLayoutAssignmentRevisionRepository assignments =
                mock(UiLayoutAssignmentRevisionRepository.class);

        when(releases.findAllById(any())).thenAnswer(call -> select(releaseRows, call.getArgument(0)));
        when(drafts.findAllById(any())).thenAnswer(call -> select(draftRows, call.getArgument(0)));
        when(revisions.findAllById(any())).thenAnswer(call -> select(revisionRows, call.getArgument(0)));
        when(definitions.findAllById(any()))
                .thenAnswer(call -> select(definitionRows, call.getArgument(0)));
        when(assignments.findAllById(any()))
                .thenAnswer(call -> select(assignmentRows, call.getArgument(0)));
        when(members.findByReleaseIdInOrderByReleaseIdAscMemberOrderAsc(any()))
                .thenAnswer(
                        call -> {
                            Collection<UUID> ids = call.getArgument(0);
                            return memberRows.values().stream()
                                    .filter(row -> ids.contains(row.getReleaseId()))
                                    .toList();
                        });
        when(releases.findByIdAndTenantIdAndEnvironment(any(), eq("default"), eq("prod")))
                .thenAnswer(call -> Optional.ofNullable(releaseRows.get(call.getArgument(0))));
        when(releases.findBySourceDraftId(any()))
                .thenAnswer(
                        call -> {
                            UUID draftId = call.getArgument(0);
                            return releaseRows.values().stream()
                                    .filter(r -> draftId.equals(r.getSourceDraftId()))
                                    .findFirst();
                        });
        when(drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(
                        any(), eq("default"), eq("prod"), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            UUID draftId = call.getArgument(0);
                            String type = call.getArgument(3);
                            String id = call.getArgument(4);
                            UiLayoutDraft found = draftRows.get(draftId);
                            if (found != null
                                    && type.equals(found.getRootComponentType())
                                    && id.equals(found.getRootComponentId())) {
                                return Optional.of(found);
                            }
                            return Optional.empty();
                        });
        when(members.findByReleaseIdOrderByMemberOrder(any()))
                .thenAnswer(
                        call -> {
                            UUID relId = call.getArgument(0);
                            return memberRows.values().stream()
                                    .filter(m -> relId.equals(m.getReleaseId()))
                                    .sorted(Comparator.comparingInt(UiLayoutReleaseMember::getMemberOrder))
                                    .toList();
                        });
        when(definitions.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(definitionRows.get(call.getArgument(0))));
        when(revisions.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(revisionRows.get(call.getArgument(0))));
        when(assignments.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(assignmentRows.get(call.getArgument(0))));

        ObjectNode baselineDoc = baselinePageDocument();
        CorporatePageCatalogContributor contributor =
                new CorporatePageCatalogContributor() {
                    @Override
                    public String applicationId() {
                        return "workforce-portal";
                    }

                    @Override
                    public List<NavigationGroupSeed> navigationGroups() {
                        return List.of(
                                new NavigationGroupSeed(GROUP_ID, "Workforce Operations", "groups", 10));
                    }

                    @Override
                    public List<NativePageSeed> nativePages() {
                        return List.of(
                                new NativePageSeed(
                                        "workforce-overview",
                                        "1",
                                        PAGE_ID,
                                        baselineDoc,
                                        "Workforce Overview",
                                        "groups"));
                    }

                    @Override
                    public Set<String> admittedComposedWidgetTypes() {
                        return Set.of("praxis-table", "praxis-analytics-presentation");
                    }

                    @Override
                    public Optional<ResolvedActorContext> resolveActor(
                            Principal principal, String contextVersion) {
                        ActorState actorState = actorSessions.get(principal.getName());
                        if (actorState == null) {
                            throw new UiLayoutLifecycleException(
                                    UiLayoutLifecycleException.Code.SESSION_REQUIRED,
                                    "Unknown principal session.");
                        }
                        return Optional.of(
                                new ResolvedActorContext(
                                        principal.getName(),
                                        "default",
                                        actorState.unit(),
                                        "prod",
                                        actorState.organization(),
                                        null,
                                        actorState.profile(),
                                        Set.of(),
                                        actorState.contextVersion()));
                    }

                    @Override
                    public void checkLifecycleOperation(
                            UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {
                        ActorState actorState = actorSessions.get(invocation.actorRef());
                        if (actorState == null) {
                            throw new UiLayoutLifecycleException(
                                    UiLayoutLifecycleException.Code.SESSION_REQUIRED,
                                    "Missing actor state.");
                        }
                        if (operation == UiLayoutLifecycleOperation.READ_HEAD && !actorState.canReadHead()) {
                            throw new UiLayoutLifecycleException(
                                    UiLayoutLifecycleException.Code.DENIED,
                                    "Actor is not authorized to read published pages.");
                        }
                    }
                };

        groupsRoot =
                new UiLayoutTarget(
                        UiLayoutNavigationGroupsRevisionReproduction.TYPE,
                        contributor.navigationGroupsRootId("default", "prod").toString());

        support =
                new CorporatePageRuntimeSupport(
                        contributor,
                        json,
                        FIXED_CLOCK,
                        new StaticListableBeanFactory().getBeanProvider(PageRuntimeReadService.class));

        PageCreationService pages =
                mock(PageCreationService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(pages.prefetchRuntimeEvidence(any(), anyList()))
                .thenAnswer(call -> ((List<?>) call.getArgument(1)).size());

        readService =
                new PageRuntimeReadService(
                        pages,
                        support.pageCreationHost(),
                        support.audienceProvider(),
                        support.pageLifecycleAdmission(),
                        support.structureValidator(),
                        support.draftWorkspaceSource(),
                        support.baselineMetadataAdmission(),
                        jdbc,
                        transactions,
                        support.validationBudgetPolicy(),
                        releases,
                        drafts,
                        members,
                        revisions,
                        definitions,
                        assignments,
                        json,
                        hashes,
                        FIXED_CLOCK);

        ObjectMapper httpMapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        mvc =
                MockMvcBuilders.standaloneSetup(new PageRuntimeController(readService))
                        .setControllerAdvice(new UiLayoutResolutionExceptionHandler())
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(httpMapper))
                        .build();

        // Creator publishes under hr.manager (org-1, ctx-v1)
        actorSessions.put("hr.manager", new ActorState("org-1", "org-1", "MANAGER", "ctx-v1", true));
        seedPublishedGroups("hr.manager", "org-1", "ctx-v1");
        seedPublishedMultiWidgetPage("hr.manager", "org-1", "ctx-v1");
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.unbindResourceIfPossible(factory);
    }

    @Test
    void allowsReadsAfterContextRotationAndAcrossAuthorizedUsersWhileKeepingGuardsFailClosed()
            throws Exception {
        // 1. Original publisher rotates contextVersion AND administrativeUnit (ctx-v2)
        actorSessions.put(
                "hr.manager", new ActorState("org-1-reassigned", "org-1", "MANAGER", "ctx-v2", true));
        mvc.perform(
                        get("/api/praxis/config/pages")
                                .principal(() -> "hr.manager")
                                .header("X-Praxis-Context-Version", "ctx-v2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache, private"))
                .andExpect(jsonPath("$.items[0].pageId").value(PAGE_ID.toString()));

        mvc.perform(
                        get("/api/praxis/config/pages/" + PAGE_ID + "/published")
                                .principal(() -> "hr.manager")
                                .header("X-Praxis-Context-Version", "ctx-v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageId").value(PAGE_ID.toString()))
                .andExpect(jsonPath("$.document.config.definition.widgets.length()").value(2))
                .andExpect(
                        jsonPath("$.document.config.definition.widgets[0].definition.id")
                                .value("praxis-table"))
                .andExpect(
                        jsonPath("$.document.config.definition.widgets[1].definition.id")
                                .value("praxis-analytics-presentation"));

        EnterpriseRuntimeNavigationResponse managerNav =
                readService.navigation(
                        new EnterpriseRuntimeContextRequest(() -> "hr.manager", "pt-BR", "UTC", "portal"),
                        "ctx-v2",
                        GROUP_ID.toString(),
                        null,
                        50);
        assertThat(managerNav.nodes())
                .extracting(EnterpriseRuntimeNavigationNode::label)
                .containsExactly("Workforce Multi-Widget Overview");

        // 2. Different READER user (hr.reader, org-1, ctx-v3) reads both catalog and published page
        actorSessions.put("hr.reader", new ActorState("org-1", "org-1", "READER", "ctx-v3", true));
        mvc.perform(
                        get("/api/praxis/config/pages")
                                .principal(() -> "hr.reader")
                                .header("X-Praxis-Context-Version", "ctx-v3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].pageId").value(PAGE_ID.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Workforce Multi-Widget Overview"));

        mvc.perform(
                        get("/api/praxis/config/pages/" + PAGE_ID + "/published")
                                .principal(() -> "hr.reader")
                                .header("X-Praxis-Context-Version", "ctx-v3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageId").value(PAGE_ID.toString()))
                .andExpect(jsonPath("$.document.config.definition.widgets.length()").value(2));

        EnterpriseRuntimeNavigationResponse readerNav =
                readService.navigation(
                        new EnterpriseRuntimeContextRequest(() -> "hr.reader", "pt-BR", "UTC", "portal"),
                        "ctx-v3",
                        GROUP_ID.toString(),
                        null,
                        50);
        assertThat(readerNav.nodes())
                .extracting(EnterpriseRuntimeNavigationNode::label)
                .containsExactly("Workforce Multi-Widget Overview");

        // 3. Stale X-Praxis-Context-Version header must still fail with 409 CONFLICT
        mvc.perform(
                        get("/api/praxis/config/pages/" + PAGE_ID + "/published")
                                .principal(() -> "hr.reader")
                                .header("X-Praxis-Context-Version", "ctx-stale"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CONTEXT_STALE"));

        // 4. Audience isolation: switching hr.reader to org-2 hides the org-1 page
        actorSessions.put("hr.reader", new ActorState("org-2", "org-2", "READER", "ctx-v4", true));
        mvc.perform(
                        get("/api/praxis/config/pages")
                                .principal(() -> "hr.reader")
                                .header("X-Praxis-Context-Version", "ctx-v4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mvc.perform(
                        get("/api/praxis/config/pages/" + PAGE_ID + "/published")
                                .principal(() -> "hr.reader")
                                .header("X-Praxis-Context-Version", "ctx-v4"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));

        // 5. Revoked READ_HEAD capability denies access fail-closed
        actorSessions.put("hr.blocked", new ActorState("org-1", "org-1", "GUEST", "ctx-v5", false));
        mvc.perform(
                        get("/api/praxis/config/pages")
                                .principal(() -> "hr.blocked")
                                .header("X-Praxis-Context-Version", "ctx-v5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mvc.perform(
                        get("/api/praxis/config/pages/" + PAGE_ID + "/published")
                                .principal(() -> "hr.blocked")
                                .header("X-Praxis-Context-Version", "ctx-v5"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));

        // 6. Tampered historical metadata provenance in stored evidence fails closed (never 200)
        actorSessions.put("hr.reader", new ActorState("org-1", "org-1", "READER", "ctx-v6", true));
        UUID pageDraftId = releaseRows.get(heads.get(1).release()).getSourceDraftId();
        tamperedServiceKeyDrafts.add(pageDraftId);
        MvcResult tamperedResult =
                mvc.perform(
                                get("/api/praxis/config/pages/" + PAGE_ID + "/published")
                                        .principal(() -> "hr.reader")
                                        .header("X-Praxis-Context-Version", "ctx-v6"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        assertThat(tamperedResult.getResponse().getStatus()).isIn(403, 503);
        assertThat(tamperedResult.getResponse().getContentAsString())
                .doesNotContain("Workforce Multi-Widget Overview");
    }

    private void seedPublishedGroups(String actor, String unit, String contextVersion) {
        UiLayoutLifecycleInvocation invocation =
                new UiLayoutLifecycleInvocation(
                        actor,
                        "default",
                        unit,
                        "prod",
                        contextVersion,
                        new UiLayoutCompositionRegistration(groupsRoot, List.of(groupsRoot)));
        UiLayoutValidationContext validation =
                new UiLayoutValidationContext(
                        UiLayoutValidationAttempt.start(
                                invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(30)),
                        UiLayoutValidationPurpose.AUTHORING);
        UiLayoutDraftWorkspaceSeed workspaceSeed =
                support.draftWorkspaceSource().capture(invocation, validation);
        UiLayoutDraftWorkspaceSeed.TargetSeed seed = workspaceSeed.targets().getFirst();
        ObjectNode patch =
                UiLayoutAuthoringProjection.project(
                                groupsRoot,
                                seed.authoring(),
                                seed.document(),
                                seed.document(),
                                seed.patch(),
                                () -> {})
                        .compilePatch();
        persistRelease(
                null,
                invocation,
                seed,
                seed.document(),
                patch,
                List.of(),
                new UiLayoutAudienceSelectorCommand("default", null, null, null, null, null));
    }

    private void seedPublishedMultiWidgetPage(String actor, String unit, String contextVersion) {
        UiLayoutLifecycleInvocation invocation =
                new UiLayoutLifecycleInvocation(
                        actor,
                        "default",
                        unit,
                        "prod",
                        contextVersion,
                        new UiLayoutCompositionRegistration(PAGE_TARGET, List.of(PAGE_TARGET)));
        UiLayoutValidationContext createValidation =
                new UiLayoutValidationContext(
                        UiLayoutValidationAttempt.start(
                                invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(30)),
                        UiLayoutValidationPurpose.AUTHORING);
        UiLayoutDraftWorkspaceSeed workspaceSeed =
                support.draftWorkspaceSource().capture(invocation, createValidation);
        UiLayoutDraftWorkspaceSeed.TargetSeed seed = workspaceSeed.targets().getFirst();

        UUID draftId = UUID.randomUUID();
        UiLayoutPageChildCaptures capturesHelper = new UiLayoutPageChildCaptures(hashes);
        List<UiLayoutPageChildEvidence> initialEvidence =
                capturesHelper.initial(
                        invocation, draftId, seed, support.draftWorkspaceSource(), createValidation);

        UiLayoutDraftWorkspaceDocument.TargetState initialState =
                new UiLayoutDraftWorkspaceDocument.TargetState(
                        PAGE_TARGET,
                        seed.authoring(),
                        seed.patch(),
                        new UiLayoutDraftWorkspaceDocument.BaselineDocument(
                                seed.sourceRef(), hashes.sha256Exact(seed.document()), seed.document()),
                        new UiLayoutDraftWorkspaceDocument.WorkingDocument(
                                hashes.sha256Exact(seed.document()), seed.document()),
                        null,
                        null,
                        initialEvidence);

        ObjectNode candidateDoc = multiWidgetCandidateDocument();
        UiLayoutValidationContext editValidation =
                new UiLayoutValidationContext(
                        UiLayoutValidationAttempt.start(
                                invocation, UiLayoutLifecycleOperation.EDIT_DRAFT, Duration.ofSeconds(30)),
                        UiLayoutValidationPurpose.AUTHORING);
        List<UiLayoutPageChildEvidence> updatedEvidence =
                capturesHelper.revision(
                        invocation,
                        draftId,
                        initialState,
                        candidateDoc,
                        List.of(
                                new UiLayoutPageChildSource(
                                        ANALYTICS_WIDGET_KEY,
                                        new UiLayoutPageChildSource.NativeSource(
                                                "registered-native",
                                                CorporatePageRuntimeSupport.COMPOSED_WIDGET_SOURCE_PREFIX
                                                        + "praxis-analytics-presentation",
                                                CorporatePageRuntimeSupport.COMPOSED_WIDGET_SOURCE_REVISION))),
                        support.draftWorkspaceSource(),
                        editValidation);

        ObjectNode patch =
                UiLayoutAuthoringProjection.project(
                                PAGE_TARGET,
                                seed.authoring(),
                                seed.document(),
                                candidateDoc,
                                seed.patch(),
                                () -> {})
                        .compilePatch();
        persistRelease(
                PAGE_ID,
                invocation,
                seed,
                candidateDoc,
                patch,
                updatedEvidence,
                new UiLayoutAudienceSelectorCommand("default", "org-1", null, null, null, null),
                draftId);
    }

    private void persistRelease(
            UUID pageId,
            UiLayoutLifecycleInvocation invocation,
            UiLayoutDraftWorkspaceSeed.TargetSeed seed,
            JsonNode workingDoc,
            ObjectNode patch,
            List<UiLayoutPageChildEvidence> children,
            UiLayoutAudienceSelectorCommand selector) {
        persistRelease(pageId, invocation, seed, workingDoc, patch, children, selector, UUID.randomUUID());
    }

    private void persistRelease(
            UUID pageId,
            UiLayoutLifecycleInvocation invocation,
            UiLayoutDraftWorkspaceSeed.TargetSeed seed,
            JsonNode workingDoc,
            ObjectNode patch,
            List<UiLayoutPageChildEvidence> children,
            UiLayoutAudienceSelectorCommand selector,
            UUID draftId) {
        UiLayoutTarget target = seed.target();
        UUID releaseId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String patchHash = hashes.sha256Exact(patch);
        String baselineHash = hashes.sha256Exact(seed.document());
        String workingHash = hashes.sha256Exact(workingDoc);
        String layerClass = selector.organization() != null ? "ORGANIZATION" : "TENANT";

        UiLayoutDraftWorkspaceDocument.TargetState state =
                new UiLayoutDraftWorkspaceDocument.TargetState(
                        target,
                        seed.authoring(),
                        seed.patch(),
                        new UiLayoutDraftWorkspaceDocument.BaselineDocument(
                                seed.sourceRef(), baselineHash, seed.document()),
                        new UiLayoutDraftWorkspaceDocument.WorkingDocument(workingHash, workingDoc),
                        new UiLayoutDraftWorkspaceDocument.RevisionSelection(
                                revisionId, 1, patchHash, seed.patch().schemaVersion(), UUID.randomUUID()),
                        new UiLayoutDraftWorkspaceDocument.AssignmentSelection(
                                assignmentId, revisionId, layerClass, selector, (short) 0, "page", UUID.randomUUID()),
                        children);
        UiLayoutDraftWorkspaceDocument workspace =
                new UiLayoutDraftWorkspaceDocument(
                        UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, List.of(state), UUID.randomUUID());

        UiLayoutRelease releaseRow =
                UiLayoutRelease.builder()
                        .id(releaseId)
                        .tenantId("default")
                        .environment("prod")
                        .rootComponentType(target.componentType())
                        .rootComponentId(target.componentId())
                        .sourceDraftId(draftId)
                        .build();
        UiLayoutDraft draftRow =
                UiLayoutDraft.builder()
                        .id(draftId)
                        .tenantId("default")
                        .environment("prod")
                        .rootComponentType(target.componentType())
                        .rootComponentId(target.componentId())
                        .state("RELEASED")
                        .draftEtag(UUID.randomUUID())
                        .draftDocument(workspaceCodec.encode(workspace))
                        .build();
        UiLayoutReleaseMember member =
                UiLayoutReleaseMember.builder()
                        .id(UUID.randomUUID())
                        .releaseId(releaseId)
                        .memberOrder(0)
                        .componentType(target.componentType())
                        .componentId(target.componentId())
                        .contentRevisionId(revisionId)
                        .assignmentRevisionId(assignmentId)
                        .contributionKey("page")
                        .build();
        UiLayoutDefinition definition =
                UiLayoutDefinition.builder()
                        .id(definitionId)
                        .tenantId("default")
                        .environment("prod")
                        .componentType(target.componentType())
                        .componentId(target.componentId())
                        .schemaVersion(seed.patch().schemaVersion())
                        .build();
        UiLayoutRevision revisionRow =
                UiLayoutRevision.builder()
                        .id(revisionId)
                        .definitionId(definitionId)
                        .revisionNumber(1L)
                        .contentHash(patchHash)
                        .schemaVersion(seed.patch().schemaVersion())
                        .patchDocument(patch.toString())
                        .build();
        UiLayoutAssignmentRevision assignment =
                UiLayoutAssignmentRevision.builder()
                        .id(assignmentId)
                        .tenantId("default")
                        .environment("prod")
                        .revisionId(revisionId)
                        .layerClass(layerClass)
                        .selectorDocument(json.valueToTree(selector).toString())
                        .priority((short) 0)
                        .contributionKey("page")
                        .build();

        releaseRows.put(releaseId, releaseRow);
        draftRows.put(draftId, draftRow);
        memberRows.put(member.getId(), member);
        definitionRows.put(definitionId, definition);
        revisionRows.put(revisionId, revisionRow);
        assignmentRows.put(assignmentId, assignment);

        UiLayoutMetadataCapture.Binding binding =
                new UiLayoutMetadataCapture.Binding(
                        draftId,
                        new UiLayoutMetadataCapture.Scope("default", "prod", target, target),
                        seed.sourceRef(),
                        seed.authoring(),
                        baselineHash);
        UiLayoutBaselineMetadataSeed meta = seed.metadata();
        captures.put(
                draftId,
                captureCodec.seal(
                        UUID.randomUUID(),
                        binding,
                        seed.document(),
                        meta.source(),
                        meta.reproduction(),
                        meta.observation(),
                        meta.rawInputText()));

        heads.add(
                new PageRuntimeStore.Head(
                        pageId, target, UUID.randomUUID(), releaseId, UUID.randomUUID(), 1L, FIXED_INSTANT));
    }

    private String rawMetadata(UiLayoutMetadataCapture capture) {
        UiLayoutMetadataCapture.Binding binding = capture.binding();
        UiLayoutMetadataCapture.Scope scope = binding.scope();
        UiLayoutBaselineMetadataSeed.NativeDocumentSource source =
                (UiLayoutBaselineMetadataSeed.NativeDocumentSource) capture.source();
        UiLayoutBaselineMetadataSeed.Observation observed = capture.observation();
        ObjectNode row = json.createObjectNode();
        String serviceKey =
                tamperedServiceKeyDrafts.contains(binding.sourceDraftRef())
                        ? "untrusted-service"
                        : source.serviceKey();
        row.put("source_draft_ref", binding.sourceDraftRef().toString())
                .put("tenant_id", scope.tenantId())
                .put("environment", scope.environment())
                .put("root_component_type", scope.root().componentType())
                .put("root_component_id", scope.root().componentId())
                .put("component_type", scope.target().componentType())
                .put("component_id", scope.target().componentId())
                .put("capture_ref", capture.captureRef().toString())
                .put("baseline_source_ref", binding.baselineSourceRef())
                .put("document_type", binding.authoring().documentType())
                .put("authoring_schema_ref", binding.authoring().schemaRef())
                .put("authoring_schema_version", binding.authoring().schemaVersion())
                .put("baseline_content_hash", binding.baselineContentHash())
                .put("baseline_document_text", capture.baselineDocument().toString())
                .put("service_key", serviceKey)
                .put("producer_ref", source.producerRef())
                .put("publication_ref", source.publicationRef())
                .put("actor_ref", observed.actorRef())
                .put("administrative_unit", observed.administrativeUnit())
                .put("context_version", observed.contextVersion())
                .put("policy_ref", observed.policyRef())
                .put("policy_revision", observed.policyRevision())
                .put("captured_at_text", observed.capturedAt().toString())
                .put("raw_schema_text", capture.rawInputText())
                .put("raw_schema_hash", capture.rawInputHash())
                .put("origin_kind", "NATIVE_DOCUMENT")
                .put("native_document_ref", source.documentRef())
                .put("native_document_revision", source.documentRevision());
        for (String column :
                List.of(
                        "assembly_input_hash",
                        "resource_ref",
                        "operation_path",
                        "operation_method",
                        "schema_type",
                        "source_schema_ref",
                        "normalizer_ref",
                        "projector_ref",
                        "assembly_input_version",
                        "assembler_ref",
                        "assembly_input_text")) {
            row.putNull(column);
        }
        return row.toString();
    }

    private ObjectNode baselinePageDocument() {
        ObjectNode doc =
                json.createObjectNode().put("kind", UiLayoutPageRevisionReproduction.KIND).put("version", 1);
        ObjectNode config = doc.putObject("config");
        ObjectNode def = config.putObject("definition");
        def.putObject("layout").put("orientation", "columns").put("columns", 12).put("gap", "20px");
        def.putArray("widgets").add(tableWidget(TABLE_WIDGET_KEY));
        config.putObject("presentation")
                .put("title", "Workforce Overview")
                .put("icon", "groups")
                .put("description", "Governed workforce baseline");
        config.putArray("navigation");
        return doc;
    }

    private ObjectNode multiWidgetCandidateDocument() {
        ObjectNode doc =
                json.createObjectNode().put("kind", UiLayoutPageRevisionReproduction.KIND).put("version", 1);
        ObjectNode config = doc.putObject("config");
        ObjectNode def = config.putObject("definition");
        def.putObject("layout").put("orientation", "columns").put("columns", 12).put("gap", "20px");
        ObjectNode canvas =
                def.putObject("canvas").put("mode", "grid").put("columns", 12).put("gap", "16px");
        ObjectNode items = canvas.putObject("items");
        items.putObject(TABLE_WIDGET_KEY)
                .put("widgetKey", TABLE_WIDGET_KEY)
                .put("col", 1)
                .put("row", 1)
                .put("colSpan", 12)
                .put("rowSpan", 4);
        items.putObject(ANALYTICS_WIDGET_KEY)
                .put("widgetKey", ANALYTICS_WIDGET_KEY)
                .put("col", 1)
                .put("row", 5)
                .put("colSpan", 12)
                .put("rowSpan", 4);

        ArrayNode widgets = def.putArray("widgets");
        widgets.add(tableWidget(TABLE_WIDGET_KEY));
        widgets.add(analyticsPresentationWidget(ANALYTICS_WIDGET_KEY));

        config.putObject("presentation")
                .put("title", "Workforce Multi-Widget Overview")
                .put("icon", "groups")
                .put("description", "Governed multi-widget workforce projection");
        config.putArray("navigation")
                .addObject()
                .put("id", NAV_ENTRY_ID.toString())
                .put("groupId", GROUP_ID.toString())
                .put("label", "Workforce Multi-Widget Overview")
                .put("icon", "groups")
                .put("order", 1);
        return doc;
    }

    private ObjectNode tableWidget(String key) {
        ObjectNode w = json.createObjectNode().put("key", key);
        w.putObject("shell")
                .put("kind", "dashboard-card")
                .put("title", "Employee Directory")
                .put("icon", "groups")
                .put("showHeader", true);
        ObjectNode def = w.putObject("definition").put("id", "praxis-table");
        ObjectNode inputs = def.putObject("inputs");
        inputs.put("resourcePath", "/api/workforce/employees");
        ObjectNode cfg = inputs.putObject("config");
        cfg.putArray("columns");
        ObjectNode projection = cfg.putObject("columnProjection").put("source", "schema");
        projection.putArray("include").add("fullName");
        projection.putObject("overrides")
                .putObject("fullName")
                .put("header", "Full Name")
                .put("visible", true)
                .put("order", 0);
        return w;
    }

    private ObjectNode analyticsPresentationWidget(String key) {
        ObjectNode w = json.createObjectNode().put("key", key);
        w.putObject("shell")
                .put("kind", "dashboard-card")
                .put("title", "Compensation Analytics")
                .put("icon", "insights")
                .put("showHeader", true);
        ObjectNode def = w.putObject("definition").put("id", "praxis-analytics-presentation");
        ObjectNode inputs = def.putObject("inputs");
        inputs.put("resourcePath", "/api/workforce/employees");
        inputs.put("preferredFamily", "chart");
        inputs.put("title", "Salary Bands");
        inputs.put("height", 320);
        ObjectNode analytics = inputs.putObject("analytics");
        analytics.putArray("groupBy").add("department");
        analytics.putObject("metric").put("field", "salary").put("aggregation", "avg");
        return w;
    }

    private static <T> List<T> select(Map<UUID, T> records, Iterable<UUID> ids) {
        List<T> values = new ArrayList<>();
        ids.forEach(
                id -> {
                    if (records.containsKey(id)) {
                        values.add(records.get(id));
                    }
                });
        return values;
    }

    private record ActorState(
            String unit, String organization, String profile, String contextVersion, boolean canReadHead) {}
}
