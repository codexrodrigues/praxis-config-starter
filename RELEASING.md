# Releasing - praxis-config-starter

Este documento descreve o fluxo de CI e release no GitHub Actions para publicar no Maven Central com o menor atrito operacional.

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
- `RELEASE_PAT` (opcional, recomendado quando `GITHUB_TOKEN` nao consegue criar/push de tags por regras de branch/protecao)
- `PRAXIS_AI_OPENAI_API_KEY` (necessario para o gate manual `Agentic Authoring HTTP Smoke` com `provider=openai`)
- `PRAXIS_AI_GEMINI_API_KEY` (necessario apenas quando o gate manual for executado com `provider=gemini`)

## Gate de authoring antes de publicar
Antes de criar a tag de release, execute o smoke ponta a ponta contra o `praxis-api-quickstart`.
Esse gate valida a integracao real entre o starter publicado/local, o host de referencia, endpoints HTTP de authoring,
aplicacao de config e streaming SSE.

Fluxo recomendado:
1) Entrar em **Actions -> Agentic Authoring HTTP Smoke -> Run workflow**.
2) Executar com `provider=openai` e manter o `quickstart_ref` padrao do workflow, salvo quando a validacao exigir explicitamente outro ref.
3) Para releases que alterem authoring, page-builder, manifestos executaveis, SSE ou compilacao de patches, marcar `run_page_builder_full_e2e=true`.
4) Confirmar que o job `Quickstart HTTP/SSE smoke` terminou com sucesso. Quando `run_page_builder_full_e2e=true`, confirmar tambem que o gate Playwright completo do page-builder terminou com sucesso.
5) Somente depois executar **Actions -> CI and Release Java Starter (praxis-config-starter) -> Run workflow** para criar a tag.

O smoke manual:
- instala o `praxis-config-starter` do checkout no Maven local do runner;
- empacota o `praxis-api-quickstart` contra essa versao local, sem depender do Maven Central;
- usa por padrao um ref pinado do `praxis-api-quickstart` para evitar que releases do starter fiquem bloqueados por dependencias ainda nao publicadas no consumidor;
- sobe o quickstart empacotado;
- valida `minimal-form-plan`, `compiled-form-patch`, `page-preview`, `page-apply`, SSE, replay e cleanup.
- quando `run_page_builder_full_e2e=true`, valida tambem o fluxo agentic completo do page-builder com browser real;
- usa `praxis.ai.stream.processing-timeout-seconds=180` por padrao para acomodar turnos reais com discovery, RAG e LLM.

Para reproduzir localmente, primeiro empacote o quickstart e depois rode:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart
```

Para alterar o timeout do stream no smoke local:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1 -Provider openai -QuickstartRoot ..\praxis-api-quickstart -StreamProcessingTimeoutSeconds 180
```

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

Resultado:
- A workflow cria/push da tag `vX.Y.Z` (ou `vX.Y.Z-rc.N`).
- O push da tag dispara automaticamente o job de release/publicacao no Maven Central.

## Exemplos praticos
- Proximo patch estavel:
  - `create_tag=true`, `bump=patch`
- Novo RC:
  - `create_tag=true`, `bump=prerelease`, `preid=rc`
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
  - Configure `RELEASE_PAT` com permissao de `contents:write`.
- Falha em assinatura GPG:
  - Validar `GPG_PRIVATE_KEY` sem CRLF/BOM e `GPG_PASSPHRASE`.
- Falha na publicacao:
  - Verificar `CENTRAL_TOKEN_USER/CENTRAL_TOKEN_PASS` e namespace no Central Portal.
