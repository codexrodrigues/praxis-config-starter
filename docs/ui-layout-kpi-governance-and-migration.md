# Governança de KPIs Executivos em `ui-layouts` e Migração de `praxis-micro-kpi-strip`

Este guia documenta a arquitetura server-side da `praxis-config-starter` para validação estrutural de KPIs executivos (`praxis-kpi-strip` e `praxis-kpi-card`), isolamento multi-audiência, proteção contra adulteração em `ui_user_config` e migração de faixas legadas baseadas em `praxis-micro-kpi-strip`.

## 1. Auto-configuração do Catálogo KPI (`UiLayoutKpiCatalogAutoConfiguration`)

A auto-configuração [`UiLayoutKpiCatalogAutoConfiguration`](../src/main/java/org/praxisplatform/config/autoconfig/UiLayoutKpiCatalogAutoConfiguration.java) registra automaticamente o bean `UiLayoutKpiCatalogOracle` apoiado em `MetadataStarterUiLayoutKpiCatalogOracle` sempre que as classes do `praxis-metadata-starter` estiverem no classpath — **sem depender** de `CorporatePageCatalogContributor`.

Em aplicações Spring Boot com `praxis-metadata-starter` + `praxis-config-starter`, a validação estrutural ao publicar layouts (`ui-layouts`) ou gravar customizações pessoais (`ui_user_config`) passa automaticamente a consultar:

- controladores e caminhos canônicos de recursos (ex.: `workforce/assignments`, `payroll/runs`);
- elegibilidade por operação analítica (`group-by`, `timeseries`, `comparison`, `distribution`) via `StatsFieldRegistry`;
- campos válidos no `GenericFilterDTO` do recurso (`baseFilter` e `contextFilters`);
- receitas verificadas (`@VerifiedAnalyticalRecipe`) e parâmetros ajustáveis permitidos;
- projeções declaradas em `@UiAnalytics` (`projectionId`).

Para personalizar ou sobrescrever em hosts avançados, basta declarar um `@Bean` explícito de `UiLayoutKpiCatalogOracle` (`@ConditionalOnMissingBean`).

## 2. Proteção Server-Side em `ui_user_config` e ETag Composto

Quando uma página ou widget corporativo é publicado em `ui-layouts`, os cards podem declarar `governance.lockLevel`:

- `none`: o usuário pode ajustar apresentação, fonte e filtros dentro das cotas permitidas;
- `metric-locked`: o usuário pode ajustar apenas identidade visual, metas e interações; alteração da métrica, `source`, `derivation` ou remoção do card governado são bloqueadas;
- `full`: o card inteiro permanece somente leitura no runtime e nas gravações pessoais.

### Fluxo de proteção em `KpiGovernanceUserConfigGuard`

1. **Validação estrutural incondicional (anti-SSRF e cotas):** toda gravação `PUT /api/praxis/config/ui` de `praxis-dynamic-page`, `praxis-kpi-strip` ou `praxis-kpi-card` passa por `UiLayoutKpiStructureValidator.validateRuntimePayloadStructure(...)` mesmo quando não existe revisão publicada de baseline ou quando `lockLevel` é `none`/`metric-locked`:
   - máximo de **12 cards** por `praxis-kpi-strip`;
   - máximo de **24 consultas analíticas distintas** por página (incluindo containers aninhados em `tabs`, `panels`, `steps`, `nav.links` e `page.widgets` até profundidade 12);
   - bloqueio de URLs absolutas (`http://`, `https://`), `//` ou `..` em `resourcePath` e caminhos de interação.
2. **Isolamento multi-audiência do baseline (`ReleaseReaderPublishedUiLayoutBaselineProvider`):** a resolução do baseline publicado utiliza `EffectiveUiAudienceProvider` (e `Principal` / `X-Praxis-Context-Version` da requisição quando disponível). Quando a audiência não pode ser resolvida, restringe o baseline apenas à camada `TENANT` e ao `USER` exato, evitando cruzamento entre camadas `ORGANIZATION`, `SECTOR`, `GROUP` ou `PROFILE`.
3. **ETag composto de governança e `X-Env`:** `UserConfigController` e `UserConfigService` compõem o `ETag` com o token de governança publicada (`<uuid>-kpi-<token>`), validam `If-Match` contra esse `ETag` composto em `PUT`/`DELETE` e propagam o ambiente efetivo (`X-Env`) mesmo quando `environment` não foi persistido na linha de `ui_user_config`.

### Códigos de Erro Canônicos

| HTTP | Código (`errorCode`) | Causa típica |
| --- | --- | --- |
| `422 Unprocessable Entity` | `KPI_STRUCTURE_VIOLATION` | URL externa (SSRF), cota acima de `12` cards/strip ou `24` queries/página, `lockLevel` inválido ou chave desconhecida no schema fechado. |
| `422 Unprocessable Entity` | `KPI_GOVERNANCE_LOCK_VIOLATION` | Tentativa de remover card `metric-locked`/`full` ou alterar `source`/`derivation` travados por governança publicada em `ui-layouts`. |
| `412 Precondition Failed` | — | `If-Match` divergente do `ETag` composto atual (incluindo alteração superveniente do baseline publicado). |

## 3. Migração de `praxis-micro-kpi-strip` (depreciado) para `praxis-kpi-strip`

1. **Identifique receitas ou projeções existentes** no recurso de backend (`GET /api/<recurso>/stats/recipes` e `x-ui.analytics` em `/schemas/filtered`).
2. **Substitua o widget legado** `praxis-micro-kpi-strip` por `praxis-kpi-strip` nos documentos `ui-layouts` ou templates Angular:
   - defina UUID RFC 4122 estável em cada `card.id` e em `widget.key`;
   - migre `source` para `{ kind: 'verified-recipe', resourcePath, recipeId }` (preferencial) ou `stats-query` / `analytics-projection`;
   - declare explicitamente `derivation`, `visualization` e `interaction` (`crossFilter` / `drillDown`).
3. **Conecte o contexto em 3 canais (`PraxisDataQueryContext`):** envie filtros globais do cockpit em `meta.scopeFilters` e filtros cruzados em `filters` + `meta.crossFilterSources`. Em `verified-recipe`, o `PraxisStatsExecutionService` separa automaticamente `parameters` (ajustáveis declarados na receita) de `contextFilters` (filtros ambientais do cockpit).
4. **Habilite edição corporativa apenas para curadores:** passe `allowGovernanceEditing` (ou `authoringMode: 'curator'`) somente em fluxos administrativos autorizados de `ui-layouts`; usuários finais devem operar sem essa flag para que `metric-locked` e `full` permaneçam protegidos no UI e no servidor.
