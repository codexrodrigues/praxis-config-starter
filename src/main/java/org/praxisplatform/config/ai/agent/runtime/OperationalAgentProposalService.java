package org.praxisplatform.config.ai.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.agent.spi.AgentActionExecutionOutcome;
import org.praxisplatform.config.ai.agent.spi.AgentExecuteActionRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContextCustomizer;
import org.praxisplatform.config.ai.agent.spi.AgentPersistedActionProposal;
import org.praxisplatform.config.ai.agent.spi.AgentPrepareActionRequest;
import org.praxisplatform.config.ai.agent.spi.AgentPreparedAction;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.domain.AiAgentActionProposal;
import org.praxisplatform.config.domain.AiAgentActionProposalStatus;
import org.praxisplatform.config.repository.AiAgentActionProposalRepository;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gerencia persistencia, ciclo de vida e confirmacao governada de {@link AiAgentActionProposal}
 * com isolamento estrito de transacoes entre o banco Config e as execucoes em processo do dominio.
 */
@Slf4j
public class OperationalAgentProposalService {

    public record ConfirmHttpResult(int httpStatus, AgentProposalConfirmResponse response) {
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final long DEFAULT_TTL_SECONDS = 900L;
    private static final long DEFAULT_RETENTION_SECONDS = 86_400L;
    private static final long CLEANUP_INTERVAL_SECONDS = 900L;

    private final AiAgentActionProposalRepository proposalRepository;
    private final AgentResourceExecutionBridge executionBridge;
    private final List<AgentExecutionContextCustomizer> contextCustomizers;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate configWriteTx;
    private final TransactionTemplate configReadTx;
    private final long proposalTtlSeconds;
    private final long proposalRetentionSeconds;
    private final AtomicLong lastCleanupEpochSecond = new AtomicLong(0L);

    public OperationalAgentProposalService(
            AiAgentActionProposalRepository proposalRepository,
            AgentResourceExecutionBridge executionBridge,
            List<AgentExecutionContextCustomizer> contextCustomizers,
            PlatformTransactionManager configTransactionManager,
            ObjectMapper objectMapper,
            long proposalTtlSeconds) {
        this(
                proposalRepository,
                executionBridge,
                contextCustomizers,
                configTransactionManager,
                objectMapper,
                proposalTtlSeconds,
                DEFAULT_RETENTION_SECONDS);
    }

    public OperationalAgentProposalService(
            AiAgentActionProposalRepository proposalRepository,
            AgentResourceExecutionBridge executionBridge,
            List<AgentExecutionContextCustomizer> contextCustomizers,
            PlatformTransactionManager configTransactionManager,
            ObjectMapper objectMapper,
            long proposalTtlSeconds,
            long proposalRetentionSeconds) {
        this.proposalRepository = Objects.requireNonNull(proposalRepository, "proposalRepository is required");
        this.executionBridge = Objects.requireNonNull(executionBridge, "executionBridge is required");
        this.contextCustomizers = contextCustomizers == null ? List.of() : List.copyOf(contextCustomizers);
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.proposalTtlSeconds = proposalTtlSeconds > 0 ? proposalTtlSeconds : DEFAULT_TTL_SECONDS;
        this.proposalRetentionSeconds = proposalRetentionSeconds > 0
                ? proposalRetentionSeconds
                : DEFAULT_RETENTION_SECONDS;
        if (configTransactionManager != null) {
            TransactionTemplate writeTx = new TransactionTemplate(configTransactionManager);
            writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            writeTx.setReadOnly(false);
            this.configWriteTx = writeTx;

            TransactionTemplate readTx = new TransactionTemplate(configTransactionManager);
            readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            readTx.setReadOnly(true);
            this.configReadTx = readTx;
        } else {
            this.configWriteTx = null;
            this.configReadTx = null;
        }
    }

