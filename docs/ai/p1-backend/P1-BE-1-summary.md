# P1-BE-1 Summary - Patch Gate hard

Arquivos alterados
- praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java
- praxis-config-starter/src/test/java/org/praxisplatform/config/service/AiOrchestratorServiceSuggestedPatchTest.java
- praxis-config-starter/docs/ai/p1-backend/P1-BE-1-patch-gate-hard.md
- praxis-config-starter/docs/ai/p1-backend/P1-BE-2-deprecate-json-patch.md
- praxis-config-starter/docs/ai/p1-backend/P1-BE-3-deterministic-diff.md

Comando executado
```bash
cd praxis-config-starter && /opt/maven/bin/mvn test
```

Output
```
[INFO] Scanning for projects...
[INFO] 
[INFO] --------------< org.praxisplatform:praxis-config-starter >--------------
[INFO] Building praxis-config-starter 0.0.1-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- resources:3.3.1:resources (default-resources) @ praxis-config-starter ---
[INFO] Copying 0 resource from src/main/resources to target/classes
[INFO] Copying 14 resources from src/main/resources to target/classes
[INFO] 
[INFO] --- compiler:3.11.0:compile (default-compile) @ praxis-config-starter ---
[INFO] Nothing to compile - all classes are up to date
[INFO] 
[INFO] --- resources:3.3.1:testResources (default-testResources) @ praxis-config-starter ---
[INFO] Copying 3 resources from src/test/resources to target/test-classes
[INFO] 
[INFO] --- compiler:3.11.0:testCompile (default-testCompile) @ praxis-config-starter ---
[INFO] Changes detected - recompiling the module! :source
[INFO] Compiling 8 source files with javac [debug release 17] to target/test-classes
[INFO] Annotation processing is enabled because one or more processors were found
  on the class path. A future release of javac may disable annotation processing
  unless at least one processor is specified by name (-processor), or a search
  path is specified (--processor-path, --processor-module-path), or annotation
  processing is enabled explicitly (-proc:only, -proc:full).
  Use -Xlint:-options to suppress this message.
  Use -proc:none to disable annotation processing.
[INFO] /mnt/d/Developer/praxis-plataform/praxis-config-starter/src/test/java/org/praxisplatform/config/service/ContextRetrievalServiceTest.java: /mnt/d/Developer/praxis-plataform/praxis-config-starter/src/test/java/org/praxisplatform/config/service/ContextRetrievalServiceTest.java uses or overrides a deprecated API.
[INFO] /mnt/d/Developer/praxis-plataform/praxis-config-starter/src/test/java/org/praxisplatform/config/service/ContextRetrievalServiceTest.java: Recompile with -Xlint:deprecation for details.
[INFO] 
[INFO] --- surefire:3.1.2:test (default-test) @ praxis-config-starter ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running org.praxisplatform.config.controller.AiContextControllerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 14.65 s -- in org.praxisplatform.config.controller.AiContextControllerTest
[INFO] Running org.praxisplatform.config.DatabaseConnectionTest
Standard Commons Logging discovery in action with spring-jcl: please remove commons-logging.jar from classpath in order to avoid potential conflicts
22:19:46.450 [main] INFO org.springframework.test.context.support.AnnotationConfigContextLoaderUtils -- Could not detect default configuration classes for test class [org.praxisplatform.config.DatabaseConnectionTest]: DatabaseConnectionTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
22:19:47.118 [main] INFO org.springframework.boot.test.context.SpringBootTestContextBootstrapper -- Found @SpringBootConfiguration org.praxisplatform.config.PraxisConfigStarterApplication for test class org.praxisplatform.config.DatabaseConnectionTest

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.3)

2026-01-17T22:19:50.066-03:00  INFO 19174 --- [           main] o.p.config.DatabaseConnectionTest        : Starting DatabaseConnectionTest using Java 21.0.9 with PID 19174 (started by codex in /mnt/d/Developer/praxis-plataform/praxis-config-starter)
2026-01-17T22:19:50.068-03:00  INFO 19174 --- [           main] o.p.config.DatabaseConnectionTest        : No active profile set, falling back to 1 default profile: "default"
2026-01-17T22:19:57.670-03:00  INFO 19174 --- [           main] .s.d.r.c.RepositoryConfigurationDelegate : Bootstrapping Spring Data JPA repositories in DEFAULT mode.
2026-01-17T22:19:58.349-03:00  INFO 19174 --- [           main] .s.d.r.c.RepositoryConfigurationDelegate : Finished Spring Data repository scanning in 642 ms. Found 4 JPA repository interfaces.
2026-01-17T22:20:00.904-03:00  INFO 19174 --- [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Starting...
2026-01-17T22:20:02.197-03:00  INFO 19174 --- [           main] com.zaxxer.hikari.pool.HikariPool        : HikariPool-1 - Added connection conn0: url=jdbc:h2:mem:praxis_config_test user=SA
2026-01-17T22:20:02.202-03:00  INFO 19174 --- [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Start completed.
2026-01-17T22:20:02.986-03:00  INFO 19174 --- [           main] o.hibernate.jpa.internal.util.LogHelper  : HHH000204: Processing PersistenceUnitInfo [name: default]
2026-01-17T22:20:03.534-03:00  INFO 19174 --- [           main] org.hibernate.Version                    : HHH000412: Hibernate ORM core version 6.4.4.Final
2026-01-17T22:20:03.776-03:00  INFO 19174 --- [           main] o.h.c.internal.RegionFactoryInitiator    : HHH000026: Second-level cache disabled
2026-01-17T22:20:04.789-03:00  INFO 19174 --- [           main] o.s.o.j.p.SpringPersistenceUnitInfo      : No LoadTimeWeaver setup: ignoring JPA class transformer
2026-01-17T22:20:05.099-03:00  WARN 19174 --- [           main] org.hibernate.orm.deprecation            : HHH90000025: H2Dialect does not need to be specified explicitly using 'hibernate.dialect' (remove the property setting and it will be selected by default)
2026-01-17T22:20:09.513-03:00  INFO 19174 --- [           main] o.h.e.t.j.p.i.JtaPlatformInitiator       : HHH000489: No JTA platform available (set 'hibernate.transaction.jta.platform' to enable JTA platform integration)
2026-01-17T22:20:09.526-03:00  INFO 19174 --- [           main] j.LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
2026-01-17T22:20:11.878-03:00  INFO 19174 --- [           main] o.s.d.j.r.query.QueryEnhancerFactory     : Hibernate is in classpath; If applicable, HQL parser will be used.
2026-01-17T22:20:13.701-03:00  WARN 19174 --- [           main] JpaBaseConfiguration$JpaWebConfiguration : spring.jpa.open-in-view is enabled by default. Therefore, database queries may be performed during view rendering. Explicitly configure spring.jpa.open-in-view to disable this warning
2026-01-17T22:20:16.806-03:00  INFO 19174 --- [           main] o.s.b.a.e.web.EndpointLinksResolver      : Exposing 1 endpoint(s) beneath base path '/actuator'
2026-01-17T22:20:16.989-03:00  INFO 19174 --- [           main] o.p.config.DatabaseConnectionTest        : Started DatabaseConnectionTest in 28.943 seconds (process running for 47.814)
2026-01-17T22:20:18.371-03:00  INFO 19174 --- [           main] o.p.config.service.EmbeddingService      : Embedding config: provider=mock, dimensions=768, geminiKeyPresent=false, openaiKeyPresent=false
2026-01-17T22:20:19.321-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-stepper
2026-01-17T22:20:19.430-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-expansion
2026-01-17T22:20:19.436-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-tabs
2026-01-17T22:20:19.441-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-list
2026-01-17T22:20:19.446-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-files-upload
2026-01-17T22:20:19.479-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-filter-form
2026-01-17T22:20:19.484-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-crud
2026-01-17T22:20:19.490-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-dynamic-form-dialog-host
2026-01-17T22:20:19.508-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-dynamic-form
2026-01-17T22:20:19.514-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-cron-builder
2026-01-17T22:20:19.526-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-table
2026-01-17T22:20:19.542-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-filter-form-dialog-host
2026-01-17T22:20:19.548-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-radio-group
2026-01-17T22:20:19.553-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-week-input
2026-01-17T22:20:19.558-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-button-toggle
2026-01-17T22:20:19.565-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-email-input
2026-01-17T22:20:19.569-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-autocomplete
2026-01-17T22:20:19.575-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: praxis-field-shell
2026-01-17T22:20:19.580-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-async-select
2026-01-17T22:20:19.586-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-avatar
2026-01-17T22:20:19.591-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-price-range
2026-01-17T22:20:19.596-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-url-input
2026-01-17T22:20:19.602-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-button
2026-01-17T22:20:19.608-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-datetime-local-input
2026-01-17T22:20:19.613-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-currency
2026-01-17T22:20:19.618-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-file-upload
2026-01-17T22:20:19.623-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-date-range
2026-01-17T22:20:19.627-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-datepicker
2026-01-17T22:20:19.633-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-chips
2026-01-17T22:20:19.638-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-multi-select-tree
2026-01-17T22:20:19.643-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-time-input
2026-01-17T22:20:19.648-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-multi-select
2026-01-17T22:20:19.652-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-date-input
2026-01-17T22:20:19.657-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-cpf-cnpj-input
2026-01-17T22:20:19.661-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-selection-list
2026-01-17T22:20:19.666-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-transfer-list
2026-01-17T22:20:19.672-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-rating
2026-01-17T22:20:19.678-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-checkbox-group
2026-01-17T22:20:19.684-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-year-input
2026-01-17T22:20:19.696-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-text-input
2026-01-17T22:20:19.702-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-number-input
2026-01-17T22:20:19.706-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-color-picker
2026-01-17T22:20:19.710-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-colorpicker
2026-01-17T22:20:19.716-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-timepicker
2026-01-17T22:20:19.728-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-select
2026-01-17T22:20:19.736-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-range-slider
2026-01-17T22:20:19.742-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-searchable-select
2026-01-17T22:20:19.746-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-time-range
2026-01-17T22:20:19.750-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-search-input
2026-01-17T22:20:19.758-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-month-input
2026-01-17T22:20:19.763-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-color-input
2026-01-17T22:20:19.767-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-slider
2026-01-17T22:20:19.771-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-textarea
2026-01-17T22:20:19.776-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-tree-select
2026-01-17T22:20:19.780-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-password-input
2026-01-17T22:20:19.784-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-preload-status
2026-01-17T22:20:19.789-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-phone-input
2026-01-17T22:20:19.795-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: pdx-material-slide-toggle
2026-01-17T22:20:19.801-03:00  INFO 19174 --- [           main] o.p.c.r.AiRegistryBootstrapService       : AI registry bootstrap completed from classpath:ai-registry/registry-snapshot.json (components=58).
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 34.54 s -- in org.praxisplatform.config.DatabaseConnectionTest
[INFO] Running org.praxisplatform.config.RegistryIngestionServiceTest
2026-01-17T22:20:20.047-03:00  INFO 19174 --- [           main] t.c.s.AnnotationConfigContextLoaderUtils : Could not detect default configuration classes for test class [org.praxisplatform.config.RegistryIngestionServiceTest]: RegistryIngestionServiceTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-01-17T22:20:20.052-03:00  INFO 19174 --- [           main] .b.t.c.SpringBootTestContextBootstrapper : Found @SpringBootConfiguration org.praxisplatform.config.PraxisConfigStarterApplication for test class org.praxisplatform.config.RegistryIngestionServiceTest
2026-01-17T22:20:20.069-03:00  INFO 19174 --- [           main] o.p.c.service.RegistryIngestionService   : Ingested component: demo-component
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.106 s -- in org.praxisplatform.config.RegistryIngestionServiceTest
[INFO] Running org.praxisplatform.config.service.AiOrchestratorServiceActionPlanTest
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.126 s <<< FAILURE! -- in org.praxisplatform.config.service.AiOrchestratorServiceActionPlanTest
[ERROR] org.praxisplatform.config.service.AiOrchestratorServiceActionPlanTest.shouldInferFormatValueFromPrompt -- Time elapsed: 0.034 s <<< FAILURE!
org.opentest4j.AssertionFailedError: 

expected: "BRL|symbol|2"
 but was: "BRL"
	at java.base/jdk.internal.reflect.DirectConstructorHandleAccessor.newInstance(DirectConstructorHandleAccessor.java:62)
	at java.base/java.lang.reflect.Constructor.newInstanceWithCaller(Constructor.java:502)
	at org.praxisplatform.config.service.AiOrchestratorServiceActionPlanTest.shouldInferFormatValueFromPrompt(AiOrchestratorServiceActionPlanTest.java:333)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

[INFO] Running org.praxisplatform.config.service.AiOrchestratorServiceSuggestedPatchTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.036 s -- in org.praxisplatform.config.service.AiOrchestratorServiceSuggestedPatchTest
[INFO] Running org.praxisplatform.config.service.AiSuggestionsServiceTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.069 s -- in org.praxisplatform.config.service.AiSuggestionsServiceTest
[INFO] Running org.praxisplatform.config.service.ContextRetrievalServiceTest
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.196 s <<< FAILURE! -- in org.praxisplatform.config.service.ContextRetrievalServiceTest
[ERROR] org.praxisplatform.config.service.ContextRetrievalServiceTest.shouldReturnFullSchemaWithoutTruncation -- Time elapsed: 0.194 s <<< ERROR!
org.mockito.exceptions.misusing.PotentialStubbingProblem: 

Strict stubbing argument mismatch. Please check:
 - this invocation of 'embed' method:
    embeddingService.embed("query", null);
    -> at org.praxisplatform.config.service.ContextRetrievalService.searchApiMetadata(ContextRetrievalService.java:46)
 - has following stubbing(s) with different arguments:
    1. embeddingService.embed("");
      -> at org.praxisplatform.config.service.ContextRetrievalServiceTest.shouldReturnFullSchemaWithoutTruncation(ContextRetrievalServiceTest.java:53)
Typically, stubbing argument mismatch indicates user mistake when writing tests.
Mockito fails early so that you can debug potential problem easily.
However, there are legit scenarios when this exception generates false negative signal:
  - stubbing the same method multiple times using 'given().will()' or 'when().then()' API
    Please use 'will().given()' or 'doReturn().when()' API for stubbing.
  - stubbed method is intentionally invoked with different arguments by code under test
    Please use default or 'silent' JUnit Rule (equivalent of Strictness.LENIENT).
For more information see javadoc for PotentialStubbingProblem class.
	at org.praxisplatform.config.service.ContextRetrievalService.searchApiMetadata(ContextRetrievalService.java:46)
	at org.praxisplatform.config.service.ContextRetrievalService.searchApiMetadata(ContextRetrievalService.java:36)
	at org.praxisplatform.config.service.ContextRetrievalServiceTest.shouldReturnFullSchemaWithoutTruncation(ContextRetrievalServiceTest.java:76)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

[INFO] Running org.praxisplatform.config.service.UserConfigServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.034 s -- in org.praxisplatform.config.service.UserConfigServiceTest
[INFO] 
[INFO] Results:
[INFO] 
[ERROR] Failures: 
[ERROR]   AiOrchestratorServiceActionPlanTest.shouldInferFormatValueFromPrompt:333 
expected: "BRL|symbol|2"
 but was: "BRL"
[ERROR] Errors: 
[ERROR]   ContextRetrievalServiceTest.shouldReturnFullSchemaWithoutTruncation:76 » PotentialStubbingProblem 
Strict stubbing argument mismatch. Please check:
 - this invocation of 'embed' method:
    embeddingService.embed("query", null);
    -> at org.praxisplatform.config.service.ContextRetrievalService.searchApiMetadata(ContextRetrievalService.java:46)
 - has following stubbing(s) with different arguments:
    1. embeddingService.embed("");
      -> at org.praxisplatform.config.service.ContextRetrievalServiceTest.shouldReturnFullSchemaWithoutTruncation(ContextRetrievalServiceTest.java:53)
Typically, stubbing argument mismatch indicates user mistake when writing tests.
Mockito fails early so that you can debug potential problem easily.
However, there are legit scenarios when this exception generates false negative signal:
  - stubbing the same method multiple times using 'given().will()' or 'when().then()' API
    Please use 'will().given()' or 'doReturn().when()' API for stubbing.
  - stubbed method is intentionally invoked with different arguments by code under test
    Please use default or 'silent' JUnit Rule (equivalent of Strictness.LENIENT).
For more information see javadoc for PotentialStubbingProblem class.
[INFO] 
[ERROR] Tests run: 24, Failures: 1, Errors: 1, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:03 min
[INFO] Finished at: 2026-01-17T22:20:20-03:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.1.2:test (default-test) on project praxis-config-starter: There are test failures.
[ERROR] 
[ERROR] Please refer to /mnt/d/Developer/praxis-plataform/praxis-config-starter/target/surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```
