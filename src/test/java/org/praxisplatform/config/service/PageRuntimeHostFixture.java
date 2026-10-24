package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Host-attested invertible registration plus the real Config auto-configuration. */
final class PageRuntimeHostFixture implements AutoCloseable {
  record Scope(String tenant, String application, String environment, String namespace) {}
  record PhysicalRoot(String tenant, String environment, UiLayoutTarget root) {}
  record Actor(String name, String unit, String version, Scope scope) implements Principal {
    public String getName() { return name; }
  }
  final Scope scope;
  final Actor alice;
  final UiLayoutTarget groups;
  final Map<String, Actor> actors = new ConcurrentHashMap<>();
  final Map<Scope, UiLayoutTarget> groupRoots = new ConcurrentHashMap<>();
  final Map<PhysicalRoot, Scope> inverseRoots = new ConcurrentHashMap<>();
  final AnnotationConfigApplicationContext application = new AnnotationConfigApplicationContext();
  final ObjectNode sourcePage;
  final UiLayoutLifecycleCommandService commands;
  final UiLayoutLifecycleReadService reads;
  final PageCreationService pages;
  final PageRuntimeReadService runtime;
  volatile java.util.function.Consumer<PageCreationContext> mappingCallback = context -> {};
  volatile java.util.function.Consumer<PageCreationContext> nativeCallback = context -> {};
  volatile java.util.function.UnaryOperator<EffectiveUiAudience> audienceProjection = value -> value;
  volatile BiConsumer<UiLayoutLifecycleOperation, UiLayoutLifecycleInvocation> grant = (operation, invocation) -> {};
  volatile BiConsumer<UiLayoutLifecycleInvocation, UiLayoutTarget> structureCallback = (invocation, target) -> {};
  volatile BiConsumer<UiLayoutLifecycleInvocation, UiLayoutTarget> metadataCallback = (invocation, target) -> {};
  volatile BiConsumer<UiLayoutLifecycleOperation, UiLayoutLifecycleInvocation> budgetCallback = (operation, invocation) -> {};
  @FunctionalInterface
  interface PageGrant {
    void require(PageCreationOperation operation, PageCreationContext context, PageSourceReference reference);
  }
  volatile PageGrant pageGrant = (operation, context, reference) -> {};
  final java.util.concurrent.atomic.AtomicInteger nativeCaptures = new java.util.concurrent.atomic.AtomicInteger();
  volatile Duration budget = Duration.ofSeconds(40);