    public AgentPersistedActionProposal prepareAndPersist(
            AgentExecutionContext context,
            AgentPrepareActionRequest request) {
        if (context == null || !StringUtils.hasText(context.tenantId()) || !StringUtils.hasText(context.userId())) {
            throw new SecurityException("Contexto de identidade obrigatorio para preparar proposta.");
        }
        AgentPreparedAction prepared = executionBridge.prepareAction(context, request);
        Instant now = Instant.now();
        purgeExpiredProposalsIfDue(now);
        Instant expiresAt = now.plusSeconds(Math.max(60L, proposalTtlSeconds));
        AiAgentActionProposalStatus initialStatus = prepared.allowed()
                ? AiAgentActionProposalStatus.PENDING
                : AiAgentActionProposalStatus.BLOCKED;

        AiAgentActionProposal entity = AiAgentActionProposal.builder()
                .proposalId(UUID.randomUUID())
                .tenantId(context.tenantId())
                .userId(context.userId())
                .environment(StringUtils.hasText(context.environment()) ? context.environment() : "default")
                .threadId(context.threadId())
                .turnId(context.turnId())
                .entityKey(prepared.entityKey())
                .resourceKey(prepared.resourceKey())
                .resourcePath(prepared.resourcePath())
                .entityId(prepared.entityId())
                .actionId(prepared.actionId())
                .actionTitle(prepared.actionTitle())
                .actionDescription(truncate(prepared.actionDescription(), 1024))
                .operationKind(prepared.operationKind())
                .formMode(prepared.formMode())
                .httpMethod(prepared.httpMethod())
                .submitUrl(prepared.submitUrl())
                .schemaUrl(prepared.schemaUrl())
                .riskLevel(StringUtils.hasText(prepared.riskLevel()) ? prepared.riskLevel() : "MEDIUM")
                .confirmationRequired(prepared.confirmationRequired())
                .hasInputForm(prepared.hasInputForm())
                .organizationId(prepared.organizationId())
                .organizationLabel(prepared.organizationLabel())
                .recordTitle(truncate(OperationalAssistantBlockComposer.maskMod11TaxIds(prepared.recordTitle()), 255))
                .recordSubtitle(truncate(OperationalAssistantBlockComposer.maskMod11TaxIds(prepared.recordSubtitle()), 255))
                .recordLifecycleState(truncate(prepared.recordLifecycleState(), 120))
                .expectedVersionTag(truncate(prepared.expectedVersionTag(), 255))
                .status(initialStatus)
                .blockedCode(truncate(prepared.blockedCode(), 80))
                .impedimentNote(truncate(OperationalAssistantBlockComposer.maskMod11TaxIds(prepared.impedimentNote()), 1024))
                .prefillJson(writeJson(prepared.prefill()))
                .inputFieldsJson(writeJson(prepared.inputFields()))
                .missingFieldsJson(writeJson(prepared.missingRequiredFields()))
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();

        AiAgentActionProposal saved = inConfigWriteTx(() -> proposalRepository.save(entity));
        return new AgentPersistedActionProposal(
                saved.getProposalId(),
                saved.getStatus().name(),
                saved.getExpiresAt(),
                prepared);
    }

