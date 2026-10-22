package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.SSLSocket;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real isolated Neon/JPA/JDBC gate; synthetic host policy/source. No migration, host HTTP or browser claim. */
@Tag("neon")
@EnabledIfSystemProperty(named="praxis.page.neon.it", matches="true")
class UiLayoutPageLifecyclePostgresIT {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static DriverManagerDataSource dataSource;
  private static SessionFactory factory;
  private static TransactionTemplate transactions;
  private static JpaRepositoryFactory repositories;
  private static JdbcTemplate jdbc;
  private static JsonNode assignment;
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-dynamic-page",UUID.randomUUID().toString());
  private final java.security.Principal principal = () -> "p1-neon-author";
  private final Map<String,UiLayoutPageChildSeed> sources = new LinkedHashMap<>();
  private final String creationKey = "p1-"+UUID.randomUUID();
  private boolean revoked;
  private boolean revokeBeforeCommit;
  private boolean revokeCreationBeforeCommit;
  private UiLayoutLifecycleCommandService commands;
  private UiLayoutLifecycleReadService reads;

  @BeforeAll static void connectAssignedRuntimeOnly() throws Exception {
    String file = System.getenv("PRAXIS_PAGE_P1_NEON_RUNTIME_FILE");
    if(file==null)throw new IllegalStateException("Explicit Neon runtime assignment required");
    assignment=JSON.readTree(Files.readString(Path.of(file)));
    requireAssignment(assignment);
    String url=assignment.path("jdbcUrl").asText();

    dataSource=new DriverManagerDataSource(url,assignment.path("username").asText(),assignment.path("password").asText()) {
      @Override public Connection getConnection() throws java.sql.SQLException {
        Connection c=super.getConnection();
        try { c.setSchema(assignment.path("schema").asText()); c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); return c; }
        catch (java.sql.SQLException e) { c.close(); throw e; }
      }
    };
    jdbc=new JdbcTemplate(dataSource);
    try(var connection=dataSource.getConnection()) {
      var field=org.postgresql.core.QueryExecutorBase.class.getDeclaredField("pgStream");field.setAccessible(true);
      var stream=(org.postgresql.core.PGStream)field.get(connection.unwrap(org.postgresql.jdbc.PgConnection.class).getQueryExecutor());
      assertThat(stream.getSocket()).isInstanceOf(SSLSocket.class);
      var tls=((SSLSocket)stream.getSocket()).getSession();
      assertThat(tls.getPeerHost()).isEqualTo(assignment.path("endpoint").asText());
      try(var statement=connection.createStatement();var row=statement.executeQuery("select current_user,current_schema(),current_database(),current_setting('server_version'),rolsuper,rolcreatedb,rolcreaterole,rolbypassrls from pg_roles where rolname=current_user")) {
        assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo(assignment.path("username").asText());
        assertThat(row.getString(2)).isEqualTo(assignment.path("schema").asText());assertThat(row.getString(3)).isEqualTo("neondb");
        for(int n=5;n<=8;n++)assertThat(row.getBoolean(n)).isFalse();
        System.out.println("P1 Neon project="+assignment.path("projectId").asText()+" branch="+assignment.path("branchId").asText()+" role="+row.getString(1)+" schema="+row.getString(2)+" PostgreSQL="+row.getString(4)+" clientTLS="+tls.getProtocol()+" peer="+tls.getPeerPrincipal().getName());
      }
    }
    assertThat(jdbc.queryForObject("select has_schema_privilege(current_user,current_schema(),'CREATE')",Boolean.class)).isFalse();
    assertThat(jdbc.queryForObject("select has_table_privilege(current_user,'ui_layout_revision','UPDATE')",Boolean.class)).isFalse();
    assertThat(jdbc.queryForObject("select has_table_privilege(current_user,'ui_layout_baseline_metadata_evidence','DELETE')",Boolean.class)).isFalse();
    assertThat(jdbc.queryForObject("select has_column_privilege(current_user,'ui_layout_definition','id','UPDATE')",Boolean.class)).isTrue();
    Configuration config=new Configuration();
    for(var type:List.of(UiLayoutDraft.class,UiLayoutDefinition.class,UiLayoutRevision.class,UiLayoutAssignmentRevision.class,
        UiLayoutRelease.class,UiLayoutReleaseMember.class,UiLayoutReleaseReview.class,UiLayoutReleaseApproval.class,
        UiLayoutReleaseHead.class,UiLayoutReleaseEvent.class))config.addAnnotatedClass(type);
    config.setProperty("hibernate.hbm2ddl.auto","validate");config.setProperty("hibernate.default_schema",assignment.path("schema").asText());
    config.setProperty("hibernate.show_sql","false");config.getProperties().put("hibernate.connection.datasource",dataSource);
    factory=config.buildSessionFactory();
    var transactionManager=new JpaTransactionManager(factory);transactionManager.setDataSource(dataSource);transactionManager.setJpaDialect(new HibernateJpaDialect());
    transactions=new TransactionTemplate(transactionManager);transactions.setTimeout(45);
    repositories=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
  }
  static void requireAssignment(JsonNode value) {
    String endpoint=value.path("endpoint").asText(), schema=value.path("schema").asText();
    String role=value.path("username").asText(), url=value.path("jdbcUrl").asText();
    if (!value.path("projectId").asText().matches("[a-z][a-z0-9-]{5,80}")
        || !value.path("branchId").asText().matches("br-[a-z0-9-]{5,80}")
        || !endpoint.matches("ep-[a-z0-9-]+\\.[a-z0-9-]+\\.aws\\.neon\\.tech")
        || !schema.matches("b1a_it_page_[a-z0-9_]{1,45}") || !role.matches("p1_page_[a-z0-9_]{1,45}")
        || value.path("password").asText().isBlank()) throw new IllegalArgumentException("Explicit isolated Neon assignment is invalid");
    var uri=java.net.URI.create(url.replaceFirst("^jdbc:", ""));
    var query=uri.getRawQuery();
    if (!url.startsWith("jdbc:postgresql://") || !endpoint.equals(uri.getHost()) || uri.getUserInfo()!=null
        || !"/neondb".equals(uri.getPath()) || query==null
        || !java.util.Arrays.asList(query.split("&")).contains("sslmode=verify-full")
        || !java.util.Arrays.asList(query.split("&")).contains("sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory")
        || query.contains("password=") || query.contains("user=")) throw new IllegalArgumentException("Neon assignment transport is invalid");
  }

  @AfterAll static void close() { if(factory!=null)factory.close(); }
  private static <T>T repo(Class<T> type){return repositories.getRepository(type);}

  @BeforeEach void configureRealLifecycle() {
    var invocation=new UiLayoutLifecycleInvocation(principal.getName(),"p1-neon-tenant","p1-unit","test","p1-context",new UiLayoutCompositionRegistration(root,List.of(root)));
    UiLayoutLifecycleInvocationProvider provider=(p,v,t)-> {if(!root.equals(t))throw new IllegalArgumentException();return invocation;};
    UiLayoutLifecycleAdmission admission=(op,i)->{};
    UiLayoutLifecycleStructureValidator structure=new UiLayoutLifecycleStructureValidator(){
      public void validateTarget(UiLayoutLifecycleInvocation i,UiLayoutTarget t,UiLayoutValidationContext v){assertThat(t).isEqualTo(root);}
      public void validateAuthoringBaseline(UiLayoutLifecycleInvocation i,UiLayoutTarget t,UiLayoutAuthoringDocumentDescriptor d,JsonNode b,UiLayoutValidationContext v){UiLayoutNativeStructureChecks.requireBaseline(i,t,d,b,UiLayoutPageRevisionReproductionTest.PATCH,v); if(revokeCreationBeforeCommit) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public int getOrder(){return -10;}public void beforeCommit(boolean readOnly){revoked=true;}});}
      public void validateAuthoringRevision(UiLayoutLifecycleInvocation i,UiLayoutTarget t,UiLayoutAuthoringDocumentDescriptor bd,JsonNode b,UiLayoutAuthoringDocumentDescriptor cd,JsonNode c,UiLayoutPatchDocumentDescriptor p,JsonNode patch,UiLayoutValidationContext v){UiLayoutNativeStructureChecks.requireRevision(i,t,bd,b,cd,c,p,patch,v);}
      public void validatePatch(UiLayoutLifecycleInvocation i,UiLayoutTarget t,JsonNode p,UiLayoutValidationContext v){
        if(revokeBeforeCommit)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public int getOrder(){return -10;}public void beforeCommit(boolean readOnly){revoked=true;}});
      }
      public void validateAssignment(UiLayoutLifecycleInvocation i,UiLayoutTarget t,UiLayoutResolutionCandidate.LayerClass l,UiLayoutAudienceSelector s,UiLayoutValidationContext v){}
      public void validateReleaseTargets(UiLayoutLifecycleInvocation i,List<UiLayoutTarget> t,UiLayoutValidationContext v){assertThat(t).containsExactly(root);}
    };
    UiLayoutDraftWorkspaceSource source=new UiLayoutDraftWorkspaceSource(){
      public UiLayoutDraftWorkspaceSeed capture(UiLayoutLifecycleInvocation i,UiLayoutValidationContext v){
        try{
          var page=UiLayoutPageRevisionReproductionTest.page();var children=new ArrayList<UiLayoutPageChildSeed>();
          UiLayoutPageRevisionReproduction.children(page).forEach((key,document)->children.add(register(i,key,document)));
          return new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(root,UiLayoutPageRevisionReproductionTest.DESCRIPTOR,
              UiLayoutPageRevisionReproductionTest.PATCH,"fixture:page",page,metadata(i,page),children)));
        }catch(Exception e){throw new IllegalStateException(e);}
      }
      public UiLayoutPageChildSeed capturePageChild(UiLayoutLifecycleInvocation i,UiLayoutTarget p,UiLayoutPageChildSource request,UiLayoutValidationContext v){return sources.get(request.widgetKey());}
      public void requirePageChild(UiLayoutLifecycleInvocation i,UiLayoutTarget p,UiLayoutPageChildSeed seed,UiLayoutValidationContext v){
        if(revoked||!seed.equals(sources.get(seed.request().widgetKey())))throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED,"Synthetic source revoked");
      }
    };
    var hashes=new CanonicalJsonHashService(JSON);var named=new NamedParameterJdbcTemplate(dataSource);
    commands=UiLayoutLifecycleCommandService.create(repo(UiLayoutDefinitionRepository.class),repo(UiLayoutRevisionRepository.class),repo(UiLayoutAssignmentRevisionRepository.class),repo(UiLayoutDraftRepository.class),
        repo(UiLayoutReleaseRepository.class),repo(UiLayoutReleaseMemberRepository.class),repo(UiLayoutReleaseReviewRepository.class),repo(UiLayoutReleaseApprovalRepository.class),repo(UiLayoutReleaseHeadRepository.class),repo(UiLayoutReleaseEventRepository.class),
        new JdbcUiLayoutRevisionAllocationLock(named),hashes,JSON,(release,contributions)->assertThat(contributions).hasSize(1),provider,admission,structure,source,
        new JdbcUiLayoutDraftCreationLock(named),named,(i,t,d,s,b,m)->{},transactions,(op,i)->Duration.ofSeconds(40));
    reads=new UiLayoutLifecycleReadService(provider,admission,repo(UiLayoutDraftRepository.class),repo(UiLayoutReleaseRepository.class),repo(UiLayoutReleaseReviewRepository.class),repo(UiLayoutReleaseHeadRepository.class),
        repo(UiLayoutDefinitionRepository.class),repo(UiLayoutRevisionRepository.class),repo(UiLayoutAssignmentRevisionRepository.class),JSON,hashes,structure,(op,i)->Duration.ofSeconds(40));
  }
  private UiLayoutBaselineMetadataSeed metadata(UiLayoutLifecycleInvocation i,JsonNode document){return UiLayoutMetadataTestFixtures.nativeMetadata(document,i.actorRef(),i.administrativeUnit(),i.contextVersion());}
  private UiLayoutPageChildSeed register(UiLayoutLifecycleInvocation i,String key,JsonNode document){
    var request=new UiLayoutPageChildSource(key,new UiLayoutPageChildSource.NativeSource("registered-native","fixture:"+key,"r1"));
    var seed=new UiLayoutPageChildSeed(request,new UiLayoutAuthoringDocumentDescriptor(document.path("kind").asText(),"fixture:native","1"),UiLayoutPageRevisionReproductionTest.PATCH,"fixture:"+key,document,metadata(i,document));
    sources.put(key,seed);return seed;
  }
  private UiLayoutDraftWorkspaceReceipt create(){return commands.createDraft(principal,"p1-context",root,creationKey);}
  private UiLayoutLifecycleCommandService.RevisionResult revise(UiLayoutDraftWorkspaceReceipt draft,JsonNode document,List<UiLayoutPageChildSource> sources){return commands.createRevision(principal,"p1-context",root,UUID.fromString(draft.draft().draftRef()),draft.draft().draftEtag(),new UiLayoutLifecycleCommandService.RevisionInput(UUID.randomUUID(),root,document.toString(),"🧭".repeat(1024),sources));}

  @Test void committedPageEvidenceRoundTripsThroughJsonbJpaAndFrozenRelease()throws Exception{
    var created=create();UUID draftId=UUID.fromString(created.draft().draftRef());
    var recovered=reads.draft(principal,"p1-context",root,draftId);assertThat(recovered.targets()).hasSize(1);
    var candidate=UiLayoutPageRevisionReproductionTest.page();
    var removed=candidate.deepCopy();((ObjectNode)removed.at("/config/definition")).putArray("widgets");
    var revision=revise(recovered,removed,null);
    revoked=true;
    assertThat(reads.draft(principal,"p1-context",root,draftId).targets()).hasSize(1);
    assertThatThrownBy(()->revise(revision.nextDraft(),candidate,null)).isInstanceOf(UiLayoutLifecycleException.class);
    // Removed children do not re-admit until restoration; the stored capture remains historical.
    revoked=false;
    var restored=revise(revision.nextDraft(),candidate,null);
    String newKey=UUID.randomUUID().toString();
    var newWidget=((ObjectNode)candidate.at("/config/definition/widgets/0")).deepCopy().put("key",newKey);
    ((com.fasterxml.jackson.databind.node.ArrayNode)candidate.at("/config/definition/widgets")).add(newWidget);
    var original=sources.values().iterator().next();
    var request=new UiLayoutPageChildSource(newKey,new UiLayoutPageChildSource.NativeSource("registered-native","fixture:"+newKey,"r1"));
    sources.put(newKey,new UiLayoutPageChildSeed(request,original.authoring(),original.patch(),"fixture:"+newKey,original.document(),original.metadata()));
    var added=revise(restored.nextDraft(),candidate,List.of(request));
    assertThat(jdbc.queryForObject("select jsonb_array_length(draft_document->'targets'->0->'pageChildren') from ui_layout_draft where id=?",Integer.class,draftId)).isEqualTo(3);
    var assigned=commands.createAssignment(principal,"p1-context",root,draftId,added.nextDraft().draft().draftEtag(),new UiLayoutLifecycleCommandService.AssignmentInput(UUID.randomUUID(),root,"TENANT",new UiLayoutAudienceSelectorCommand("p1-neon-tenant",null,null,null,null,null),(short)0,"page"));
    var frozen=commands.freezeRelease(principal,"p1-context",root,draftId,assigned.nextDraft().draft().draftEtag(),UUID.randomUUID());
    UUID release=UUID.fromString(frozen.release().releaseRef());
    var submitted=commands.submit(principal,"p1-context",root,release,frozen.review().reviewEtag());
    commands.approve(principal,"p1-context",root,release,submitted.reviewEtag(),"🧭".repeat(1024));
    var published=commands.publish(principal,"p1-context",root,UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish(),release,"🧭".repeat(1024));
    assertThat(published.activeReleaseRef()).isEqualTo(release.toString());
    assertThat(jdbc.queryForObject("select count(*) from ui_layout_release_member where release_id=?",Integer.class,release)).isEqualTo(1);
    assertThat(reads.draft(principal,"p1-context",root,draftId).frozenReleaseRef()).isEqualTo(release.toString());
    assertThat(jdbc.queryForObject("select min(char_length(created_reason)) from ui_layout_revision r join ui_layout_definition d on d.id=r.definition_id where d.component_id=?",Integer.class,root.componentId())).isEqualTo(1024);
    revoked=true;
    assertThat(reads.draft(principal,"p1-context",root,draftId).frozenReleaseRef()).isEqualTo(release.toString());
    assertThatThrownBy(this::create).isInstanceOf(UiLayoutLifecycleException.class);
    assertThatThrownBy(()->commands.rollback(principal,"p1-context",root,UiLayoutLifecycleCommandService.HeadWriteCondition.match(published.headEtag()),release,"denied restoration"))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertThat(jdbc.queryForObject("select active_release_id from ui_layout_release_head where root_component_id=?",UUID.class,root.componentId())).isEqualTo(release);
  }

  @Test void finalAdmissionRevocationRollsBackDraftAndMetadataTogether() {
    revokeCreationBeforeCommit=true;
    assertThatThrownBy(this::create).isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException)error).getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    assertThat(jdbc.queryForObject("select count(*) from ui_layout_draft where root_component_id=?",Integer.class,root.componentId())).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ui_layout_baseline_metadata_evidence where root_component_id=?",Integer.class,root.componentId())).isZero();
  }

  @Test void finalAdmissionRevocationRollsBackRevisionAndWorkspaceOnNewConnection()throws Exception{
    var created=create();UUID id=UUID.fromString(created.draft().draftRef());
    String before=jdbc.queryForObject("select draft_document::text from ui_layout_draft where id=?",String.class,id);
    UUID etag=jdbc.queryForObject("select draft_etag from ui_layout_draft where id=?",UUID.class,id);
    assertThatThrownBy(()->commands.createRevision(principal,"p1-context",root,id,created.draft().draftEtag(),
        new UiLayoutLifecycleCommandService.RevisionInput(UUID.randomUUID(),root,UiLayoutPageRevisionReproductionTest.page().toString(),"🧭".repeat(1025))))
        .isInstanceOf(UiLayoutLifecycleException.class);
    revoked=true;
    assertThat(reads.draft(principal,"p1-context",root,id).targets()).hasSize(1);
    assertThatThrownBy(this::create).isInstanceOf(UiLayoutLifecycleException.class);
    revoked=false;
    revokeBeforeCommit=true;
    assertThatThrownBy(()->revise(created,UiLayoutPageRevisionReproductionTest.page(),null)).isInstanceOf(UiLayoutLifecycleException.class);
    // DriverManagerDataSource obtains a fresh physical connection for each independent assertion.
    assertThat(jdbc.queryForObject("select draft_document::text from ui_layout_draft where id=?",String.class,id)).isEqualTo(before);
    assertThat(jdbc.queryForObject("select draft_etag from ui_layout_draft where id=?",UUID.class,id)).isEqualTo(etag);
    assertThat(jdbc.queryForObject("select count(*) from ui_layout_revision r join ui_layout_definition d on d.id=r.definition_id where d.component_id=?",Integer.class,root.componentId())).isZero();
  }
}
