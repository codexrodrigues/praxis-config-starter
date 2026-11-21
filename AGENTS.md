# AGENTS.md — Praxis Config Starter

## Escopo, Premissa e Classificação
- **Escopo:** `praxis-config-starter` e subpastas; complementa o `AGENTS.md` raiz do monorepo. Não editar por padrão `target/`, `logs/`, `artifacts/`, outputs de apidoc e gerados de build.
- **Premissa:** modelar necessidades de authoring, explainability, preview, governance ou runtime decision como decisão canônica governada em `/api/praxis/config/**`, não como patch solto ou editor local.
- **Inventário Antes de Novo Contrato:** auditar o que já existe (`ai_registry`, `api_metadata`, templates, contexto, diagnostics, quick replies, previews, warnings, headers, ETag, histórico) e classificar em `ja-suportado-so-ux`, `ja-suportado-mal-nomeado-ou-mal-materializado`, `suportado-parcialmente` ou `lacuna-real-de-contrato` (só esta última autoriza novo contrato com dado faltante, fonte, consumidores, derivados e validação).
- **Classificação:** `docs-apenas` | `local-pequena` | `transversal` | `contrato-publico` | `arquitetural`.

## Fronteira Canônica e Praxis Decision Platform
- Fonte canônica de persistência e semântica de `ui_user_config`, `ai_registry`, `api_metadata`, templates, headers de tenant/usuário/ambiente, ETag de config, endpoints `/api/praxis/config/**`, contratos AI, authoring manifests, validação, compilação de patch e streaming SSE. Funciona isolado no CI (não depender da raiz do monorepo nem de semântica local de `praxis-api-quickstart`, que é apenas prova downstream).
- **Control Plane / PAP (`decisionKind = BUSINESS` vs. `decisionKind = AUTHORIZATION`):** sob `/api/praxis/config/domain-rules/**` (`DomainRuleDefinition`), mantém separação ortogonal estrita:
  - **`decisionKind = BUSINESS`** (`selection_eligibility`, `backend_validation`, `workflow_action_policy`, `approval_policy` em JSON Logic): compilado/avaliado por `praxis-rules-engine`.
  - **`decisionKind = AUTHORIZATION`** (`ruleType = "authorization_policy"`, schema `praxis.authorization-policy/v1`, `targetLayer = "policy_engine"`, `targetArtifactType = "opa-policy"`, `targetArtifactKey = "unit:<policyId>:<majorVersion>"`): compilado em unidades/releases assinados (`OpaPolicyUnitManifest`, `OpaRuntimeCompositionManifest`, `OpaRuntimeRelease`) para `praxis-authz-starter` / `praxis-authz-enforcement-core` / `praxis-authz-opa`. Nunca usar `praxis-rules-engine` para segurança/mascaramento nem criar segundo control plane.
- **Roteamento Semântico de Intenção:** `AiOrchestratorService`, manifests, validators, compilers, previews e controllers não devem decidir intenção primária por palavras-chave, regex, `contains`, listas de termos ou fast paths locais (contexto + catálogos + manifests + LLM/tooling resolvem primeiro; matching textual só ranqueia candidatos depois, e fast paths residuais exigem registro/isolamento). Se faltar operação, modelar operação/tool canônica.

## Áreas de Alto Risco e Regras Locais
- **Alto risco:** `src/main/java/org/praxisplatform/config/**`, `src/main/resources/**`, `docs/ai/**`, `tools/**`, `.github/workflows/**`, `pom.xml`, `README.md`, `RELEASING.md`, `docs/ai/agentic-authoring/contracts/**`.
- Não improvisar endpoints/headers/payloads fora de `/api/praxis/config/**`. Mudanças em contratos AI atualizam tipos, validadores, docs, smokes e derivados juntos. Scripts em `tools/` usados no CI resolvem paths relativos ao próprio repo. Nunca logar secrets/chaves.
- Authoring/SSE exige cobertura de `start`, `probe`, stream, evento terminal, replay e cancelamento; manifests/validators executáveis são contrato público. Manter topo do `README.md` focado em valor, escopo e fronteiras (detalhes granulares de SPIs/pacotes vão para o final do `README.md` ou `docs/**`).

