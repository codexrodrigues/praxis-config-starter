package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Published-only read owner. A host explicitly delegates its navigation provider here.
 * No baseline fallback, personal overlay, cached head/grant or publication writer lives here.
 */
public final class PageRuntimeReadService {
  private final PageCreationService pages;
  private final JdbcUiLayoutMetadataCaptureStore rootMetadata;
  private final PageCreationHost host;
  private final EffectiveUiAudienceProvider audiences;
  private final UiLayoutLifecycleAdmission admission;
  private final UiLayoutValidationBudgetPolicy budgets;
  private final TransactionOperations transactions;
  private final PageRuntimeStore store;
  private final UiLayoutNativeSnapshotReader nativeSnapshots;
  private final CanonicalJsonHashService hashes;
  private final Clock clock;
  private final UiLayoutReleaseRepository releases;
  private final UiLayoutDraftRepository drafts;
  private final UiLayoutReleaseMemberRepository members;
  private final UiLayoutRevisionRepository revisions;
  private final UiLayoutDefinitionRepository definitions;
  private final UiLayoutAssignmentRevisionRepository assignments;

  public PageRuntimeReadService(PageCreationService pages, PageCreationHost host, EffectiveUiAudienceProvider audiences,
      UiLayoutLifecycleAdmission admission, UiLayoutLifecycleStructureValidator structure,
      UiLayoutDraftWorkspaceSource source, UiLayoutBaselineMetadataAdmission metadataAdmission,
      NamedParameterJdbcTemplate jdbc, TransactionOperations transactions,
      UiLayoutValidationBudgetPolicy budgets, UiLayoutReleaseRepository releases,
      UiLayoutDraftRepository drafts, UiLayoutReleaseMemberRepository members,
      UiLayoutRevisionRepository revisions, UiLayoutDefinitionRepository definitions,
      UiLayoutAssignmentRevisionRepository assignments, ObjectMapper mapper,
      CanonicalJsonHashService hashes, Clock clock) {
    this.pages = Objects.requireNonNull(pages);
    this.host = Objects.requireNonNull(host);
    this.audiences = Objects.requireNonNull(audiences);
    this.admission = Objects.requireNonNull(admission);
    this.transactions = Objects.requireNonNull(transactions);
    this.budgets = Objects.requireNonNull(budgets);
    this.hashes = Objects.requireNonNull(hashes);
    this.clock = Objects.requireNonNull(clock);
    this.releases = releases;
    this.drafts = drafts;
    this.members = members;
    this.revisions = revisions;
    this.definitions = definitions;
    this.assignments = assignments;
    this.store = new PageRuntimeStore(jdbc, transactions);
    this.rootMetadata = new JdbcUiLayoutMetadataCaptureStore(jdbc, mapper, hashes, transactions);
    this.nativeSnapshots = new UiLayoutNativeSnapshotReader(structure,
        rootMetadata, metadataAdmission,
        source, releases, drafts, members, revisions, definitions, assignments, mapper, hashes);
  }

  public PageCatalogResponse catalog(Principal principal, String expectedContextVersion,
      String cursor, int limit) {
    requireSize(limit);
    var continuation = PageRuntimeCursor.decode(cursor);
    return read(principal, expectedContextVersion, null, false, current -> {
      String binding = current.binding("catalog", "", limit);
      var projection = new Projection<PageCatalogEntry>(binding, continuation, limit);
      current.pages((head, snapshot) -> {
        var presentation = document(snapshot).path("config").path("presentation");
        var entry = new PageCatalogEntry(head.pageId(), head.root(), current.route(head.pageId()),
            presentation.path("title").textValue(), presentation.path("icon").textValue(), head.receipt());
        projection.offer(new PageRuntimeCursor.Key(0, head.pageId().toString(), ""), entry, head,
            Map.of("pageId", head.pageId().toString(), "route", entry.route(), "title", entry.title(),
                "icon", entry.icon(), "head", head.stable()));
      });
      var result = projection.finish();
      return new PageCatalogResponse(PageCatalogResponse.SCHEMA_VERSION, result.values(),
          current.context.invocation().contextVersion(), result.receipt(), result.next(), Instant.now(clock));
    });
  }

