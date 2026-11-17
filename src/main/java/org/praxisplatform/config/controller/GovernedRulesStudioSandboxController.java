package org.praxisplatform.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.praxisplatform.config.contract.DomainRuleTestRunResponse;
import org.praxisplatform.config.runtime.GovernedRulesStudioSandboxEndpointOwner;
import org.praxisplatform.config.runtime.GovernedRulesStudioSandboxService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.rules.plan.RulePlanException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controlador canônico do sandbox e da prova operacional do Policy/Rules Studio ({@code
 * /api/praxis/rules-studio/**} e alias {@code /api/praxis/policy-studio/**}).
 *
 * <p>Implementa {@link GovernedRulesStudioSandboxEndpointOwner} e recua automaticamente sempre que o
 * host registra sua própria implementação dedicada sobre as mesmas rotas.
 */
@RestController
@RequestMapping({
  GovernedRulesStudioSandboxController.BASE_PATH,
  GovernedRulesStudioSandboxController.POLICY_STUDIO_BASE_PATH
})
@ConditionalOnBean({
  GovernedRulesStudioSandboxService.class,
  DomainRuleGovernancePrincipalResolver.class
})
public class GovernedRulesStudioSandboxController
    implements GovernedRulesStudioSandboxEndpointOwner {

  /** Prefixo canônico das rotas do studio de regras. */
  public static final String BASE_PATH = "/api/praxis/rules-studio";

  /** Alias canônico de transição para consumidores do {@code praxis-policy-studio}. */
  public static final String POLICY_STUDIO_BASE_PATH = "/api/praxis/policy-studio";

  /** Chave de recurso canônica publicada no catálogo de ações do studio. */
  public static final String RESOURCE_KEY = "praxis.rules-studio";

  /** Alias canônico de recurso para consumidores do studio de políticas. */
  public static final String POLICY_STUDIO_RESOURCE_KEY = "praxis.policy-studio";

  /** Identificador canônico da ação de prova operacional em lote governada. */
  public static final String OPERATIONAL_TEST_ACTION_ID = "run-rules-studio-operational-test";

  /** Caminho HTTP canônico da ação de prova operacional. */
  public static final String OPERATIONAL_TEST_ACTION_PATH =
      BASE_PATH + "/actions/" + OPERATIONAL_TEST_ACTION_ID;

  private final GovernedRulesStudioSandboxService sandbox;
  private final DomainRuleGovernancePrincipalResolver principalResolver;

  public GovernedRulesStudioSandboxController(
      GovernedRulesStudioSandboxService sandbox,
      DomainRuleGovernancePrincipalResolver principalResolver) {
    this.sandbox = sandbox;
    this.principalResolver = principalResolver;
  }

  /**
   * Simula os cenários ativos selecionados de um workspace aberto comparando o plano candidato ao
   * plano ativo no escopo do tenant e registra o recibo no {@code DomainRuleTestRunService}.
   */
  @PostMapping("/sandbox/runs")
  @Operation(
      operationId = "runGovernedRulesStudioSandbox",
      summary = "Executar simulação em sandbox do Policy/Rules Studio",
      description =
          "Avalia cenários determinísticos contra o plano ativo e o candidato do workspace com persistência idempotente de resultados.")
  public ResponseEntity<GovernedRulesStudioSandboxService.SandboxRunResponse> runSandbox(
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      @RequestBody GovernedRulesStudioSandboxService.SandboxRunRequest request,
      HttpServletRequest servletRequest) {
    DomainRuleGovernancePrincipal principal =
        principalResolver.resolve(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(sandbox.run(request, principal));
  }

  /**
   * Executa a prova operacional idempotente e protegida por {@code If-Match} para cenários {@code
   * CREATE}/{@code UPDATE}.
   */
  @PostMapping({
    "/actions/run-rules-studio-operational-test",
    "/actions/run-policy-studio-operational-test"
  })
  @Operation(
      operationId = "runRulesStudioOperationalTest",
      summary = "Executar prova operacional governada no sandbox do Policy/Rules Studio",
      description =
          "Valida integralmente as asserções esperadas do candidato para cenários CREATE/UPDATE e registra evidência sanitizada com concorrência otimista.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Test Run persistido ou receipt idempotente."),
    @ApiResponse(responseCode = "400", description = "Comando, cenário ou cabeçalho inválido."),
    @ApiResponse(responseCode = "403", description = "Ator sem autoridade operacional."),
    @ApiResponse(responseCode = "409", description = "Chave idempotente conflitante."),
    @ApiResponse(responseCode = "412", description = "ETag do workspace obsoleto."),
    @ApiResponse(responseCode = "422", description = "Asserções ou grafo candidato inválidos."),
    @ApiResponse(responseCode = "428", description = "If-Match ausente.")
  })
  public ResponseEntity<DomainRuleTestRunResponse> runOperationalTest(
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      @RequestBody GovernedRulesStudioSandboxService.OperationalTestRunRequest request,
      HttpServletRequest servletRequest) {
    DomainRuleGovernancePrincipal principal =
        principalResolver.resolve(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR");
    var result =
        sandbox.runOperationalTest(request, ifMatch, idempotencyKey, correlationId, principal);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag(result.workspaceEtag())
        .body(result.run());
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleStatusFailure(ResponseStatusException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(ex.getStatusCode());
    problem.setTitle(
        ex.getStatusCode() instanceof HttpStatus status
            ? status.getReasonPhrase()
            : ex.getStatusCode().toString());
    problem.setDetail(ex.getReason() != null ? ex.getReason() : ex.getMessage());
    Object code =
        ex.getBody().getProperties() != null
            ? ex.getBody().getProperties().get("errorCode")
            : null;
    String resolvedCode = code != null ? code.toString() : ex.getStatusCode().toString();
    problem.setProperty("errorCode", resolvedCode);
    problem.setProperty("code", resolvedCode);
    return ResponseEntity.status(ex.getStatusCode()).body(problem);
  }

  @ExceptionHandler(GovernedRulesStudioSandboxService.InvalidRuleSandboxCandidateException.class)
  public ResponseEntity<ProblemDetail> handleInvalidCandidate(
      GovernedRulesStudioSandboxService.InvalidRuleSandboxCandidateException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setTitle("Unprocessable RuleSet Candidate");
    problem.setDetail(ex.getMessage());
    problem.setProperty("errorCode", ex.code());
    problem.setProperty("code", ex.code());
    return ResponseEntity.unprocessableEntity().body(problem);
  }

  @ExceptionHandler(RulePlanException.class)
  public ResponseEntity<ProblemDetail> handleRulePlanFailure(RulePlanException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setTitle("Invalid RuleSet Graph");
    problem.setDetail(ex.getMessage());
    problem.setProperty("errorCode", "INVALID_RULESET_GRAPH");
    problem.setProperty("code", "INVALID_RULESET_GRAPH");
    return ResponseEntity.unprocessableEntity().body(problem);
  }
}
