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

## Fluxo recomendado (mais simples)
1) Entrar em **Actions -> CI and Release Java Starter (praxis-config-starter) -> Run workflow**.
2) Manter `create_tag=true`.
3) Preencher:
   - `version` (opcional) para fixar exatamente a versao, ou
   - `bump` (`patch`, `minor`, `major`, `prerelease`) e `preid` (ex.: `rc`).
4) Executar.

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