    public ConfirmHttpResult confirm(
            UUID proposalId,
            AiPrincipalContext principal,
            HttpServletRequest servletRequest,
            String ifMatchHeader,
            String idempotencyHeader,
            AgentProposalConfirmRequest body) {
        if (principal == null || !StringUtils.hasText(principal.tenantId()) || !StringUtils.hasText(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Principal context is required.");
        }
        String env = StringUtils.hasText(principal.environment()) ? principal.environment() : "default";
        AiAgentActionProposal proposal = loadOwnedProposal(proposalId, principal.tenantId(), principal.userId(), env);

        String effectiveIdempotencyKey = firstNonBlank(
                idempotencyHeader,
                body != null ? body.idempotencyKey() : null);

        ConfirmHttpResult nonPending = evaluatePreClaimStatus(proposalId, proposal, effectiveIdempotencyKey);
        if (nonPending != null) {
            return nonPending;
        }

        Instant now = Instant.now();
        if (proposal.getExpiresAt() != null && now.isAfter(proposal.getExpiresAt())) {
            markProposalExpired(proposalId, now);
            return expiredConflict(proposalId, proposal);
        }

        int claimed = inConfigWriteTx(() -> proposalRepository.transitionStatusIfCurrent(
                proposalId,
                principal.tenantId(),
                principal.userId(),
                env,
                AiAgentActionProposalStatus.PENDING,
                AiAgentActionProposalStatus.EXECUTING,
                now));
        if (claimed == 0) {
            AiAgentActionProposal latest = loadOwnedProposal(proposalId, principal.tenantId(), principal.userId(), env);
            ConfirmHttpResult concurrentNonPending = evaluatePreClaimStatus(proposalId, latest, effectiveIdempotencyKey);
            if (concurrentNonPending != null) {
                return concurrentNonPending;
            }
            if (latest.getExpiresAt() != null && now.isAfter(latest.getExpiresAt())) {
                markProposalExpired(proposalId, now);
                return expiredConflict(proposalId, latest);
            }
            return executionInProgressConflict(proposalId, latest);
        }

        Map<String, Object> mergedInputs = new LinkedHashMap<>(readMap(proposal.getPrefillJson()));
        if (body != null && !body.inputs().isEmpty()) {
            mergeNonMaskedInputs(mergedInputs, body.inputs());
        }
        String effectiveIfMatch = firstNonBlank(
                ifMatchHeader,
                body != null ? body.ifMatch() : null,
                proposal.getExpectedVersionTag());
        if (!StringUtils.hasText(effectiveIdempotencyKey)) {
            effectiveIdempotencyKey = "proposal-" + proposalId;
        }

        AgentExecutionContext executionContext = buildConfirmContext(principal, proposal, servletRequest);
        AgentActionExecutionOutcome outcome;
        try {
            outcome = executionBridge.executeConfirmedAction(
                    executionContext,
                    new AgentExecuteActionRequest(
                            firstNonBlank(proposal.getEntityKey(), proposal.getResourcePath()),
                            proposal.getEntityId(),
                            proposal.getActionId(),
                            effectiveIfMatch,
                            effectiveIdempotencyKey,
                            proposal.getOrganizationId(),
                            mergedInputs));
        } catch (RuntimeException ex) {
            revertExecutingToPending(proposalId);
            throw ex;
        }

        Instant resolvedAt = Instant.now();
        if (outcome.succeeded()) {
            AgentProposalConfirmResponse okResponse = new AgentProposalConfirmResponse(
                    proposalId,
                    AiAgentActionProposalStatus.CONFIRMED.name(),
                    true,
                    firstNonBlank(outcome.outcomeCode(), "CONFIRMED"),
                    firstNonBlank(outcome.statusNote(), "Ação executada com sucesso."),
                    outcome.updatedVersionTag(),
                    outcome.updatedLifecycleState(),
                    outcome.uiResult(),
                    List.of(),
                    Map.of(),
                    Map.of());
            AgentProposalConfirmResponse minimizedReceipt = new AgentProposalConfirmResponse(
                    okResponse.proposalId(),
                    okResponse.status(),
                    okResponse.succeeded(),
                    okResponse.outcomeCode(),
                    okResponse.statusNote(),
                    okResponse.updatedVersionTag(),
                    okResponse.updatedLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of());
            String finalIdempotency = effectiveIdempotencyKey;
            inConfigWriteTx(() -> {
                AiAgentActionProposal managed = proposalRepository.findById(proposalId).orElseThrow();
                managed.setStatus(AiAgentActionProposalStatus.CONFIRMED);
                managed.setIdempotencyKey(truncate(finalIdempotency, 160));
                managed.setResolvedAt(resolvedAt);
                if (StringUtils.hasText(outcome.updatedVersionTag())) {
                    managed.setExpectedVersionTag(truncate(outcome.updatedVersionTag(), 255));
                }
                if (StringUtils.hasText(outcome.updatedLifecycleState())) {
                    managed.setRecordLifecycleState(truncate(outcome.updatedLifecycleState(), 120));
                }
                managed.setExecutionReceiptJson(writeJson(minimizedReceipt));
                return proposalRepository.save(managed);
            });
            return new ConfirmHttpResult(200, okResponse);
        }

        if (outcome.httpStatus() == 412 || outcome.httpStatus() == 428) {
            AgentProposalConfirmResponse staleResponse = new AgentProposalConfirmResponse(
                    proposalId,
                    AiAgentActionProposalStatus.STALE.name(),
                    false,
                    "STALE_RESOURCE_VERSION",
                    firstNonBlank(outcome.statusNote(), "Conflito otimista: o registro foi alterado antes da confirmação."),
                    outcome.updatedVersionTag(),
                    outcome.updatedLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    outcome.refreshedSnapshot());
            Map<String, Object> minimizedSnapshot = minimizeStaleSnapshot(outcome);
            inConfigWriteTx(() -> {
                AiAgentActionProposal managed = proposalRepository.findById(proposalId).orElseThrow();
                managed.setStatus(AiAgentActionProposalStatus.STALE);
                managed.setResolvedAt(resolvedAt);
                managed.setExecutionReceiptJson(writeJson(minimizedSnapshot));
                return proposalRepository.save(managed);
            });
            return new ConfirmHttpResult(412, staleResponse);
        }

        int status = outcome.httpStatus() >= 400 ? outcome.httpStatus() : 422;
        boolean hardBlock = status == 403 || status == 409;
        AiAgentActionProposalStatus nextStatus = hardBlock
                ? AiAgentActionProposalStatus.BLOCKED
                : AiAgentActionProposalStatus.PENDING;
        String finalNote = firstNonBlank(outcome.statusNote(), "Não foi possível confirmar a execução.");
        String blockedCode = hardBlock ? firstNonBlank(outcome.outcomeCode(), "HTTP_" + status) : null;
        inConfigWriteTx(() -> {
            AiAgentActionProposal managed = proposalRepository.findById(proposalId).orElseThrow();
            managed.setStatus(nextStatus);
            if (hardBlock) {
                managed.setResolvedAt(resolvedAt);
                managed.setBlockedCode(truncate(blockedCode, 80));
                managed.setImpedimentNote(truncate(OperationalAssistantBlockComposer.maskMod11TaxIds(finalNote), 1024));
            }
            return proposalRepository.save(managed);
        });

        AgentProposalConfirmResponse failResponse = new AgentProposalConfirmResponse(
                proposalId,
                nextStatus.name(),
                false,
                firstNonBlank(outcome.outcomeCode(), "HTTP_" + status),
                finalNote,
                outcome.updatedVersionTag(),
                outcome.updatedLifecycleState(),
                outcome.uiResult(),
                outcome.ruleViolations(),
                outcome.fieldErrors(),
                outcome.refreshedSnapshot());
        return new ConfirmHttpResult(status, failResponse);
    }

    public ConfirmHttpResult completeViaExternalForm(
            UUID proposalId,
            AiPrincipalContext principal,
            AgentProposalExternalCompletionRequest request) {
        if (principal == null || !StringUtils.hasText(principal.tenantId()) || !StringUtils.hasText(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Principal context is required.");
        }
        String env = StringUtils.hasText(principal.environment()) ? principal.environment() : "default";
        AiAgentActionProposal proposal = loadOwnedProposal(proposalId, principal.tenantId(), principal.userId(), env);

        if (proposal.getStatus() == AiAgentActionProposalStatus.CONFIRMED) {
            return new ConfirmHttpResult(200, new AgentProposalConfirmResponse(
                    proposalId,
                    AiAgentActionProposalStatus.CONFIRMED.name(),
                    true,
                    "CONFIRMED_VIA_FORM",
                    "Operação já registrada como concluída pelo formulário governado.",
                    proposal.getExpectedVersionTag(),
                    proposal.getRecordLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of()));
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.EXECUTING) {
            return executionInProgressConflict(proposalId, proposal);
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.REJECTED) {
            return new ConfirmHttpResult(409, new AgentProposalConfirmResponse(
                    proposalId,
                    AiAgentActionProposalStatus.REJECTED.name(),
                    false,
                    "PROPOSAL_REJECTED",
                    "Proposta descartada pelo usuário.",
                    proposal.getExpectedVersionTag(),
                    proposal.getRecordLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of()));
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.BLOCKED) {
            return new ConfirmHttpResult(409, new AgentProposalConfirmResponse(
                    proposalId,
                    AiAgentActionProposalStatus.BLOCKED.name(),
                    false,
                    firstNonBlank(proposal.getBlockedCode(), "PROPOSAL_BLOCKED"),
                    firstNonBlank(proposal.getImpedimentNote(), "Proposta bloqueada para execução."),
                    proposal.getExpectedVersionTag(),
                    proposal.getRecordLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of()));
        }

        Instant resolvedAt = Instant.now();
        String updatedVersion = request != null ? firstNonBlank(request.updatedVersionTag(), proposal.getExpectedVersionTag()) : proposal.getExpectedVersionTag();
        String updatedState = request != null ? firstNonBlank(request.updatedLifecycleState(), proposal.getRecordLifecycleState()) : proposal.getRecordLifecycleState();
        String idemKey = request != null ? firstNonBlank(request.idempotencyKey(), "proposal-form-" + proposalId) : "proposal-form-" + proposalId;

        AgentProposalConfirmResponse okResponse = new AgentProposalConfirmResponse(
                proposalId,
                AiAgentActionProposalStatus.CONFIRMED.name(),
                true,
                "CONFIRMED_VIA_FORM",
                "Operação concluída pelo formulário governado.",
                updatedVersion,
                updatedState,
                Map.of(),
                List.of(),
                Map.of(),
                Map.of());

        inConfigWriteTx(() -> {
            AiAgentActionProposal managed = proposalRepository.findById(proposalId).orElseThrow();
            managed.setStatus(AiAgentActionProposalStatus.CONFIRMED);
            managed.setIdempotencyKey(truncate(idemKey, 160));
            managed.setResolvedAt(resolvedAt);
            if (StringUtils.hasText(updatedVersion)) {
                managed.setExpectedVersionTag(truncate(updatedVersion, 255));
            }
            if (StringUtils.hasText(updatedState)) {
                managed.setRecordLifecycleState(truncate(updatedState, 120));
            }
            managed.setExecutionReceiptJson(writeJson(okResponse));
            return proposalRepository.save(managed);
        });
        return new ConfirmHttpResult(200, okResponse);
    }

    public ConfirmHttpResult reject(UUID proposalId, AiPrincipalContext principal) {
        if (principal == null || !StringUtils.hasText(principal.tenantId()) || !StringUtils.hasText(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Principal context is required.");
        }
        String env = StringUtils.hasText(principal.environment()) ? principal.environment() : "default";
        AiAgentActionProposal proposal = loadOwnedProposal(proposalId, principal.tenantId(), principal.userId(), env);

        if (proposal.getStatus() == AiAgentActionProposalStatus.CONFIRMED) {
            return new ConfirmHttpResult(409, new AgentProposalConfirmResponse(
                    proposalId,
                    proposal.getStatus().name(),
                    false,
                    "ALREADY_CONFIRMED",
                    "Uma proposta já confirmada não pode ser descartada.",
                    proposal.getExpectedVersionTag(),
                    proposal.getRecordLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of()));
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.EXECUTING) {
            return executionInProgressConflict(proposalId, proposal);
        }

        Instant now = Instant.now();
        if (proposal.getStatus() == AiAgentActionProposalStatus.EXPIRED
                || (proposal.getExpiresAt() != null && now.isAfter(proposal.getExpiresAt()))) {
            if (proposal.getStatus() != AiAgentActionProposalStatus.EXPIRED) {
                markProposalExpired(proposalId, now);
            }
            return expiredConflict(proposalId, proposal);
        }

        if (proposal.getStatus() == AiAgentActionProposalStatus.PENDING
                || proposal.getStatus() == AiAgentActionProposalStatus.BLOCKED
                || proposal.getStatus() == AiAgentActionProposalStatus.STALE) {
            inConfigWriteTx(() -> {
                AiAgentActionProposal managed = proposalRepository.findById(proposalId).orElseThrow();
                managed.setStatus(AiAgentActionProposalStatus.REJECTED);
                managed.setResolvedAt(now);
                return proposalRepository.save(managed);
            });
        }
        return new ConfirmHttpResult(200, new AgentProposalConfirmResponse(
                proposalId,
                AiAgentActionProposalStatus.REJECTED.name(),
                true,
                "REJECTED",
                "Proposta descartada pelo usuário.",
                proposal.getExpectedVersionTag(),
                proposal.getRecordLifecycleState(),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of()));
    }

    private AiAgentActionProposal loadOwnedProposal(UUID proposalId, String tenantId, String userId, String env) {
        return inConfigReadTx(() -> proposalRepository
                .findByProposalIdAndTenantIdAndUserIdAndEnvironment(proposalId, tenantId, userId, env))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action proposal not found."));
    }

    private ConfirmHttpResult evaluatePreClaimStatus(
            UUID proposalId,
            AiAgentActionProposal proposal,
            String effectiveIdempotencyKey) {
        if (proposal.getStatus() == AiAgentActionProposalStatus.CONFIRMED) {
            if (StringUtils.hasText(effectiveIdempotencyKey)
                    && effectiveIdempotencyKey.equals(proposal.getIdempotencyKey())
                    && StringUtils.hasText(proposal.getExecutionReceiptJson())) {
                AgentProposalConfirmResponse replay = readJson(
                        proposal.getExecutionReceiptJson(), AgentProposalConfirmResponse.class);
                if (replay != null) {
                    return new ConfirmHttpResult(200, replay);
                }
            }
            return new ConfirmHttpResult(409, new AgentProposalConfirmResponse(
                    proposalId,
                    proposal.getStatus().name(),
                    false,
                    "ALREADY_CONFIRMED",
                    "Esta proposta já foi confirmada anteriormente.",
                    proposal.getExpectedVersionTag(),
                    proposal.getRecordLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of()));
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.EXECUTING) {
            return executionInProgressConflict(proposalId, proposal);
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.BLOCKED) {
            return new ConfirmHttpResult(409, new AgentProposalConfirmResponse(
                    proposalId,
                    proposal.getStatus().name(),
                    false,
                    firstNonBlank(proposal.getBlockedCode(), "PROPOSAL_BLOCKED"),
                    firstNonBlank(proposal.getImpedimentNote(), "Proposta bloqueada para execução."),
                    proposal.getExpectedVersionTag(),
                    proposal.getRecordLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of()));
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.REJECTED) {
            return new ConfirmHttpResult(409, new AgentProposalConfirmResponse(
                    proposalId,
                    proposal.getStatus().name(),
                    false,
                    "PROPOSAL_REJECTED",
                    "Proposta descartada pelo usuário.",
                    proposal.getExpectedVersionTag(),
                    proposal.getRecordLifecycleState(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of()));
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.STALE) {
            Map<String, Object> storedSnapshot = readMap(proposal.getExecutionReceiptJson());
            return new ConfirmHttpResult(412, new AgentProposalConfirmResponse(
                    proposalId,
                    AiAgentActionProposalStatus.STALE.name(),
                    false,
                    "STALE_RESOURCE_VERSION",
                    "A versão do registro mudou desde o preparo. Revalide a proposta com o estado atual.",
                    storedSnapshot.get("expectedVersionTag") instanceof String s ? s : null,
                    storedSnapshot.get("recordLifecycleState") instanceof String s ? s : null,
                    Map.of(),
                    List.of(),
                    Map.of(),
                    storedSnapshot));
        }
        if (proposal.getStatus() == AiAgentActionProposalStatus.EXPIRED) {
            return expiredConflict(proposalId, proposal);
        }
        return null;
    }

    private ConfirmHttpResult expiredConflict(UUID proposalId, AiAgentActionProposal proposal) {
        return new ConfirmHttpResult(409, new AgentProposalConfirmResponse(
                proposalId,
                AiAgentActionProposalStatus.EXPIRED.name(),
                false,
                "PROPOSAL_EXPIRED",
                "O prazo de validade desta proposta expirou. Gere uma nova análise para continuar.",
                proposal.getExpectedVersionTag(),
                proposal.getRecordLifecycleState(),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of()));
    }

    private ConfirmHttpResult executionInProgressConflict(UUID proposalId, AiAgentActionProposal proposal) {
        return new ConfirmHttpResult(409, new AgentProposalConfirmResponse(
                proposalId,
                AiAgentActionProposalStatus.EXECUTING.name(),
                false,
                "EXECUTION_IN_PROGRESS",
                "Esta proposta já está sendo executada por outra requisição.",
                proposal.getExpectedVersionTag(),
                proposal.getRecordLifecycleState(),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of()));
    }

    private void markProposalExpired(UUID proposalId, Instant now) {
        inConfigWriteTx(() -> {
            AiAgentActionProposal managed = proposalRepository.findById(proposalId).orElse(null);
            if (managed != null && managed.getStatus() != AiAgentActionProposalStatus.CONFIRMED
                    && managed.getStatus() != AiAgentActionProposalStatus.EXPIRED) {
                managed.setStatus(AiAgentActionProposalStatus.EXPIRED);
                managed.setResolvedAt(now);
                return proposalRepository.save(managed);
            }
            return managed;
        });
    }

    private void revertExecutingToPending(UUID proposalId) {
        try {
            inConfigWriteTx(() -> {
                AiAgentActionProposal managed = proposalRepository.findById(proposalId).orElse(null);
                if (managed != null && managed.getStatus() == AiAgentActionProposalStatus.EXECUTING) {
                    managed.setStatus(AiAgentActionProposalStatus.PENDING);
                    return proposalRepository.save(managed);
                }
                return managed;
            });
        } catch (RuntimeException revertEx) {
            log.warn("[OperationalAgentProposalService] Failed reverting proposal {} from EXECUTING to PENDING: {}",
                    proposalId, revertEx.getMessage());
        }
    }

    private void purgeExpiredProposalsIfDue(Instant now) {
        long nowSec = now.getEpochSecond();
        long prev = lastCleanupEpochSecond.get();
        if (nowSec - prev < CLEANUP_INTERVAL_SECONDS || !lastCleanupEpochSecond.compareAndSet(prev, nowSec)) {
            return;
        }
        try {
            Instant cutoff = now.minusSeconds(Math.max(60L, proposalRetentionSeconds));
            int deleted = inConfigWriteTx(() -> proposalRepository.deleteByExpiresAtBefore(cutoff));
            if (deleted > 0) {
                log.debug("[OperationalAgentProposalService] Purged {} expired action proposals older than {}", deleted, cutoff);
            }
        } catch (RuntimeException ex) {
            log.debug("[OperationalAgentProposalService] Skipped expired proposal cleanup: {}", ex.getMessage());
        }
    }

    private static Map<String, Object> minimizeStaleSnapshot(AgentActionExecutionOutcome outcome) {
        Map<String, Object> minimal = new LinkedHashMap<>();
        String versionTag = outcome != null && StringUtils.hasText(outcome.updatedVersionTag())
                ? outcome.updatedVersionTag()
                : (outcome != null && outcome.refreshedSnapshot().get("expectedVersionTag") instanceof String s ? s : null);
        String lifecycleState = outcome != null && StringUtils.hasText(outcome.updatedLifecycleState())
                ? outcome.updatedLifecycleState()
                : (outcome != null && outcome.refreshedSnapshot().get("recordLifecycleState") instanceof String s ? s : null);
        if (StringUtils.hasText(versionTag)) {
            minimal.put("expectedVersionTag", versionTag);
        }
        if (StringUtils.hasText(lifecycleState)) {
            minimal.put("recordLifecycleState", lifecycleState);
        }
        return minimal;
    }

    @SuppressWarnings("unchecked")
    private static void mergeNonMaskedInputs(Map<String, Object> target, Map<String, Object> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            Object candidate = entry.getValue();
            Object stored = target.get(key);
            if (isMaskedPlaceholder(candidate, stored)) {
                continue;
            }
            if (candidate instanceof Map<?, ?> candidateMap && stored instanceof Map<?, ?> storedMap) {
                Map<String, Object> nested = new LinkedHashMap<>();
                for (Map.Entry<?, ?> se : storedMap.entrySet()) {
                    if (se.getKey() != null) {
                        nested.put(se.getKey().toString(), se.getValue());
                    }
                }
                Map<String, Object> nestedIncoming = new LinkedHashMap<>();
                for (Map.Entry<?, ?> ie : candidateMap.entrySet()) {
                    if (ie.getKey() != null) {
                        nestedIncoming.put(ie.getKey().toString(), ie.getValue());
                    }
                }
                mergeNonMaskedInputs(nested, nestedIncoming);
                target.put(key, nested);
            } else {
                target.put(key, candidate);
            }
        }
    }

    private static boolean isMaskedPlaceholder(Object candidate, Object stored) {
        if (!(candidate instanceof String candidateStr) || !(stored instanceof String storedStr)) {
            return false;
        }
        if (candidateStr.equals(storedStr)) {
            return false;
        }
        return candidateStr.contains("***") || candidateStr.startsWith("**.");
    }

    private AgentExecutionContext buildConfirmContext(
            AiPrincipalContext principal,
            AiAgentActionProposal proposal,
            HttpServletRequest servletRequest) {
        Set<String> principalAuthorities = principal.authorities() != null
                ? principal.authorities()
                : Set.of();
        AgentExecutionContext ctx = new AgentExecutionContext(
                proposal.getThreadId(),
                proposal.getTurnId(),
                null,
                principal.tenantId(),
                principal.userId(),
                principal.environment(),
                principal.userId(),
                principalAuthorities,
                proposal.getResourcePath(),
                null,
                Map.of(),
                Map.of());
        for (AgentExecutionContextCustomizer customizer : contextCustomizers) {
            AgentExecutionContext updated = customizer.customize(ctx, servletRequest);
            if (updated != null) {
                ctx = updated;
            }
        }
        return ctx;
    }

    private <T> T inConfigWriteTx(java.util.function.Supplier<T> supplier) {
        if (configWriteTx == null) {
            return supplier.get();
        }
        return configWriteTx.execute(status -> supplier.get());
    }

    private <T> T inConfigReadTx(java.util.function.Supplier<T> supplier) {
        if (configReadTx == null) {
            return supplier.get();
        }
        return configReadTx.execute(status -> supplier.get());
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.debug("[OperationalAgentProposalService] Serialization warning: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed != null ? parsed : Map.of();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private <T> T readJson(String json, Class<T> target) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, target);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (StringUtils.hasText(c)) {
                return c.trim();
            }
        }
        return null;
    }
}
