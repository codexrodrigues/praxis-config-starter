# Releasing - praxis-config-starter

Este documento descreve o fluxo de CI e release no GitHub Actions para publicar no Maven Central com o menor atrito operacional.

O build, os gates downstream e a publicação usam Java 21, baseline necessário
para o contrato canônico de snapshots do `praxis-rules-engine`.

## O que esta automatizado
- Build automatico em `push` para `main` (job `Build on main`).
- Build de `smoke/unit` em `push` para `main` com perfil Maven `ci-smoke-unit`.
- Criacao automatica de tag por `workflow_dispatch` (job `Create release tag`), com:
  - versao explicita (`version`), ou
  - calculo automatico por semver (`bump`: patch/minor/major/prerelease + `preid`).
- Publicacao automatica no Maven Central ao receber tag `v*`.
- Release executa `smoke/unit` antes da assinatura/publicacao.
- O job de release publica **somente** quando a execucao foi disparada por tag `v*`.

## Convencao de testes para CI
- `@Tag("unit")`: testes unitarios deterministicos.
- `@Tag("smoke")`: testes de sanidade rapidos de contrato/wiring.
- `@Tag("integration")`, `@Tag("external")`, `@Tag("e2e")`: nao entram no profile `ci-smoke-unit`.
- O profile `ci-smoke-unit` roda apenas `groups=unit,smoke`.

## Secrets necessarios (repositorio)
- `CENTRAL_TOKEN_USER`
- `CENTRAL_TOKEN_PASS`
- `GPG_PRIVATE_KEY`
- `GPG_PASSPHRASE`
- `GPG_KEY_ID` (opcional)
- `RELEASE_PAT` (obrigatorio para o fluxo `create_tag=true`, com `contents:write`; pushes feitos com `GITHUB_TOKEN` nao disparam o workflow de publicacao por tag)
- `PRAXIS_AI_OPENAI_API_KEY` (necessario para o gate manual `Agentic Authoring HTTP Smoke` com `provider=openai`)
- `PRAXIS_AI_GEMINI_API_KEY` (necessario apenas quando o gate manual for executado com `provider=gemini`)

O workflow gera uma chave aleatoria e efemera para
`PRAXIS_RESOURCE_VERSION_ETAG_SECRET` em cada execucao, mascara o valor nos logs
e a fornece apenas ao Quickstart iniciado pelo smoke. Nao configure um valor
padrao ou compartilhado para esse gate.

## Gate de authoring antes de publicar
Antes de criar a tag de release, execute o smoke ponta a ponta contra o `praxis-api-quickstart`.
Esse gate valida a integracao real entre o starter publicado/local, o host de referencia, endpoints HTTP de authoring,
aplicacao de config e streaming SSE.

Fluxo recomendado:
1) Entrar em **Actions -> Agentic Authoring HTTP Smoke -> Run workflow**.
2) Executar com `provider=openai` e confirmar os SHAs imutaveis sugeridos para Quickstart, Metadata e Angular. Branches como `main` nao sao aceitas pelo gate.
3) Selecionar exatamente uma `paid_gate_lane` proporcional ao corte: `none` para validacao deterministica, `http-sse` para a jornada HTTP/SSE, `page-builder` para authoring browser ou `llm-compliance` para o shadow de compliance. Para Page Builder, manter `page_builder_e2e_mode=smoke`.
4) Confirmar que o job terminou com sucesso e publicou os artefatos da lane escolhida. Uma falha posterior de exportacao de evidencia deve ser reproduzida com os artefatos sanitizados, sem repetir automaticamente a chamada paga.
5) Somente depois executar **Actions -> CI and Release Java Starter (praxis-config-starter) -> Run workflow** para criar a tag.

O smoke manual:
- instala o `praxis-config-starter` do checkout no Maven local do runner;
- empacota o `praxis-api-quickstart` contra essa versao local, sem depender do Maven Central;
- usa por padrao um ref pinado do `praxis-api-quickstart` para evitar que releases do starter fiquem bloqueados por dependencias ainda nao publicadas no consumidor;
- sobe o quickstart empacotado;
- executa uma unica jornada paga `governed-authoring-apply`, que resolve a intencao, planeja e compila a materializacao, transmite o turno por SSE, aplica o resultado terminal com linhagem, le a configuracao persistida e executa cleanup;
- preserva no mesmo thread a decisao semantica emitida pelo backend e permite no maximo uma continuacao `governed-review-revise`; label e prompt sao apenas apresentacao, e ausencia, duplicidade ou novo bloqueio terminam fail-closed;
- mantem os scripts isolados de `intent-resolution`, `minimal-form-plan`, `compiled-form-patch`, `page-preview` e patch stream como diagnosticos focais, sem repeti-los no gate pago de release;
- valida o recurso canonico `/api/operations/incidentes`, os campos obrigatorios do formulario, `page-apply`, readback, linhagem SSE e cleanup.
- quando `paid_gate_lane=page-builder`, valida o fluxo agentic do page-builder com browser real; o perfil `smoke` executa uma unica jornada canonica sem retries automaticos, e `full` fica reservado a investigacoes deliberadas da matriz completa;
- usa os defaults de `tools/e2e/page-builder-agentic-gate-matrix.json`, atualmente com `praxis.ai.stream.processing-timeout-seconds=360`, para acomodar turnos reais com discovery, RAG, multiplas chamadas LLM e materializacao;
- instala Metadata e Config a partir de copias temporarias exatas de `git archive HEAD`, sem diretorio `.git`; Config/Quickstart/Angular falham antes de usar o provider se seus working trees estiverem dirty, enquanto Metadata registra commit + tree SHA e declara `materialization=git-archive`, pois seu checkout historico normaliza line endings que nao participam do build;
- executa apenas a config Playwright `production-like`, que bloqueia mocks de endpoints criticos, exige capabilities provenientes do registry e produz JSON com discovered/executed/skipped/failed, tentativas/retries reais, SHAs de checkouts limpos, versoes efetivas de Config/Metadata/Quickstart/Angular, provider/model sanitizado e cleanup;
- confirma o bootstrap real do AI Registry pelo hash SHA-256 do snapshot versionado, a ingestao real do Domain Catalog e o estado `READY` do indice canonico do API Catalog;
- publica `criticalEndpointMocks=0` somente quando o teste negativo da matriz comprovar que a interceptacao critica foi rejeitada antes do registro; trace, video e screenshot permanecem desabilitados na lane live;
- publica no artifact remoto somente `summary.json` do HTTP/SSE e a pasta sanitizada `agentic-authoring-publication`; logs, payloads, relatorio HTML, traces, videos, screenshots e o JSON bruto do Playwright permanecem locais ao runner;
- mantem cenarios com mocks em uma config separada, sem contabiliza-los como gate live.

