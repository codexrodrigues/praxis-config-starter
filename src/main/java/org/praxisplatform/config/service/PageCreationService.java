package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.praxisplatform.config.dto.PageCreationCommand;
import org.praxisplatform.config.dto.PageCreationReceipt;
import org.praxisplatform.config.repository.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Canonical page creation/catalog boundary. Existing lifecycle remains the only publication writer.
 */
public final class PageCreationService {
  private final PageCreationCodec codec;
  private final CanonicalJsonHashService hashes;
  private final PageCatalogStore store;
  private final PageCatalogLifecycleBridge bridge;
  private final TransactionOperations transactions;
  private final UiLayoutValidationBudgetPolicy budgets;
  private final Supplier<UiLayoutLifecycleCommandService> commands;
  private final Supplier<UiLayoutLifecycleReadService> reads;

  public PageCreationService(
      PageCreationHost host,
      NamedParameterJdbcTemplate jdbc,
      TransactionOperations transactions,
      CanonicalJsonHashService hashes,
      ObjectMapper mapper,
      UiLayoutDraftWorkspaceSource source,
      UiLayoutBaselineMetadataAdmission metadata,
      UiLayoutLifecycleStructureValidator structure,
      UiLayoutValidationBudgetPolicy budgets,
      UiLayoutDraftRepository drafts,
      UiLayoutReleaseRepository releases,
      UiLayoutReleaseMemberRepository members,
      UiLayoutDefinitionRepository definitions,
      UiLayoutRevisionRepository revisions,
      UiLayoutAssignmentRevisionRepository assignments,
      Supplier<UiLayoutLifecycleCommandService> commands,
      Supplier<UiLayoutLifecycleReadService> reads) {
    this.budgets = Objects.requireNonNull(budgets);
    this.transactions = Objects.requireNonNull(transactions);
    this.hashes = hashes;
    this.codec = new PageCreationCodec();
    this.store = new PageCatalogStore(jdbc, transactions, codec, hashes);
    this.commands = Objects.requireNonNull(commands);
    this.reads = Objects.requireNonNull(reads);
    this.bridge =
        new PageCatalogLifecycleBridge(
            Objects.requireNonNull(host),
            store,
            codec,
            hashes,
            Objects.requireNonNull(source),
            Objects.requireNonNull(metadata),
            Objects.requireNonNull(structure),
            new UiLayoutFrozenWorkspaceResolver(
                releases, drafts, members, definitions, revisions, assignments, mapper, hashes),
            new JdbcUiLayoutMetadataCaptureStore(jdbc, mapper, hashes, transactions),
            mapper,
            budgets);
  }

  /**
   * Wiring adapters return existing seams; delegates must be the host beans, never these adapters.
   */
  public UiLayoutLifecycleInvocationProvider invocationProvider(
      UiLayoutLifecycleInvocationProvider delegate) {
    return bridge.invocations(Objects.requireNonNull(delegate));
  }

  public UiLayoutLifecycleAdmission lifecycleAdmission(UiLayoutLifecycleAdmission delegate) {
    return bridge.admission(Objects.requireNonNull(delegate));
  }

  public UiLayoutDraftWorkspaceSource workspaceSource() {
    return bridge.workspaceSource();
  }

  public UiLayoutBaselineMetadataAdmission metadataAdmission() {
    return bridge.metadataAdmission();
  }

  public PageCreationCommand decode(String raw) {
    return codec.command(raw);
  }

  public CreationOutcome create(
      Principal principal, String contextVersion, PageCreationCommand command) {
    codec.require(command);
    PageCreationContext currentContext = bridge.resolve(principal, contextVersion);
    UiLayoutValidationContext validation =
        begin(currentContext, UiLayoutLifecycleOperation.CREATE_DRAFT);
    try (var attempt = validation.attempt()) {
      CreationOutcome outcome =
          transaction(
              () -> {
                revalidate(principal, contextVersion, currentContext, validation);
                authorizeCreate(currentContext, command, validation);
                validation.run(
                    currentContext.invocation(),
                    () -> store.lock(currentContext, command.creationKey()));
                revalidate(principal, contextVersion, currentContext, validation);
                authorizeCreate(currentContext, command, validation);
                var replay =
                    validation.call(
                        currentContext.invocation(),
                        () -> store.byKey(currentContext, command.creationKey()));
                if (replay.isPresent()) {
                  PageCreationEvidence originalCreation = replay.orElseThrow();
                  if (!hashes
                      .sha256Exact(codec.tree(command))
                      .equals(hashes.sha256Exact(codec.tree(originalCreation.command())))) {
                    throw new UiLayoutLifecycleException(
                        UiLayoutLifecycleException.Code.INVALID_STATE,
                        "Creation key belongs to a different command.");
                  }
                  bridge.verify(currentContext, originalCreation, attempt);
                  commitCheck(
                      principal,
                      contextVersion,
                      currentContext,
                      command,
                      originalCreation,
                      validation);
                  return new CreationOutcome(
                      receipt(
                          principal, contextVersion, currentContext, originalCreation, validation),
                      false);
                }
                var originalSource = bridge.capture(currentContext, command.source(), attempt);
                UUID pageId = UUID.randomUUID();
                UUID draftId = UUID.randomUUID();
                var transformed =
                    new PageCopyTransformation(hashes)
                        .allocate(
                            originalSource.document(),
                            UUID.fromString(originalSource.baseline().target().componentId()),
                            pageId,
                            command.title());
                PageCreationEvidence creation =
                    new PageCreationEvidence(
                        pageId,
                        draftId,
                        currentContext,
                        command,
                        originalSource,
                        transformed.sourceHash(),
                        transformed.outputHash(),
                        PageCopyTransformation.TRANSFORM,
                        transformed.identityMap(),
                        Instant.now());
                bridge.verify(currentContext, creation, attempt);
                validation.run(currentContext.invocation(), () -> store.insert(creation));
                validation.run(
                    currentContext.invocation(),
                    () ->
                        commands
                            .get()
                            .createReservedPageDraft(
                                principal,
                                contextVersion,
                                creation.target(),
                                command.creationKey().toString(),
                                draftId));
                commitCheck(
                    principal, contextVersion, currentContext, command, creation, validation);
                return new CreationOutcome(
                    receipt(principal, contextVersion, currentContext, creation, validation), true);
              });
      validation.require(currentContext.invocation());
      return outcome;
    }
  }