## Release e Gate de Authoring (`agentic-authoring-smoke.yml`)
- Antes de publicar no Maven Central, usar `.github/workflows/agentic-authoring-smoke.yml`:
  - `config_artifact_source`: `source-checkout` (instala checkout) vs. `maven-central` (baixa e valida SHA-512 da coordenada do Quickstart, exigindo identidade byte a byte entre JAR de referência e aninhado).
  - `quickstart_ref`, `metadata_ref`, `ui_ref`: SHAs imutáveis de 40 caracteres (branches móveis falham).
  - `paid_gate_lane`: exclusivo (`none` determinístico, `http-sse`, `page-builder`, `llm-compliance`). Lanes pagas exigem aprovação no GitHub Environment `ai-paid-gates`; alterações no fluxo agentic do page-builder / SSE browser / patch-apply selecionam `paid_gate_lane=page-builder` (`page_builder_e2e_mode=smoke`, ou `page_builder_e2e_mode=full` sob investigação deliberada).
  - Gate `page-builder` faz checkout de `praxis-ui-angular`, sobe quickstart (`8088`), Angular (`4003`) e roda `praxis-page-builder-agentic-production-like.playwright.config.ts` contra PostgreSQL/pgvector, LLM e embeddings reais (`signed-url-token`, segredo efêmero), usando timeouts/retries de `tools/e2e/page-builder-agentic-gate-matrix.json` (mocks são lane `mocked` e não contam como production-like).
  - **Secrets:** `PRAXIS_AI_OPENAI_API_KEY` (`provider=openai`), `PRAXIS_AI_GEMINI_API_KEY` (`provider=gemini`), `PRAXIS_RESOURCE_VERSION_ETAG_SECRET` (gerada efêmera por run, sem default no runtime) e `RELEASE_PAT` (`create_tag=true`, `contents:write`, pois `GITHUB_TOKEN` não dispara workflow por tag). Se o gate falhar, corrigir a causa real antes de publicar.

## Validação Local & Downstream
- No Windows sem `mvn` no `PATH`, usar `<maven-home>\bin\mvn.cmd`. Mudanças docs-only: leitura final + `git diff --check`.
- **Implementação e regressão:** rodar seleção focal real `mvn -B "-Dtest=<classes-afetadas-e-dependentes>" test` (não substitui gates de migração, isolamento, concorrência ou HTTP/OpenAPI). Para candidato consolidado: `mvn -B -P ci-smoke-unit -T 1C verify` (não substitui integrações externas; acrescentar `clean` quando exigido). Registrar comando, motivo, duração, resultado e identidade do candidato.
- **Smokes locais com quickstart:**
  - Determinístico: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart -DomainRuleLifecycleOnly`
  - Pago (só após aprovação de custo): adicionar `-ConfirmPaidProviderRun` sem `-DomainRuleLifecycleOnly`.
  - Page-builder agentic pago: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-PbAgenticFullE2E.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart -UiRoot ..\praxis-ui-angular -ValidationMode smoke -ConfirmPaidProviderRun` (ou `-ValidationMode full`).
- **Workflow GitHub (`gh`):** determinístico `gh workflow run agentic-authoring-smoke.yml --repo codexrodrigues/praxis-config-starter -f provider=openai -f paid_gate_lane=none` (prova publicada: `-f config_artifact_source=maven-central`; pago: substituir `none` por uma única lane aprovada).
- **Downstream (`praxis-api-quickstart`):** publicado → confirmar `praxis-api-quickstart/pom.xml`, `mvn -B verify` e smoke HTTP/SSE; não publicado → `mvn install` local + empacotar quickstart + smoke HTTP/SSE.

## Derivados, Referências, Pronto e Economia de CI
- **Derivados:** revisar `README.md`, `RELEASING.md`, `docs/ai/**`, `tools/**`, `.github/workflows/**` e `praxis-api-quickstart/AGENTS.md`/docs (ou descartar explicitamente no encerramento).
- **Referências:** `README.md`, `RELEASING.md`, `docs/ai/contracts/README.md`, `docs/ai/agentic-authoring-streaming.md`, `tools/Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1`, `tools/Invoke-QuickstartAiPatchStreamHttpE2E.ps1`, `.github/workflows/agentic-authoring-smoke.yml`, `../praxis-api-quickstart/AGENTS.md`.
- **Pronto & Economia de CI:** explicitar origem da mudança, validação executada, prova downstream e derivados revisados. Zero GitHub Actions em dev normal; consultar [ACTIONS-RELEASE-POLICY.md](ACTIONS-RELEASE-POLICY.md) antes de tag (`v*`) ou dispatch.