  PageRuntimeHostFixture(String tenant) throws Exception {
    scope = new Scope(tenant, "app-a", "test", "/pages");
    alice = addActor("alice-" + tenant, "unit-a", "c1", scope);
    groups = new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString());
    bindGroups(scope, groups);
    sourcePage = UiLayoutPageRevisionReproductionTest.page();
    UiLayoutLifecycleStructureValidator structure = new UiLayoutLifecycleStructureValidator() {
      public void validateTarget(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutValidationContext validation) {
        validation.require(invocation);
        structureCallback.accept(invocation, target);
        assertThat(invocation.composition().targets()).contains(target);
      }
      public void validateAuthoringBaseline(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
          UiLayoutAuthoringDocumentDescriptor descriptor, JsonNode baseline, UiLayoutValidationContext validation) {
        UiLayoutNativeStructureChecks.requireBaseline(invocation, target, descriptor, baseline, UiLayoutPageRevisionReproductionTest.PATCH, validation);
      }
      public void validateAuthoringRevision(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
          UiLayoutAuthoringDocumentDescriptor before, JsonNode baseline, UiLayoutAuthoringDocumentDescriptor after,
          JsonNode candidate, UiLayoutPatchDocumentDescriptor descriptor, JsonNode patch, UiLayoutValidationContext validation) {
        UiLayoutNativeStructureChecks.requireRevision(invocation, target, before, baseline, after, candidate, descriptor, patch, validation);
      }
      public void validatePatch(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, JsonNode patch, UiLayoutValidationContext validation) {
        validation.require(invocation);
      }
      public void validateAssignment(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
          UiLayoutResolutionCandidate.LayerClass layer, UiLayoutAudienceSelector selector, UiLayoutValidationContext validation) {
        validation.require(invocation);
      }
      public void validateReleaseTargets(UiLayoutLifecycleInvocation invocation, List<UiLayoutTarget> targets, UiLayoutValidationContext validation) {
        validation.require(invocation);
        assertThat(targets).containsExactly(invocation.rootTarget());
      }
    };
    UiLayoutDraftWorkspaceSource source = new UiLayoutDraftWorkspaceSource() {
      public UiLayoutDraftWorkspaceSeed capture(UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation) {
        var authority = current(invocation);
        if (invocation.rootTarget().equals(UiLayoutPageRevisionReproductionTest.PAGE)) return new UiLayoutDraftWorkspaceSeed(List.of(nativePageSeed(invocation)));
        if (!invocation.rootTarget().equals(groupRoots.get(authority.scope()))) throw denied();
        var baseline = groupsDocument();
        return new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(invocation.rootTarget(),
            new UiLayoutAuthoringDocumentDescriptor("praxis.navigation-groups.editor", "fixture:groups", "1"),
            UiLayoutPageRevisionReproductionTest.PATCH, "fixture:groups", baseline, metadata(invocation, baseline))));
      }
      public void requirePageChild(UiLayoutLifecycleInvocation invocation, UiLayoutTarget page,
          UiLayoutPageChildSeed seed, UiLayoutValidationContext validation) {
        validation.require(invocation);
        assertThat(seed.document()).isEqualTo(UiLayoutPageRevisionReproduction.children(sourcePage).get(seed.request().widgetKey()));
      }
    };
    UiLayoutBaselineMetadataAdmission metadataAdmission = (invocation, target, descriptor, reference, baseline, evidence) -> {
      current(invocation); metadataCallback.accept(invocation, target);
    };
    PageCreationHost host = new PageCreationHost() {
      public PageCreationContext resolve(Principal principal, String version) {
        var actor = current(principal, version);
        return context(actor, groupRoots.get(actor.scope()));
      }
      public PageCreationContext resolveLifecycle(UiLayoutLifecycleInvocation invocation) {
        var actor = current(invocation);
        if ("praxis-app-shell".equals(invocation.rootTarget().componentType())
            && !actor.scope().equals(inverseRoots.get(physical(actor.scope(), invocation.rootTarget())))) throw denied();
        return new PageCreationContext(actor.scope().application(), actor.scope().namespace(), invocation);
      }
      public UiLayoutTarget navigationGroupsRoot(PageCreationContext context) {
        mappingCallback.accept(context);
        var actor = current(context.invocation());
        if (!actor.scope().application().equals(context.applicationId()) || !actor.scope().namespace().equals(context.routeNamespace())) throw denied();
        var root = groupRoots.get(actor.scope());
        if (root == null || !actor.scope().equals(inverseRoots.get(physical(actor.scope(), root)))) throw denied();
        return root;
      }
      public void require(PageCreationOperation operation, PageCreationContext context, PageSourceReference reference) {
        current(context.invocation());
        pageGrant.require(operation, context, reference);
      }
      public void requireCreation(PageCreationContext context, PageSourceReference source) {
        require(PageCreationOperation.CREATE_PAGE, context, null);
        require(PageCreationOperation.READ_SOURCE, context, source);
        require(PageCreationOperation.COPY_SOURCE, context, source);
      }
      public UiLayoutDraftWorkspaceSeed.TargetSeed captureNative(PageCreationContext context, PageSourceReference.Native reference) {
        assertThat(reference).isEqualTo(new PageSourceReference.Native("fixture:page", "r1"));
        nativeCaptures.incrementAndGet();
        return nativePageSeed(context.invocation());
      }
      public void requireNativeProvenance(PageCreationContext context, PageSourceReference.Native origin,
          UiLayoutDraftWorkspaceSeed.TargetSeed captured) {
        PageNativeProvenanceTestPolicies.require(context, captured,
            () -> requireNative(context, origin, captured), source, metadataAdmission);
      }
      public void requireNative(PageCreationContext context, PageSourceReference.Native reference,
          UiLayoutDraftWorkspaceSeed.TargetSeed captured) {
        nativeCallback.accept(context);
        current(context.invocation());
        assertThat(reference).isEqualTo(new PageSourceReference.Native("fixture:page", "r1"));
        assertThat(captured.document()).isEqualTo(sourcePage);
      }
    };
    UiLayoutLifecycleInvocationProvider provider = (principal, version, root) -> {
      var actor = current(principal, version);
      if (!root.equals(UiLayoutPageRevisionReproductionTest.PAGE) && !root.equals(groupRoots.get(actor.scope()))) throw denied();
      return context(actor, root).invocation();
    };
    var named = new NamedParameterJdbcTemplate(PageRuntimePostgresFixture.dataSource);
    application.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class, () -> PageRuntimePostgresFixture.JSON);
    application.registerBean(CanonicalJsonHashService.class, () -> new CanonicalJsonHashService(PageRuntimePostgresFixture.JSON));
    application.registerBean("configNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class, () -> named);
    application.registerBean("uiLayoutLifecycleConfigTransactionOperations", org.springframework.transaction.support.TransactionOperations.class,
        () -> PageRuntimePostgresFixture.transactions);
    application.registerBean(PageCreationHost.class, () -> host);
    application.registerBean(EffectiveUiAudienceProvider.class, () -> (principal, version) -> {
      var actor = current(principal, version);
      return audienceProjection.apply(new EffectiveUiAudience(actor.scope().tenant(), actor.scope().environment(),
          actor.name(), null, null, null, Set.of(), actor.version()));
    });
    application.registerBean(UiLayoutLifecycleInvocationProvider.class, () -> provider);
    application.registerBean(UiLayoutLifecycleAdmission.class, () -> (operation, invocation) -> {
      current(invocation); grant.accept(operation, invocation);
    });
    application.registerBean(UiLayoutLifecycleStructureValidator.class, () -> structure);
    application.registerBean(UiLayoutDraftWorkspaceSource.class, () -> source);
    application.registerBean(UiLayoutBaselineMetadataAdmission.class, () -> metadataAdmission);
    application.registerBean(UiLayoutValidationBudgetPolicy.class, () -> (operation, invocation) -> { budgetCallback.accept(operation, invocation); return budget; });
    application.registerBean(UiLayoutReleaseValidator.class, () -> (release, members) -> {});
    application.registerBean(UiLayoutRevisionAllocationLock.class, () -> new JdbcUiLayoutRevisionAllocationLock(named));
    application.registerBean(UiLayoutDraftCreationLock.class, () -> new JdbcUiLayoutDraftCreationLock(named));
    for (var type : List.of(UiLayoutDefinitionRepository.class, UiLayoutRevisionRepository.class,
        UiLayoutAssignmentRevisionRepository.class, UiLayoutDraftRepository.class, UiLayoutReleaseRepository.class,
        UiLayoutReleaseMemberRepository.class, UiLayoutReleaseReviewRepository.class, UiLayoutReleaseApprovalRepository.class,
        UiLayoutReleaseHeadRepository.class, UiLayoutReleaseEventRepository.class)) register(type);
    application.register(org.praxisplatform.config.autoconfig.UiLayoutResolutionAutoConfiguration.class);
    application.refresh();
    commands = application.getBean(UiLayoutLifecycleCommandService.class);
    reads = application.getBean(UiLayoutLifecycleReadService.class);
    pages = application.getBean(PageCreationService.class);
    runtime = application.getBean(PageRuntimeReadService.class);
    assertThat(application.getBeansOfType(PageCreationService.class)).hasSize(1);
    assertThat(application.getBeansOfType(UiLayoutLifecycleCommandService.class)).hasSize(1);
  }

  private UiLayoutDraftWorkspaceSeed.TargetSeed nativePageSeed(UiLayoutLifecycleInvocation invocation) {
        var children = new ArrayList<UiLayoutPageChildSeed>();
        UiLayoutPageRevisionReproduction.children(sourcePage).forEach((key, child) -> children.add(
            new UiLayoutPageChildSeed(new UiLayoutPageChildSource(key,
                new UiLayoutPageChildSource.NativeSource("registered-native", "fixture:" + key, "r1")),
                new UiLayoutAuthoringDocumentDescriptor(child.path("kind").asText(), "fixture:child", "1"),
                UiLayoutPageRevisionReproductionTest.PATCH, "fixture:" + key, child, metadata(invocation, child))));
        return new UiLayoutDraftWorkspaceSeed.TargetSeed(UiLayoutPageRevisionReproductionTest.PAGE,
            UiLayoutPageRevisionReproductionTest.DESCRIPTOR, UiLayoutPageRevisionReproductionTest.PATCH,
            "fixture:page", sourcePage, metadata(invocation, sourcePage), children);
  }

  Actor addActor(String name, String unit, String version, Scope scope) {
    var actor = new Actor(name, unit, version, scope); actors.put(name, actor); return actor;
  }
  private static PhysicalRoot physical(Scope scope, UiLayoutTarget root) {
    return new PhysicalRoot(scope.tenant(), scope.environment(), root);
  }
  void bindGroups(Scope scope, UiLayoutTarget root) {
    var previous = inverseRoots.putIfAbsent(physical(scope, root), scope);
    if (previous != null && !previous.equals(scope)) throw denied();
    groupRoots.put(scope, root);
  }
  PageCreationContext context(Actor actor, UiLayoutTarget root) {
    return new PageCreationContext(actor.scope().application(), actor.scope().namespace(),
        new UiLayoutLifecycleInvocation(actor.name(), actor.scope().tenant(), actor.unit(), actor.scope().environment(), actor.version(),
            new UiLayoutCompositionRegistration(root, List.of(root))));
  }
  private Actor current(Principal principal, String version) {
    var actor = actors.get(principal.getName());
    if (actor == null || !actor.version().equals(version)) throw stale();
    return actor;
  }
  private Actor current(UiLayoutLifecycleInvocation invocation) {
    var actor = actors.get(invocation.actorRef());
    if (actor == null || !context(actor, invocation.rootTarget()).invocation().equals(invocation)) throw stale();
    return actor;
  }
  private <T> void register(Class<T> type) {
    application.registerBean(type, () -> PageRuntimePostgresFixture.repo(type));
  }
  private static UiLayoutBaselineMetadataSeed metadata(UiLayoutLifecycleInvocation invocation, JsonNode document) {
    return UiLayoutMetadataTestFixtures.nativeMetadata(document, invocation.actorRef(), invocation.administrativeUnit(), invocation.contextVersion());
  }
  static ObjectNode groupsDocument(UUID... ids) {
    var document = PageRuntimePostgresFixture.JSON.createObjectNode().put("kind", "praxis.navigation-groups.editor").put("version", 1);
    var values = document.putObject("config").putArray("groups");
    for (int index = 0; index < ids.length; index++) values.addObject().put("id", ids[index].toString())
        .put("label", "Group " + index).put("icon", "home").put("order", index);
    return document;
  }
  private static UiLayoutLifecycleException denied() { return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Fixture registration denied"); }
  private static UiLayoutLifecycleException stale() { return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Fixture context stale"); }
  @Override public void close() { application.close(); }
}