Para reproduzir localmente, primeiro empacote o quickstart e depois rode:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart -ConfirmPaidProviderRun
```

Para alterar o timeout do stream no smoke local:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart -StreamProcessingTimeoutSeconds 360 -ConfirmPaidProviderRun
```

Para depurar localmente apenas a observabilidade do planejamento pre-intent do
turn stream, sem Angular/browser e sem Render, suba o `praxis-api-quickstart`
local em `http://localhost:8088` empacotado contra o starter em desenvolvimento
e rode:

```bash
BASE_URL=http://localhost:8088 tools/local-e2e/run-agentic-turn-pre-intent-local.sh
```

Esse smoke chama diretamente
`POST /api/praxis/config/ai/authoring/turn/stream/start`, consome o SSE bruto e
falha se `tool.plan` ou `tool.plan.skipped` nao aparecer antes de
`intent.resolved` para o prompt
`quero criar algo que mostre informacoes dos empregados`. Quando `tool.plan`
executa, encontra candidatos e a resolucao semantica final falha por provider,
o smoke tambem exige `consultative.grounded-clarification`, resultado terminal
com `canApply=false` e
`decisionDiagnostics.resourceDiscoveryGroundedClarification=true`.

Para validar o mesmo fluxo com prompts abertos, sinonimos, erro de digitacao e
um caso fora de RH, use a matriz local:

```bash
BASE_URL=http://localhost:8088 tools/local-e2e/run-agentic-turn-pre-intent-matrix-local.sh
```

A matriz reutiliza o mesmo contrato do smoke unitario e grava um
`matrix-summary.json` consolidado com `expectedResourceRank`,
`expectedResourceRecovered`, top candidatos, recurso selecionado,
`reviewReason`, quick replies e `unexpectedApplyCount`. Ela deve ser usada
durante investigacoes do authoring pre-intent para provocar limites de recall,
ranking e materializacao segura, nao apenas para confirmar o prompt feliz.

Quando a versao ja estiver publicada no Maven Central, valide tambem o consumidor sem override local:

```powershell
cd ..\praxis-api-quickstart
mvn -B verify
```

## Fluxo recomendado (mais simples)
1) Executar o gate **Agentic Authoring HTTP Smoke**.
2) Entrar em **Actions -> CI and Release Java Starter (praxis-config-starter) -> Run workflow**.
3) Manter `create_tag=true`.
4) Preencher:
   - `version` (opcional) para fixar exatamente a versao, ou
   - `bump` (`patch`, `minor`, `major`, `prerelease`) e `preid` (ex.: `rc`).
5) Executar.

`create_tag=false` nao publica artefatos. Publicacao sem tag e deliberadamente
rejeitada para preservar rastreabilidade e reprodutibilidade.

Resultado:
- A workflow atualiza o `pom.xml`, cria um commit `chore: release vX.Y.Z` e envia o commit e a tag de forma atomica.
- A tag `vX.Y.Z` (ou `vX.Y.Z-rc.N`) aponta para uma arvore cujo `project.version` corresponde exatamente a versao publicada.
- O push da tag dispara automaticamente o job de release/publicacao no Maven Central.
- O job de publicacao confere que a versao declarada no `pom.xml` da tag e exatamente a versao da tag; divergencias falham antes de assinatura ou upload.

## Exemplos praticos
- Proximo patch estavel:
  - `create_tag=true`, `bump=patch`
- Novo RC:
  - `create_tag=true`, `bump=prerelease`, `preid=rc`
  - continua a maior serie RC existente quando ela ainda nao foi superada por uma release estavel
- Versao fixa:
  - `create_tag=true`, `version=1.2.0`

## Convencao de tags
- Formato aceito para release automatica: `v*` (ex.: `v1.2.0`, `v1.2.1-rc.1`).

## Validacao local (opcional)
```bash
mvn -B -P ci-smoke-unit -T 1C clean verify
```

## Troubleshooting
- Falha para criar tag:
  - Configure `RELEASE_PAT` com permissao de `contents:write`; o workflow nao usa `GITHUB_TOKEN` como fallback porque esse token nao encadeia o workflow acionado pelo push da tag.
  - Confirme que a protecao de `main` permite ao titular do token persistir o commit de versao.
- Falha em assinatura GPG:
  - Validar `GPG_PRIVATE_KEY` sem CRLF/BOM e `GPG_PASSPHRASE`.
- Falha na publicacao:
  - Verificar `CENTRAL_TOKEN_USER/CENTRAL_TOKEN_PASS` e namespace no Central Portal.