  public PageCreationReceipt byCreationKey(Principal principal, String contextVersion, UUID key) {
    if (key == null) {
      throw new UiLayoutLifecycleException(
          UiLayoutLifecycleException.Code.INVALID_REQUEST, "Creation key is required.");
    }
    PageCreationContext currentContext = bridge.resolve(principal, contextVersion);
    UiLayoutValidationContext validation =
        begin(currentContext, UiLayoutLifecycleOperation.READ_DRAFT);
    try (var attempt = validation.attempt()) {
      PageCreationReceipt result =
          transaction(
              () -> {
                Runnable authorize =
                    () -> {
                      revalidate(principal, contextVersion, currentContext, validation);
                      validation.run(
                          currentContext.invocation(),
                          () ->
                              bridge.require(
                                  PageCreationOperation.READ_CREATION, currentContext, null));
                    };
                authorize.run();
                PageCreationEvidence creation =
                    validation
                        .call(currentContext.invocation(), () -> store.byKey(currentContext, key))
                        .orElseThrow(
                            () ->
                                new UiLayoutLifecycleException(
                                    UiLayoutLifecycleException.Code.NOT_FOUND,
                                    "Page creation was not observed."));
                PageCreationReceipt receipt =
                    receipt(principal, contextVersion, currentContext, creation, validation);
                authorize.run();
                beforeCommit(
                    () -> {
                      authorize.run();
                      receipt(principal, contextVersion, currentContext, creation, validation);
                      authorize.run();
                    });
                return receipt;
              });
      validation.require(currentContext.invocation());
      return result;
    }
  }

  private UiLayoutValidationContext begin(
      PageCreationContext context, UiLayoutLifecycleOperation operation) {
    return UiLayoutValidationContext.begin(
        budgets, context.invocation(), operation, UiLayoutValidationPurpose.AUTHORING);
  }

  private PageCreationReceipt receipt(
      Principal principal,
      String version,
      PageCreationContext context,
      PageCreationEvidence creation,
      UiLayoutValidationContext validation) {
    var currentDraft =
        validation.call(
            context.invocation(),
            () ->
                reads
                    .get()
                    .draftByCreationKey(
                        principal,
                        version,
                        creation.target(),
                        creation.command().creationKey().toString()));
    if (!creation.draftId().toString().equals(currentDraft.draftRef())) {
      throw new UiLayoutLifecycleException(
          UiLayoutLifecycleException.Code.INVALID_STATE,
          "Page draft identity differs from creation.");
    }
    revalidate(principal, version, context, validation);
    return new PageCreationReceipt(
        creation.command().creationKey(),
        creation.pageId(),
        creation.target(),
        creation.observation().routeNamespace() + "/" + creation.pageId(),
        creation.draftId(),
        "\"" + currentDraft.draftEtag() + "\"",
        creation.command().source());
  }

  private void authorizeCreate(
      PageCreationContext context,
      PageCreationCommand command,
      UiLayoutValidationContext validation) {
    validation.run(
        context.invocation(),
        () -> bridge.require(PageCreationOperation.CREATE_PAGE, context, null));
    validation.run(
        context.invocation(),
        () -> bridge.require(PageCreationOperation.READ_SOURCE, context, command.source()));
    validation.run(
        context.invocation(),
        () -> bridge.require(PageCreationOperation.COPY_SOURCE, context, command.source()));
  }

  private void commitCheck(
      Principal principal,
      String version,
      PageCreationContext context,
      PageCreationCommand command,
      PageCreationEvidence creation,
      UiLayoutValidationContext validation) {
    Runnable check =
        () -> {
          revalidate(principal, version, context, validation);
          authorizeCreate(context, command, validation);
          bridge.verify(context, creation, validation.attempt());
          authorizeCreate(context, command, validation);
          revalidate(principal, version, context, validation);
        };
    check.run();
    beforeCommit(check);
  }

  private void beforeCommit(Runnable check) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void beforeCommit(boolean readOnly) {
            store.requireTransaction();
            check.run();
            store.requireTransaction();
          }
        });
  }

  private void revalidate(
      Principal principal,
      String version,
      PageCreationContext expected,
      UiLayoutValidationContext validation) {
    PageCreationContext current =
        validation.call(expected.invocation(), () -> bridge.resolve(principal, version));
    if (!expected.equals(current)) {
      throw new UiLayoutLifecycleException(
          UiLayoutLifecycleException.Code.CONTEXT_STALE,
          "Page context changed during the operation.");
    }
  }

  private <T> T transaction(Supplier<T> work) {
    try {
      return transactions.execute(
          status -> {
            try {
              store.requireTransaction();
              T result = work.get();
              store.requireTransaction();
              if (status.isRollbackOnly()) {
                throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
                    "Page transaction is rollback-only.");
              }
              return result;
            } catch (RuntimeException | Error failure) {
              status.setRollbackOnly();
              throw failure;
            }
          });
    } catch (UiLayoutLifecycleException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new UiLayoutLifecycleException(
          UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "Page creation could not be completed.");
    }
  }

  public record CreationOutcome(PageCreationReceipt receipt, boolean created) {}
}
