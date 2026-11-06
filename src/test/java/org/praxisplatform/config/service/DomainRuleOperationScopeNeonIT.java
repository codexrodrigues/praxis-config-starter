package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import db.migration.V72__scope_rule_snapshot_operations;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Synthetic history in the newly created proof database; never uses domain/runtime datasource defaults. */
@EnabledIfEnvironmentVariable(named="G3_OPERATION_NEON_PROOF",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DomainRuleOperationScopeNeonIT {
  static final ObjectMapper MAPPER=new ObjectMapper();
  static final String FAMILY="proof.ontology.operations";
  static Connection db;static String url,user,password;
  static UUID a1,a2,b1,b2,policy,rolloutA,rolloutB;static String beforeHistory;static UUID headEtag;

  @BeforeAll static void prepare()throws Exception {
    String branch=required("PROOF_NEON_BRANCH");
    assertThat(branch).isEqualTo(required("BULK_PROOF_DISPOSABLE_BRANCH"))
        .isNotIn("br-super-sound-b7urizmd","br-bitter-bonus-b7i0vfko","br-purple-dust-b70ss3dj","br-blue-mode-b7oabglw");
    url=required("G3_NEON_JDBC");user=required("G3_NEON_USER");password=required("G3_NEON_PASSWORD");
    assertThat(url).startsWith("jdbc:postgresql://"+required("BULK_PROOF_EXPECTED_HOST")+"/g3_operation_proof?")
        .contains("sslmode=require");
    db=DriverManager.getConnection(url,user,password);
    assertThat(scalar("select current_database()",String.class)).isEqualTo("g3_operation_proof");
    assertThat(scalar("select to_regclass('public.domain_rule_snapshot')::text",String.class)).isNull();
    flyway("71").migrate();
  }
  @AfterAll static void close()throws Exception{if(db!=null)db.close();}
  static Flyway flyway(String target) {
    var builder=Flyway.configure().dataSource(url,user,password).schemas("public").defaultSchema("public")
        .locations("classpath:db/migration");
    if(target!=null)builder.target(target);
    return builder.load();
  }
  static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+key);return value;}
  @SuppressWarnings("unchecked") static <T>T scalar(String sql,Class<T> type)throws Exception {
    try(var q=db.createStatement();var r=q.executeQuery(sql)){assertThat(r.next()).isTrue();return (T)r.getObject(1);}
  }
  static void execute(String sql,Object...args)throws Exception {
    try(var q=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}
  }
  static UUID snapshot(String operation,int version,int revision,UUID previous,boolean migrated)throws Exception {
    var rule=new RuleSetDefinition(new RuleSetRef("proof","proof.domain",FAMILY,operation,version),List.of("request"),
        List.of(new DecisionSlot("allowed",DecisionStage.DOMAIN_DECISION,SlotCardinality.SINGLE,OverridePolicy.FORBIDDEN,DecisionAggregationPolicy.SINGLE_RESULT,null,List.of())),
        List.of(new DecisionBinding("allowed","allowed",DecisionSource.PRODUCT,null,RuleExecutorRef.jsonLogic(MAPPER.readTree("true")),List.of(),1,true,RuleDecision.DENY,"NO",List.of(),null)),
        new RuleRuntimeCompatibility("historical-fixture",RuleRuntimeCompatibility.current().jsonLogicDialectVersion(),RuleRuntimeCompatibility.current().jsonLogicCorpusSha256()),RuleFailPolicy.FAIL_CLOSED);
    var id=UUID.randomUUID();String key=id.toString();
    var value=new PublishedRuleSnapshot(PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,key,"2","g3-proof","synthetic-proof",revision,
        "2026-01-01T00:00:00Z",previous==null?null:previous.toString(),"proof/1.0","2026-01-01T00:00:00Z",null,
        List.of(new RuleSnapshotSource("synthetic-source","synthetic-definition",1,"A".repeat(64))),
        List.of(new RuleSnapshotApproval("synthetic-approval","TECHNICAL_REVIEWER","synthetic-reviewer","2026-01-01T00:00:00Z","B".repeat(64))),rule);
    String json=MAPPER.writeValueAsString(value),hash=PraxisCanonicalJson.sha256(MAPPER.valueToTree(value));
    execute("insert into domain_rule_snapshot(id,tenant_id,environment,snapshot_key,rule_set_key,rule_set_version,publication_revision,snapshot_payload,content_hash,supersedes_snapshot_id,published_by,published_at"
        +(migrated?",operation_key":"")+") values(?,'2','g3-proof',?,?,?, ?,cast(? as jsonb),?,?,'synthetic-proof',current_timestamp"+(migrated?",?":"")+")",
        migrated?new Object[]{id,key,FAMILY,version,revision,json,hash,previous,operation}:new Object[]{id,key,FAMILY,version,revision,json,hash,previous});
    return id;
  }
  static boolean operationColumn()throws Exception{return scalar("select count(*) from information_schema.columns where table_schema='public' and table_name='domain_rule_snapshot' and column_name='operation_key'",Long.class)>0;}
  static void directMigration()throws Exception {
    Context context=mock(Context.class);when(context.getConnection()).thenReturn(db);
    new V72__scope_rule_snapshot_operations().migrate(context);
  }
  static void rejected(String sql,String state,Object...args)throws Exception {
    boolean auto=db.getAutoCommit();db.setAutoCommit(false);var save=db.setSavepoint();
    try {assertThatThrownBy(()->execute(sql,args)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo(state));}
    finally{db.rollback(save);db.commit();db.setAutoCommit(auto);}
  }

  @Test @Order(1) void corruptImmutableHistoryPreventsAnyRelationalRewrite()throws Exception {
    db.setAutoCommit(false);
    try {
      UUID bad=snapshot("evaluate",1,1,null,false);
      execute("update domain_rule_snapshot set content_hash=? where id=?","0".repeat(64),bad);
      assertThatThrownBy(DomainRuleOperationScopeNeonIT::directMigration).hasMessageContaining("hash verification failed");
    }finally{db.rollback();db.setAutoCommit(true);}
    assertThat(operationColumn()).isFalse();
    assertThat(scalar("select count(*) from domain_rule_snapshot",Long.class)).isZero();
  }
  @Test @Order(2) void unattributableHeartbeatRollsBackAllAddedColumns()throws Exception {
    db.setAutoCommit(false);
    try {
      execute("insert into domain_rule_host_status(id,tenant_id,environment,rule_set_key,host_actor_ref,ready,host_contract_version,observed_at) values(?,'2','g3-proof','unpublished-family','synthetic-host',false,'proof/1.0',current_timestamp)",UUID.randomUUID());
      assertThatThrownBy(DomainRuleOperationScopeNeonIT::directMigration).isInstanceOf(SQLException.class).hasMessageContaining("unverifiable");
    }finally{db.rollback();db.setAutoCommit(true);}
    assertThat(operationColumn()).isFalse();
  }
  @Test @Order(3) void flywayDiscoversJavaMigrationAndPreservesImmutableHistoryAndHeadIdentity()throws Exception {
    a1=snapshot("evaluate",1,1,null,false);a2=snapshot("evaluate",2,2,a1,false);headEtag=UUID.randomUUID();
    execute("insert into domain_rule_snapshot_head(id,tenant_id,environment,rule_set_key,active_snapshot_id,activation_revision,head_etag,updated_at) values(?,'2','g3-proof',?,?,7,?,current_timestamp)",UUID.randomUUID(),FAMILY,a2,headEtag);
    execute("insert into domain_rule_snapshot_event(id,tenant_id,environment,rule_set_key,event_type,to_snapshot_id,actor,created_at) values(?,'2','g3-proof',?,'PUBLISHED',?,'synthetic-proof',current_timestamp)",UUID.randomUUID(),FAMILY,a1);
    execute("insert into domain_rule_host_status(id,tenant_id,environment,rule_set_key,host_actor_ref,loaded_snapshot_key,ready,host_contract_version,observed_at) values(?,'2','g3-proof',?,'synthetic-host',?,false,'proof/1.0',current_timestamp)",UUID.randomUUID(),FAMILY,a2.toString());
    policy=UUID.randomUUID();rolloutA=UUID.randomUUID();
    execute("insert into domain_rule_rollout_policy(id,tenant_id,environment,rule_set_key,policy_key,policy_version,enforcement_mode,minimum_fresh_probes,minimum_ready_ratio,block_on_incompatible,stale_after_seconds,active,status,created_by,created_at,approved_by,approved_at,activated_by,activated_at) values(?,'2','g3-proof',?,'synthetic-policy',1,'REQUIRED',1,1,true,120,true,'ACTIVE','synthetic-author',current_timestamp,'synthetic-approver',current_timestamp,'synthetic-activator',current_timestamp)",policy,FAMILY);
    execute("insert into domain_rule_snapshot_rollout(id,tenant_id,environment,rule_set_key,candidate_snapshot_id,expected_active_snapshot_id,expected_head_etag,policy_id,status,created_by,created_at,updated_at) values(?,'2','g3-proof',?,?,?,?,?,'PREPARING','synthetic-author',current_timestamp,current_timestamp)",rolloutA,FAMILY,a2,a1,UUID.randomUUID(),policy);
    execute("insert into domain_rule_candidate_probe(id,rollout_id,tenant_id,environment,rule_set_key,host_actor_ref,candidate_snapshot_id,candidate_snapshot_key,candidate_content_hash,preload_ready,host_contract_version,observed_at,received_at) select ?,?,'2','g3-proof',?,'synthetic-host',id,snapshot_key,content_hash,false,'proof/1.0',current_timestamp,current_timestamp from domain_rule_snapshot where id=?",UUID.randomUUID(),rolloutA,FAMILY,a2);
    execute("insert into domain_rule_snapshot_rollout_event(id,rollout_id,tenant_id,environment,rule_set_key,event_type,actor_ref,created_at) values(?,?,'2','g3-proof',?,'CREATED','synthetic-author',current_timestamp)",UUID.randomUUID(),rolloutA,FAMILY);
    beforeHistory=scalar("select jsonb_agg(jsonb_build_object('id',id,'payload',snapshot_payload,'hash',content_hash,'supersedes',supersedes_snapshot_id) order by publication_revision)::text from domain_rule_snapshot",String.class);
    var result=flyway(null).migrate();assertThat(result.migrationsExecuted).isEqualTo(1);flyway(null).validate();
    assertThat(operationColumn()).isTrue();
    assertThat(scalar("select jsonb_agg(jsonb_build_object('id',id,'payload',snapshot_payload,'hash',content_hash,'supersedes',supersedes_snapshot_id) order by publication_revision)::text from domain_rule_snapshot",String.class)).isEqualTo(beforeHistory);
    assertThat(scalar("select operation_key from domain_rule_snapshot_head",String.class)).isEqualTo("evaluate");
    assertThat(scalar("select head_etag from domain_rule_snapshot_head",UUID.class)).isEqualTo(headEtag);
    assertThat(scalar("select activation_revision from domain_rule_snapshot_head",Long.class)).isEqualTo(7L);
    assertThat(scalar("select operation_key from domain_rule_host_status",String.class)).isEqualTo("evaluate");
    assertThat(scalar("select operation_key from domain_rule_snapshot_event",String.class)).isEqualTo("evaluate");
    for(String table:List.of("domain_rule_snapshot_rollout","domain_rule_candidate_probe","domain_rule_snapshot_rollout_event"))
      assertThat(scalar("select operation_key from "+table,String.class)).isEqualTo("evaluate");
    assertThat(scalar("select count(*) from information_schema.columns where table_name='domain_rule_rollout_policy' and column_name='operation_key'",Long.class)).isZero();
  }
  @Test @Order(4) void sameFamilyAndVersionsCanCoexistAcrossOperations()throws Exception {
    b1=snapshot("delete",1,1,null,true);b2=snapshot("delete",2,2,b1,true);
    execute("insert into domain_rule_snapshot_head(id,tenant_id,environment,rule_set_key,operation_key,active_snapshot_id,activation_revision,head_etag,updated_at) values(?,'2','g3-proof',?,'delete',?,1,?,current_timestamp)",UUID.randomUUID(),FAMILY,b1,UUID.randomUUID());
    assertThat(scalar("select count(*) from domain_rule_snapshot_head",Long.class)).isEqualTo(2L);
    assertThat(scalar("select active_snapshot_id from domain_rule_snapshot_head where operation_key='evaluate'",UUID.class)).isEqualTo(a2);
  }
  @Test @Order(5) void headCannotSelectContentFromAnotherOperation()throws Exception {
    rejected("update domain_rule_snapshot_head set active_snapshot_id=? where operation_key='evaluate'","23503",b1);
  }
  @Test @Order(6) void supersedesCannotCrossOperation()throws Exception {
    rejected("update domain_rule_snapshot set supersedes_snapshot_id=? where id=?","23503",b1,a2);
  }
  @Test @Order(7) void eventsCannotCrossOperation()throws Exception {
    rejected("update domain_rule_snapshot_event set to_snapshot_id=? where operation_key='evaluate'","23503",b1);
  }
  @Test @Order(8) void operationColumnCannotDisagreeWithImmutableContent()throws Exception {
    rejected("update domain_rule_snapshot set operation_key='forged' where id=?","23514",a1);
  }
  @Test @Order(9) void repeatedVersionRemainsImmutableWithinOneOperation()throws Exception {
    rejected("insert into domain_rule_snapshot(id,tenant_id,environment,snapshot_key,rule_set_key,operation_key,rule_set_version,publication_revision,snapshot_payload,content_hash,published_by,published_at) select ?,tenant_id,environment,?,rule_set_key,operation_key,rule_set_version,99,snapshot_payload,content_hash,published_by,published_at from domain_rule_snapshot where id=?","23505",UUID.randomUUID(),UUID.randomUUID().toString(),a1);
  }
  @Test @Order(10) void oneHostCanReportBothOperationsWithoutOverwritingEither()throws Exception {
    execute("insert into domain_rule_host_status(id,tenant_id,environment,rule_set_key,operation_key,host_actor_ref,loaded_snapshot_key,ready,host_contract_version,observed_at) values(?,'2','g3-proof',?,'delete','synthetic-host',?,false,'proof/1.0',current_timestamp)",UUID.randomUUID(),FAMILY,b1.toString());
    assertThat(scalar("select count(*) from domain_rule_host_status",Long.class)).isEqualTo(2L);
  }
  @Test @Order(11) void independentRolloutsCoexistAndCannotExchangeCandidates()throws Exception {
    rolloutB=UUID.randomUUID();
    execute("insert into domain_rule_snapshot_rollout(id,tenant_id,environment,rule_set_key,operation_key,candidate_snapshot_id,expected_active_snapshot_id,expected_head_etag,policy_id,status,created_by,created_at,updated_at) values(?,'2','g3-proof',?,'delete',?,?,?,?,'PREPARING','synthetic-author',current_timestamp,current_timestamp)",rolloutB,FAMILY,b2,b1,UUID.randomUUID(),policy);
    assertThat(scalar("select count(*) from domain_rule_snapshot_rollout",Long.class)).isEqualTo(2L);
    rejected("update domain_rule_snapshot_rollout set candidate_snapshot_id=? where id=?","23503",b2,rolloutA);
    rejected("update domain_rule_snapshot_rollout set expected_active_snapshot_id=? where id=?","23503",b1,rolloutA);
  }
  @Test @Order(12) void probeAndRolloutEventCannotBeRelabeledToAnotherOperation()throws Exception {
    rejected("update domain_rule_candidate_probe set operation_key='delete' where rollout_id=?","23503",rolloutA);
    rejected("update domain_rule_snapshot_rollout_event set operation_key='delete' where rollout_id=?","23503",rolloutA);
  }
}
