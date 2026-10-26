# RELEASE & ACEITE — Matriz de Compatibilidade, Prontidão de Distribuição e Aceite Integral (Épico #8)

Data: 2026-10-26  
Issues: [`praxis-config-starter#513` (RELEASE)](https://github.com/codexrodrigues/praxis-config-starter/issues/513), [`praxis-hero-hq-ui#12` (ACEITE)](https://github.com/codexrodrigues/praxis-hero-hq-ui/issues/12) e [`praxis-hero-hq-ui#8` (Épico)](https://github.com/codexrodrigues/praxis-hero-hq-ui/issues/8).

---

## 1. Matriz Canônica Fonte → Artefato → Consumidor (RELEASE `#513`)

Todos os PRs dos owners foram integrados em `main`, e todas as bibliotecas e starters públicos consumidos nos hosts foram publicados pelos workflows oficiais e instalados a partir dos registries públicos (Maven Central e npm), sem `file:`, `npm link` ou `mvn install` local oculto:

| Camada / Repositório Owner | Branch / SHA em `main` | Artefato Público / Superfície | Versão / Coordenada Pública | Consumidores Alinhados e Validados |
| :--- | :--- | :--- | :--- | :--- |
| **`praxis-config-starter`** (`P0`, `P1-A`, `P1-D`, `P2`, `P3-A`, `P3-B` + otimização de orçamento [#533](https://github.com/codexrodrigues/praxis-config-starter/pull/533)) | `ba2008bd` (tag `v0.1.0-rc.175`, [Run #40158581504](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/40158581504)) | Maven Central (`io.github.codexrodrigues:praxis-config-starter`) | **`0.1.0-rc.175`** | `praxis-api-quickstart` (`pom.xml` `<praxis.config.version>0.1.0-rc.175</praxis.config.version>`, [PR #481](https://github.com/codexrodrigues/praxis-api-quickstart/pull/481) `48767e86`) |
| **`praxis-api-quickstart`** (`P3-C` [#461](https://github.com/codexrodrigues/praxis-api-quickstart/issues/461) + catálogo de 35 ícones [#480](https://github.com/codexrodrigues/praxis-api-quickstart/pull/480)) | `48767e86` ([PR #480](https://github.com/codexrodrigues/praxis-api-quickstart/pull/480), [PR #481](https://github.com/codexrodrigues/praxis-api-quickstart/pull/481)) | Host Operacional Backend (`/api/praxis/config/pages/**`, `/api/praxis/config/ui-layouts/**`, `/api/praxis/runtime/navigation`) | **`2.0.0-rc.57`** (consumindo `praxis-config-starter:0.1.0-rc.175`) | `praxis-hero-hq-ui` (`quickstart:corporate-page@2`, SHA-256 `93ef265c9eb0f19d81b6d2aac5de19497c2fcb6dce2c34f9ec254ab901b60319`) e `praxisui-http-examples` |
| **`praxis-ui-angular`** (`P1-B` `#623`, `P1-C` `#624`, `UX` `#625`, `P4` `#626`, `P5-D` `#627`) | `c50a31a6` (tag `praxis-ui-v10.0.0-rc.39`, [Run #40369318575](https://github.com/codexrodrigues/praxis-ui-angular/actions/runs/40369318575)) | npm registry (`@praxisui/core`, `@praxisui/dynamic-form`, `@praxisui/table`, `@praxisui/page-builder` e demais `@praxisui/*`) | **`10.0.0-rc.39`** (`dist-tag: rc`, OIDC + postflight público verde) | `praxis-hero-hq-ui` (`package.json` + `package-lock.json` em `10.0.0-rc.39`, [PR #26](https://github.com/codexrodrigues/praxis-hero-hq-ui/pull/26)) e showcase `/governed-pages-demo` |
| **`praxis-hero-hq-ui`** (`P5-A` `#9`, `P5-B` `#10`, `P5-C` `#11`, UX polish [#24](https://github.com/codexrodrigues/praxis-hero-hq-ui/pull/24), [#25](https://github.com/codexrodrigues/praxis-hero-hq-ui/pull/25), [#26](https://github.com/codexrodrigues/praxis-hero-hq-ui/pull/26)) | `main` ([PR #13](https://github.com/codexrodrigues/praxis-hero-hq-ui/pull/13), [#19](https://github.com/codexrodrigues/praxis-hero-hq-ui/pull/19)..[#26](https://github.com/codexrodrigues/praxis-hero-hq-ui/pull/26)) | Vitrine Corporativa (`/workspace-corporativo/*`, `/pages/:pageId`) e Laboratório Local (`/laboratorio`) | **`1.0.0`** (`@praxisui/*@10.0.0-rc.39`) | Certificação E2E Desktop (`1440×1000`) & Mobile/Narrow (`390×844`) e benchmark de 1.000 páginas |
| **`praxis-ui-landing-page`** (`P6-A` `#266`) | `e724f42` ([PR #269](https://github.com/codexrodrigues/praxis-ui-landing-page/pull/269)) | Guia público `/guides/governed-pages-publication-guide`, `registry.ts`, `sitemap.xml`, `llms.txt` | Integrado em `main` | Documentação oficial da plataforma (146 guias publicados, 316 URLs no sitemap) |
| **`praxisui-http-examples`** (`P6-B` `#30`) | `5489cb7` ([PR #32](https://github.com/codexrodrigues/praxisui-http-examples/pull/32)) | Corpus HTTP executável (`http/config/pages.http`, `payloads/config/pages_*.json`, `examples.manifest.json`, `LLM_SURFACE.md`) | Integrado em `main` | `npm run verify:manifest` (`182/182` exemplos válidos) |
| **`praxis-codex-skills`** (`P6-C` `#816`) | `e039a079` ([PR #866](https://github.com/codexrodrigues/praxis-codex-skills/pull/866)) | Skills canônicas (`praxis-angular-host-project`, `praxis-page-builder-authoring`, `praxis-config-runtime-persistence`) | Integrado em `main` e sincronizado | `audit-praxis-skills.ps1 -Family praxis` (`168/168 OK`, `Drift=0`, `SourceInvalid=0`) |

---

## 2. Rastreabilidade dos 15 Cenários de Integração (`scenarios.json` — ACEITE `#12`)

Todos os 15 cenários de integração definidos no contrato P0 ([`scenarios.json`](scenarios.json)) possuem implementação integrada e evidência verificável nos respectivos owners e hosts:

| # | Gate ID | Fase | Evidência e Testes Executados | Status |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `new-child-seed` | P1 | `PageCreationAdmissionCheckpointTest`, `UiLayoutPageChildCapturesTest` (`praxis-config-starter` `ba2008bd`) e `QuickstartPageStructureTest` (`praxis-api-quickstart` `48767e86`). | **PROVEN** |
| 2 | `same-child-binding` | P1 | `PagePublicationP0ContractTest` e `UiLayoutPageChildCapturesTest` rejeitam mutação de `mode`/`resourcePath` em key existente sem nova key + `childSources` admitida. | **PROVEN** |
| 3 | `copy-idempotency` | P2 | `PageCreationHttpTest` e evidência Neon P2 (`evidence/p2`): mesmo `(actor, scope, creationKey)` + mesmo corpo reconcilia o mesmo `pageId`/`draftId`; corpo divergente falha com conflito. | **PROVEN** |
| 4 | `copy-source` | P2 | `PageCreationServiceTest`, `PageCreationFullPathCostTest` (`131 → 28` authority units em `0.1.0-rc.175`) e `QuickstartPageHostTest`: copia exclusivamente revisão autoral publicada, remapeia `(kind,id)` e zera `navigation`. | **PROVEN** |
| 5 | `atomic-creation` | P2 | `PageCreationAdmissionCheckpointTest` e evidência transacional Neon P2 (`evidence/p2`): falha antes do commit reverte catálogo, registro, capturas, draft e recibo `creationKey`. | **PROVEN** |
| 6 | `page-group-race` | P3 | Evidência transacional Neon P3-A (`P3A-IMPLEMENTATION.md`, `evidence/p3a`): locks ordenados sobre grupos escopados serializam publicação de página contra omissão/rollback/withdraw de grupo. | **PROVEN** |
| 7 | `rollback-independent` | P3 | `P3A-IMPLEMENTATION.md` (`evidence/p3a`) + E2E `test-corporate-pages.cjs` (`praxis-hero-hq-ui` `465570a`): `rollback` restaura apenas a página alvo para uma release `APPROVED` anterior e nega se o grupo vinculado não estiver mais admitido. | **PROVEN** |
| 8 | `authorization-and-cache` | P3 | `PageRuntimeReadServiceTest`, evidência Neon P3-B (`P3B-VALIDATION.md`) e `test-corporate-pages.cjs`: revogação de permissão ou troca de tenant nega com `403`/`404` antes de qualquer `304`/conteúdo em cache. | **PROVEN** |
| 9 | `head-and-reload` | P3 | `PageRuntimeReadServiceTest`, `P3B-VALIDATION.md` e `test-corporate-pages.cjs`: `/pages/:pageId` e `/api/praxis/runtime/navigation` resolvem exclusivamente o head ativo autorizado; após `withdraw`, `/pages/:pageId` retorna `404` sem cair para rascunho. | **PROVEN** |
| 10 | `context-switch` | P4 | `governed-page-publication.spec.ts` (`@praxisui/page-builder@10.0.0-rc.39`), `test-session-seams.cjs`, `test-corporate-context.cjs` e `test-corporate-pages.cjs`: geração monotônica descarta callbacks atrasados após troca `A → B → A` ou expiração `401`. | **PROVEN** |
| 11 | `child-storage` | P4 | `dynamic-form-page-widget-adapter.ts` e `table-page-widget-adapter.ts` (`@praxisui/*@10.0.0-rc.39`): `configPersistenceStrategy: 'volatile'` e IDs determinísticos derivados de `(pageId, widgetKey)` impedem que preferências locais sobreponham a release publicada. | **PROVEN** |
| 12 | `preview-hooks` | P4 | `page-preview.ts` + `page-preview-and-navigation.spec.ts` (`@praxisui/core@10.0.0-rc.39`): `materializeGovernedPagePreview` força `[blockMutations]="true"` e bloqueia submissões e hooks de mutação nos adapters admitidos. | **PROVEN** |
| 13 | `migration-local` | P5 | `visitor-workspace.repository.ts`, `visitor-workspace.service.ts` (`migrate()`, `renamePage()`) e `test-visitor-lab.cjs` (`praxis-hero-hq-ui`): migração atômica sob Web Lock exclusivo preserva URLs, aplica ícone default `web` e recupera de quota/corrupção. | **PROVEN** |
| 14 | `legacy-import` | P5 | `inspectLegacyPublishedPagePayload`, `GovernedPageWorkflowController.importLegacyPublishedPage` e `governed-pages-demo.spec.ts` (`praxis-ui-angular` `7e1b54d3` / `10.0.0-rc.39`): preserva/exporta o registro `published-page:<uuid>` original e cria rascunho governado explícito sem fabricar aprovação. | **PROVEN** |
| 15 | `thousand-pages` | P5 | Backend Neon D1000 (`P3B-VALIDATION.md`: `1.000` identidades lidas em `35,476s/40s`) + Frontend `scripts/workspace/test-corporate-scale-1000.cjs` (`praxis-hero-hq-ui`: paginação de `1.000` páginas em `20 × 50` em `10,88ms`, `heapDelta = 0,72 MiB`). | **PROVEN** |

---

## 3. Demonstração Reproduzível e Limites Operacionais Explícitos

### 3.1 Roteiro Reproduzível
1. **Backend (`praxis-api-quickstart`)**:
   - `mvn test -Dtest=QuickstartPageDocumentsTest,QuickstartPageFormContentPolicyTest,QuickstartPageStructureTest,QuickstartPageHostTest` (`48/48 PASS` contra `praxis-config-starter:0.1.0-rc.175`).
2. **Bibliotecas e Exemplo Oficial (`praxis-ui-angular`)**:
   - `npm run audit:purity` (`0 violations`).
   - `npx ng test praxis-core --watch=false` (`39/39 PASS`), `npx ng test praxis-page-builder --watch=false` (`7/7 PASS`) e `governed-pages-demo.spec.ts` (`3/3 PASS`).
3. **Vitrine Principal (`praxis-hero-hq-ui`)**:
   - `npm test` (`58/58` asserções de contrato + navegação PASS).
   - `node test-e2e/test-corporate-native-source.cjs <path-to-quickstart>` (`PASS` com `@praxisui/core@10.0.0-rc.39` e `@praxisui/dynamic-form@10.0.0-rc.39`).
   - `node --test test-e2e/test-session-seams.cjs` (`16/16 PASS`).
   - `node scripts/workspace/test-corporate-navigation.cjs` (`24/24 PASS`) & `node scripts/workspace/test-corporate-scale-1000.cjs` (`PASS`).
   - Browser E2E (`test-corporate-context.cjs`, `test-corporate-pages.cjs`, `test-visitor-lab.cjs`): **100% PASS** em desktop (`1440×1000`) e mobile/narrow (`390×844`).

### 3.2 Limites Conhecidos e Governança de Deploy
- **Sem auto-deploy de produção**: Conforme diretriz do Épico `#8` e da issue `#513`, os artefatos públicos de plataforma (`praxis-config-starter:0.1.0-rc.175` no Maven Central e `@praxisui/*@10.0.0-rc.39` no npm) estão publicados e validados, e os repositórios consumidores estão alinhados na `main`; o deploy de ambientes hospedados finais permanece sob controle operacional de cada ambiente.
- **Escopo do Piloto Corporativo**:
  - O agregado `praxis.navigation-groups.editor` opera em modo `first-publish` (`If-None-Match: *`) nesta fase piloto para impedir substituição destrutiva de grupos fora da visibilidade do operador.
  - O consumidor `praxis-hero-hq-ui` preserva e projeta widgets companheiros `praxis-table` ao lado de `praxis-dynamic-form` (`P5-B`), enquanto a política de admissão de escrita do host de referência (`QuickstartPageConfiguration`) mantém o perfil conservador de 1 `praxis-dynamic-form` com campos locais até expansão deliberada do catálogo do host.