  public PublishedPageResponse published(Principal principal, String expectedContextVersion, UUID pageId) {
    if (pageId == null) throw new IllegalArgumentException("Page identity is required.");
    return read(principal, expectedContextVersion, pageId, false, current -> {
      if (current.vector.size() != 1 || current.vector.getFirst().release() == null) throw notFound();
      var head = current.vector.getFirst();
      var snapshot = current.visible(head, null);
      if (snapshot == null) throw notFound();
      JsonNode document = document(snapshot).deepCopy();
      var copySource = new PageSourceReference.Published(pageId, snapshot.releaseId(),
          snapshot.document().targets().getFirst().selectedRevision().revisionRef());
      String receipt = hashes.sha256Exact(Map.of("schema", PublishedPageResponse.SCHEMA_VERSION,
          "binding", current.binding("published", pageId.toString(), 1),
          "head", head.stable(), "document", document, "copySource", copySource));
      return new PublishedPageResponse(PublishedPageResponse.SCHEMA_VERSION, pageId, current.route(pageId),
          document, current.context.invocation().contextVersion(), receipt, head.receipt(), copySource, Instant.now(clock));
    });
  }

  public EnterpriseRuntimeNavigationResponse navigation(EnterpriseRuntimeContextRequest request,
      String expectedContextVersion, String parentRef, String cursor, int pageSize) {
    requireSize(pageSize);
    UUID parent = parentRef == null ? null : strictUuid(parentRef);
    var continuation = PageRuntimeCursor.decode(cursor);
    return read(request == null ? null : request.principal(), expectedContextVersion, null, true, current -> {
      String binding = current.binding("navigation", parent == null ? "" : parent.toString(), pageSize);
      var projection = new Projection<EnterpriseRuntimeNavigationNode>(binding, continuation, pageSize);
      var groupsHead = current.vector.stream().filter(head -> head.pageId() == null).findFirst().orElseThrow();
      var snapshot = groupsHead.release() == null ? null : current.visible(groupsHead, null);
      if (snapshot == null) {
        if (parent != null) throw notFound();
        var empty = projection.finish();
        return navigationResponse(current, empty, List.of());
      }
      projection.include(Map.of("head", groupsHead.stable()));
      JsonNode groups = document(snapshot).path("config").path("groups");
      if (parent == null) {
        var ordered = new ArrayList<JsonNode>();
        groups.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt((JsonNode group) -> group.path("order").intValue())
            .thenComparing(group -> group.path("id").textValue()));
        for (var group : ordered) {
          String groupId = group.path("id").textValue();
          var node = new EnterpriseRuntimeNavigationNode(groupId, group.path("label").textValue(),
              group.path("icon").textValue(), "group", null, null, null, null, null, null, null, null);
          projection.offer(new PageRuntimeCursor.Key(group.path("order").intValue(), groupId, ""),
              node, groupsHead, Map.of("group", group, "head", groupsHead.stable()));
        }
      } else {
        JsonNode selected = null;
        for (var group : groups) if (parent.toString().equals(group.path("id").textValue())) selected = group;
        if (selected == null) throw notFound();
        projection.include(Map.of("group", selected, "head", groupsHead.stable()));
        current.pages((head, pageSnapshot) -> {
          var ordered = new ArrayList<JsonNode>();
          for (var entry : document(pageSnapshot).path("config").path("navigation")) {
            if (parent.toString().equals(entry.path("groupId").textValue())) ordered.add(entry);
          }
          ordered.sort(Comparator.comparingInt((JsonNode entry) -> entry.path("order").intValue())
              .thenComparing(entry -> entry.path("id").textValue()));
          for (var entry : ordered) {
            String entryId = entry.path("id").textValue();
            String nodeId = head.pageId() + ":" + entryId;
            var node = new EnterpriseRuntimeNavigationNode(nodeId, entry.path("label").textValue(),
                entry.path("icon").textValue(), "page", null, current.route(head.pageId()),
                null, null, null, null, null, List.of());
            projection.offer(new PageRuntimeCursor.Key(entry.path("order").intValue(), entryId, head.pageId().toString()),
                node, head, Map.of("node", node, "order", entry.path("order").intValue(), "head", head.stable()));
          }
        });
      }
      var result = projection.finish();
      var heads = new LinkedHashMap<UiLayoutTarget, UiLayoutReleaseHeadReceipt>();
      heads.put(groupsHead.root(), groupsHead.receipt());
      for (var head : result.heads()) heads.put(head.root(), head.receipt());
      return navigationResponse(current, result, List.copyOf(heads.values()));
    });
  }

  private EnterpriseRuntimeNavigationResponse navigationResponse(Read current,
      ProjectionResult<EnterpriseRuntimeNavigationNode> result, List<UiLayoutReleaseHeadReceipt> heads) {
    return new EnterpriseRuntimeNavigationResponse(EnterpriseRuntimeNavigationResponse.SCHEMA_VERSION,
        result.values(), List.of(), Instant.now(clock), current.context.invocation().contextVersion(),
        result.receipt(), heads, result.next());
  }

  private <T> T read(Principal principal, String expectedVersion, UUID page, boolean navigation,
      Function<Read, T> work) {
    if (principal == null) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SESSION_REQUIRED,
        "Authenticated principal is required.");
    if (expectedVersion == null || expectedVersion.isBlank()) throw new IllegalArgumentException("Context is required.");
    PageCreationContext context = resolve(principal, expectedVersion);
    var validation = UiLayoutValidationContext.begin(budgets, context.invocation(),
        UiLayoutLifecycleOperation.READ_HEAD, UiLayoutValidationPurpose.FROZEN_RELEASE);
    var retained = new AtomicBoolean();
    try {
      T result = transactions.execute(status -> {
        try {
          store.requireTransaction();
          boolean participating = !status.isNewTransaction();
          TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { validation.require(context.invocation()); }
            @Override public void afterCompletion(int completionStatus) {
              if (participating) validation.attempt().close();
            }
          });
          retained.set(participating);
          var current = new Read(principal, expectedVersion, context, validation, page, navigation);
          T value = work.apply(current);
          current.finishSnapshot();
          if (participating) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { current.finishAuthority(); }
          });
          store.requireTransaction();
          if (status.isRollbackOnly()) throw unavailable();
          return value;
        } catch (RuntimeException | Error failure) {
          status.setRollbackOnly();
          throw failure;
        }
      });
      validation.require(context.invocation());
      return result;
    } catch (UiLayoutResolutionException known) {
      throw known;
    } catch (UiLayoutLifecycleException known) {
      if (known.getCode() == UiLayoutLifecycleException.Code.SESSION_REQUIRED
          || known.getCode() == UiLayoutLifecycleException.Code.CONTEXT_STALE
          || known.getCode() == UiLayoutLifecycleException.Code.NOT_FOUND) throw known;
      throw unavailable();
    } catch (RuntimeException failure) {
      throw unavailable();
    } finally {
      if (!retained.get()) validation.attempt().close();
    }
  }

  private PageCreationContext resolve(Principal principal, String version) {
    try {
      var context = host.resolve(principal, version);
      if (context == null || !version.equals(context.invocation().contextVersion())) throw contextStale();
      return context;
    } catch (UiLayoutLifecycleException | UiLayoutResolutionException known) {
      throw known;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private final class Read {
    final Principal principal;
    final String version;
    final PageCreationContext context;
    final UiLayoutValidationContext validation;
    final EffectiveUiAudience audience;
    final UUID page;
    final UiLayoutTarget groupsRoot;
    final List<PageRuntimeStore.Head> vector;
    final List<Visibility> visibility = new ArrayList<>();

    Read(Principal principal, String version, PageCreationContext context,
        UiLayoutValidationContext validation, UUID page, boolean navigation) {
      this.principal = principal;
      this.version = version;
      this.context = context;
      this.validation = validation;
      this.page = page;
      requireContext();
      this.audience = call(() -> audiences.resolve(principal, version));
      requireAudience(audience);
      this.groupsRoot = navigation ? call(() -> host.navigationGroupsRoot(context)) : null;
      if (navigation) {
        if (groupsRoot == null || !UiLayoutNavigationGroupsRevisionReproduction.TYPE.equals(groupsRoot.componentType())) throw unavailable();
        try {
          strictUuid(groupsRoot.componentId());
        } catch (UiLayoutResolutionException invalidHostIdentity) {
          throw unavailable();
        }
        requireTarget(groupsRoot);
      }
      this.vector = call(() -> store.vector(context, page, groupsRoot));
    }

    String route(UUID pageId) { return context.routeNamespace() + "/" + pageId; }

    String binding(String surface, String parent, int limit) {
      var invocation = context.invocation();
      String schema = switch (surface) {
        case "catalog" -> PageCatalogResponse.SCHEMA_VERSION;
        case "navigation" -> EnterpriseRuntimeNavigationResponse.SCHEMA_VERSION;
        default -> PublishedPageResponse.SCHEMA_VERSION;
      };
      return hashes.sha256Exact(Map.of("schema", schema, "actor", invocation.actorRef(),
          "application", context.applicationId(), "tenant", invocation.tenantId(),
          "environment", invocation.environment(), "namespace", context.routeNamespace(),
          "context", version, "parent", parent, "size", limit));
    }

    <T> T call(java.util.function.Supplier<T> callback) {
      return validation.call(context.invocation(), callback);
    }

    void requireContext() {
      if (!context.equals(call(() -> resolve(principal, version)))) throw contextStale();
    }

    void requireAudience(EffectiveUiAudience current) {
      var invocation = context.invocation();
      if (current == null || !invocation.actorRef().equals(current.user())
          || !invocation.tenantId().equals(current.tenant()) || !invocation.environment().equals(current.environment())
          || !version.equals(current.contextVersion())) throw contextStale();
    }

    void requireTarget(UiLayoutTarget target) {
      var invocation = context.forTarget(target);
      var expected = new PageCreationContext(context.applicationId(), context.routeNamespace(), invocation);
      if (!expected.equals(call(() -> host.resolveLifecycle(invocation)))) throw contextStale();
    }

    boolean granted(UiLayoutTarget target) {
      requireTarget(target);
      return currentGrant(target);
    }

    boolean currentGrant(UiLayoutTarget target) {
      try {
        call(() -> { admission.require(UiLayoutLifecycleOperation.READ_HEAD, context.forTarget(target)); return true; });
        return true;
      } catch (UiLayoutLifecycleException denied) {
        if (denied.getCode() == UiLayoutLifecycleException.Code.DENIED
            || denied.getCode() == UiLayoutLifecycleException.Code.NOT_FOUND) return false;
        throw denied;
      }
    }

    UiLayoutFrozenWorkspaceResolver.Snapshot visible(PageRuntimeStore.Head head, UiLayoutFrozenRecordBatch batch) {
      boolean grant = granted(head.root());
      if (!grant) {
        visibility.add(new Visibility(head.root(), false, null));
        return null;
      }
      var invocation = context.forTarget(head.root());
      var snapshot = nativeSnapshots.read(invocation, head.release(), batch, validation);
      var source = snapshot.document().targets().getFirst().selectedAssignment().selector();
      var selector = new UiLayoutAudienceSelector(source.tenant(), source.organization(), source.sector(),
          source.group(), source.profile(), source.user());
      visibility.add(new Visibility(head.root(), true, selector));
      if (!selector.matches(audience)) return null;
      nativeSnapshots.admit(invocation, snapshot, validation);
      return snapshot;
    }

    void pages(java.util.function.BiConsumer<PageRuntimeStore.Head, UiLayoutFrozenWorkspaceResolver.Snapshot> consumer) {
      var active = vector.stream().filter(head -> head.pageId() != null && head.release() != null).toList();
      int offset = 0;
      while (offset < active.size()) {
        validation.require(context.invocation());
        var heads = store.batch(active, offset);
        var prior = store.managedDefinitions();
        var batch = UiLayoutFrozenRecordBatch.load(heads.stream().map(PageRuntimeStore.Head::release).toList(),
            releases, drafts, members, revisions, definitions, assignments);
        try {
          int position = 0;
          int ledgerEnd = 0;
          int metadataEnd = 0;
          while (position < heads.size()) {
            validation.require(context.invocation());
            var remaining = heads.subList(position, heads.size());
            if (position == ledgerEnd) {
              ledgerEnd = position + pages.prefetchRuntimeEvidence(context,
                  remaining.stream().map(PageRuntimeStore.Head::pageId).toList());
            }
            if (position == metadataEnd) {
              var keys = remaining.stream().map(head -> {
                var release = batch.releases().get(head.release());
                return new JdbcUiLayoutMetadataCaptureStore.RowKey(
                    release == null ? null : release.getSourceDraftId(), context.invocation().tenantId(),
                    context.invocation().environment(), head.root(), head.root());
              }).toList();
              metadataEnd = position + rootMetadata.prefetch(keys);
            }
            int end = Math.min(ledgerEnd, metadataEnd);
            // Each owner's retained prefix is fully consumed before that owner is refilled.
            while (position < end) {
              var head = heads.get(position++);
              var snapshot = visible(head, batch);
              if (snapshot != null) consumer.accept(head, snapshot);
            }
          }
        } finally { store.detach(batch, prior); }
        offset += heads.size();
      }
    }

    void finishAuthority() {
      requireContext();
      var currentAudience = call(() -> audiences.resolve(principal, version));
      requireAudience(currentAudience);
      if (!audience.equals(currentAudience)) throw snapshotChanged();
      // Complete all mapping/target callbacks before the final grant fence. A mapping callback
      // may revoke a grant without changing its returned root or context version.
      if (groupsRoot != null) {
        if (!groupsRoot.equals(call(() -> host.navigationGroupsRoot(context)))) throw contextStale();
        requireTarget(groupsRoot);
      }
      for (var decision : visibility) requireTarget(decision.root());
      int position = 0;
      while (position < visibility.size()) {
        var window = visibility.subList(position, Math.min(visibility.size(), position + 128));
        // All mapping callbacks above precede all current grants below. Prefetch has no policy callback.
        var pageIds = window.stream().filter(decision ->
            UiLayoutPageRevisionReproduction.TYPE.equals(decision.root().componentType()))
            .map(decision -> UUID.fromString(decision.root().componentId())).toList();
        int pageCount = pageIds.isEmpty() ? 0 : pages.prefetchRuntimeEvidence(context, pageIds);
        int consumedPages = 0;
        for (var decision : window) {
          boolean pageRoot = UiLayoutPageRevisionReproduction.TYPE.equals(decision.root().componentType());
          if (pageRoot && consumedPages == pageCount) break;
          if (pageRoot) consumedPages++;
          if (decision.granted() != currentGrant(decision.root())) throw snapshotChanged();
          if (decision.selector() != null
              && decision.selector().matches(audience) != decision.selector().matches(currentAudience)) throw snapshotChanged();
          position++;
        }
      }
      // The last extension may change identity/audience. These are current attestations, not cached grants.
      requireContext();
      var finalAudience = call(() -> audiences.resolve(principal, version));
      requireAudience(finalAudience);
      if (!audience.equals(finalAudience)) throw snapshotChanged();
      validation.require(context.invocation());
    }

    void finishSnapshot() {
      finishAuthority();
      if (!vector.equals(call(() -> store.vector(context, page, groupsRoot)))) throw snapshotChanged();
      validation.require(context.invocation());
    }
  }

  private record Visibility(UiLayoutTarget root, boolean granted, UiLayoutAudienceSelector selector) {}

  /** Complete authorized view digest with at most K+1 retained response payloads. */
  private final class Projection<T> {
    final String binding;
    final PageRuntimeCursor cursor;
    final int limit;
    final MessageDigest digest;
    final PriorityQueue<Item<T>> selected;
    boolean foundLast;

    Projection(String binding, PageRuntimeCursor cursor, int limit) {
      this.binding = binding;
      this.cursor = cursor;
      this.limit = limit;
      if (cursor != null) cursor.requireBinding(binding);
      try { this.digest = MessageDigest.getInstance("SHA-256"); }
      catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
      this.selected = new PriorityQueue<>(Comparator.comparing((Item<T> item) -> item.key()).reversed());
    }

    void include(Object stable) {
      digest.update(hashes.sha256Exact(stable).getBytes(StandardCharsets.US_ASCII));
    }

    void offer(PageRuntimeCursor.Key key, T value, PageRuntimeStore.Head head, Object stable) {
      include(stable);
      if (cursor != null && key.equals(cursor.last())) foundLast = true;
      if (cursor != null && key.compareTo(cursor.last()) <= 0) return;
      selected.add(new Item<>(key, value, head));
      if (selected.size() > limit + 1) selected.remove();
    }

    ProjectionResult<T> finish() {
      String view = HexFormat.of().formatHex(digest.digest());
      if (cursor != null) cursor.requireView(view, foundLast);
      var values = selected.stream().sorted(Comparator.comparing(Item::key)).toList();
      boolean more = values.size() > limit;
      var page = more ? values.subList(0, limit) : values;
      String next = more ? new PageRuntimeCursor(binding, view, page.getLast().key()).encode() : null;
      String receipt = hashes.sha256Exact(Map.of("binding", binding, "view", view,
          "position", cursor == null ? List.of() : List.of(cursor.last().order(), cursor.last().id(), cursor.last().page())));
      return new ProjectionResult<>(page.stream().map(Item::value).toList(),
          page.stream().map(Item::head).toList(), receipt, next);
    }
  }

  private record Item<T>(PageRuntimeCursor.Key key, T value, PageRuntimeStore.Head head) {}
  private record ProjectionResult<T>(List<T> values, List<PageRuntimeStore.Head> heads, String receipt, String next) {}

  private static JsonNode document(UiLayoutFrozenWorkspaceResolver.Snapshot snapshot) {
    return snapshot.document().targets().getFirst().working().document();
  }
  private static UUID strictUuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException failure) { throw invalidInput(); }
  }
  private static void requireSize(int size) {
    if (size < 1 || size > 100) throw invalidInput();
  }
  private static UiLayoutResolutionException invalidInput() {
    return new UiLayoutResolutionException(UiLayoutResolutionException.Code.INVALID_PUBLICATION_CURSOR,
        "Invalid publication request.", null);
  }
  private static UiLayoutLifecycleException notFound() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.NOT_FOUND, "Publication is unavailable.");
  }
  private static UiLayoutLifecycleException unavailable() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Publication source is unavailable.");
  }
  private static UiLayoutLifecycleException contextStale() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Publication context changed.");
  }
  private static UiLayoutResolutionException snapshotChanged() {
    return new UiLayoutResolutionException(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED,
        "Publication changed during resolution.", null);
  }
}
