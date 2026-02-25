# P1-BE-8 Memoria de Programa - Baseline Atual e Alvo Corporativo

Status
- Data de corte desta memoria: 2026-02-22.
- Objetivo: registrar com evidencia onde estamos saindo e onde queremos chegar para evitar perda de contexto entre sessoes.
- Escopo: backend (`praxis-config-starter`) + frontend (`praxis-ui-angular`) + pipeline de catalogo/RAG + readiness operacional.
- Premissa operacional atual: ambiente single-node (1 maquina, sem LB real). Itens distribuidos devem ser tratados como bloqueio de ambiente.

## 1) Como usar este documento

1. Este arquivo e a referencia primaria de baseline e alvo do programa AI corporativo.
2. Qualquer mudanca relevante deve atualizar:
   - status por fase (A, B, C, 4, 5);
   - lacunas de aprovacao corporativa;
   - checklist de aceite e log de atualizacoes.
3. Sempre anexar evidencia em formato `arquivo:linha` ou marcar explicitamente como `nao evidenciado`.
4. Quando houver conflito entre narrativa e codigo, o codigo com evidencia prevalece.

## 2) Snapshot executivo (2026-02-22)

### 2.1 Forcas ja comprovadas (evidenciado)

| Tema | Evidencia | Leitura |
|---|---|---|
| Handshake SSE publicado | `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiPatchStreamController.java:40`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiPatchStreamController.java:77`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiPatchStreamController.java:97`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiPatchStreamController.java:115` | Endpoints `start/stream/probe/cancel` existem e estao ativos no controller. |
| Last-Event-ID e ownership | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:122`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:126`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:134`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:138`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:162`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:177` | Replay valida escopo, trata `Last-Event-ID` invalido e expira stream com `410`. |
| Idempotencia de start + hash canonico | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java:57`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java:166`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java:171` | Reenvio por `clientTurnId` reaproveita stream quando hash bate. |
| Cancelamento terminal e convergencia de estado | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java:328`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java:349`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java:362`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:214` | Cancel gera terminal `cancelled` e estado terminal fica deterministico. |
| FE reducer SSE com dedupe/reorder | `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:2390`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:2403`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:2413` | FE ignora evento duplicado e fora de ordem por `eventId/seq`. |
| Semantica UX Detalhes vs Previa implementada e testada | `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:799`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:819`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:863`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:880`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts:1961`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts:1991` | FE separa acao de contexto (`Detalhes`) e diff completo (`Previa`) com guarda por terminal real de stream. |
| Streaming provider + fallback/cancel cooperativo | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1617`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1631`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1653`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/SpringAiOpenAiService.java:120`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/SpringAiOpenAiService.java:125`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/SpringAiGeminiService.java:164`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/SpringAiGeminiService.java:169`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiProviderStreamException.java:8` | BE possui caminho stream por provider, excecao tipada e fallback sync controlado por classe de erro. |
| Metricas P0 de stream publicadas | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java` (contador `ai_stream_fallback_total`), `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java` (contador `ai_stream_cancel_inflight_total` + histograma `ai_stream_duration_ms`), `praxis-config-starter/src/test/java/org/praxisplatform/config/service/AiOrchestratorServiceTextStreamingTest.java`, `praxis-config-starter/src/test/java/org/praxisplatform/config/service/AiStreamServiceTest.java` | Backlog F5-BE-01/02/03 agora tem implementacao e cobertura de teste unitario. |

### 2.2 Riscos estruturais relevantes (evidenciado)

| Risco | Evidencia | Impacto de producao |
|---|---|---|
| Core ainda acoplado em tabela | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:62`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1402`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:5376`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:5419` | Escalar para centenas de componentes aumenta risco de regressao e custo de manutencao por branching central. |
| Contrato FE/BE ainda dinamico | `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts:50`, `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts:51`, `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts:54`, `praxis-config-starter/src/main/java/org/praxisplatform/config/dto/AiOrchestratorRequest.java:31`, `praxis-config-starter/src/main/java/org/praxisplatform/config/dto/AiOrchestratorRequest.java:36` | Baixa garantida de schema para payloads de componente, reduzindo previsibilidade em ambiente corporativo. |
| Drift de contrato hash/version sem enforcement forte | `docs/ai/intent-contract-v1.1.md:262`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java:55`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java:63`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java:81` | FE e BE podem divergir em schema sem bloqueio deterministico de request. |
| IDs de documento RAG com escopo fraco e overwrite | `praxis-config-starter/src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java:42`, `praxis-config-starter/src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java:43`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/RegistryIngestionService.java:108`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/ApiMetadataIngestionService.java:278` | Colisao entre contextos de tenant/version pode degradar recall ou sobrescrever material indevidamente. |
| Retrieval com filtro tenant/env existe, mas nao e propagado de forma consistente pelo orquestrador | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java:63`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java:202`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:6085`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:6439`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:7258`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:9684` | Risco de retrieval menos isolado do que esperado em cenarios multi-tenant. |
| Pipeline de documentacao de componentes sem gate forte de schema/unicidade | `praxis-ui-angular/tools/ai-registry/extract-component-docs.js:141`, `praxis-ui-angular/tools/ai-registry/extract-component-docs.js:150`, `praxis-ui-angular/tools/ai-registry/component-docs.json:81`, `praxis-ui-angular/tools/ai-registry/component-docs.json:132`, `praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts:427`, `praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts:609` | Qualidade do catalogo para RAG pode variar e causar comportamento inconsistente do LLM. |

### 2.3 Itens prometidos e status de evidencia no codigo

| Item prometido | Onde foi prometido | Evidencia de implementacao | Status |
|---|---|---|---|
| `SCHEMA_HASH_MISMATCH` com HTTP 409 | `docs/ai/intent-contract-v1.1.md:262` | Enforcement implementado em `/patch` e `/patch/stream/start` com testes de controller (`AiOrchestratorControllerTest`, `AiPatchStreamControllerTest`). | Evidenciado |
| Metricas `ai_stream_fallback_total`, `ai_stream_cancel_inflight_total`, `ai_stream_duration_ms` | `praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md:28-30` | Implementadas no backend com cobertura: `AiOrchestratorService`, `AiStreamService`, `AiOrchestratorServiceTextStreamingTest`, `AiStreamServiceTest`. | Evidenciado |
| Artefatos SRE Fase 5 (dashboard/alertas/runbook) | `praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md:44-46` | Publicados em `docs/ai/observability/` e `docs/ai/runbooks/fase5-canary-rollback.md`. | Evidenciado |
| Artefatos QA de assertividade (`docs/ai/fase5-test-matrix.md`, `docs/ai/fase5-llm-report.md`) | `praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md:41-42` | Publicados com execucao real 12x3 e scorecard versionado. | Evidenciado (gate FAIL) |

## 3) Baseline detalhado por fase (A -> 5)

### 3.1 Fase A - Contrato e seguranca

Prometido
- Contrato SSE testavel e semantica HTTP fechada.
- Identidade server-side obrigatoria.
- Logging/redaction por default seguro.

Evidenciado
- Handshake e controle de stream no controller (`praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiPatchStreamController.java:40-142`).
- Resolucao de principal no servidor (`praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiPatchStreamController.java:60-64`).
- Redaction na serializacao/replay de evento (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:264-278`).
- Tests de contrato de controller e integracao SSE (`praxis-config-starter/src/test/java/org/praxisplatform/config/controller/AiPatchStreamControllerTest.java:106-224`, `praxis-config-starter/src/test/java/org/praxisplatform/config/controller/AiPatchStreamHttpSseIntegrationTest.java:229-251`).
- Suite versionada `v1.1` para boundary de entrada/saida/SSE (`praxis-config-starter/src/test/java/org/praxisplatform/config/contract/AiContractV11RetroCompatibilityTest.java`, `praxis-config-starter/docs/ai/contract-v11-retro-compat-report.md`).

Lacunas
- Suite retro `v1.1` agora esta versionada e automatizada no CI local; prova adicional em host real segue recomendada para hardening operacional.

### 3.2 Fase B - Integridade transacional, replay, idempotencia

Prometido
- Event store com replay confiavel.
- Idempotencia por chave deterministica.
- Terminalidade e cancelamento consistentes.

Evidenciado
- Append transacional com lock e sequencia monotona (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:41-112`).
- Replay com validacao de escopo e erro deterministico para `Last-Event-ID` (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:122-147`).
- Terminalidade definida (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java:214-220`).
- Idempotencia de start com hash canonico (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java:57-83`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java:171-226`).

Lacunas
- Validacao distribuidade real (2+ nos + LB) permanece bloqueada por ambiente, conforme backlog (`praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md:47-53`).

### 3.3 Fase C - Timeline/reducer/eventos reais FE/BE

Prometido
- FE priorizar eventos reais de stream.
- Reducer idempotente para dedupe/reorder/foreign.

Evidenciado
- Guardas de schema de stream no FE (`praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:2268-2275`).
- Reducer com dedupe por `eventId` e ordem por `seq` (`praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts:2403-2423`).
- Cobertura de cenarios de fallback e stream reducer (`praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts:294`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts:370`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts:509`, `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts:689`).

Lacunas
- Tipagem do payload de evento ainda generica em FE (`praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts:140`).

### 3.4 Fase 4 - Streaming provider + fallback/cancel

Prometido
- Streaming provider opt-in, fallback sync deterministico e cancelamento cooperativo.

Evidenciado
- Chave opt-in e fallback controlado no orquestrador (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1617-1637`).
- Classificacao de erro tipada para fallback (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1653-1657`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiProviderStreamException.java:8-80`).
- Bridge de cancelamento thread-local (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamExecutionContextHolder.java:21-79`).
- OpenAI e Gemini declaram `supportsTextStreaming` e `supportsTurnCancellation` (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/SpringAiOpenAiService.java:120-127`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/SpringAiGeminiService.java:164-171`).

Lacunas
- Fallback ainda inclui heuristica por mensagem de erro alem da excecao tipada (`praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1666-1676`).

### 3.5 Fase 5 - Hardening/canary/readiness

Prometido
- Metricas, correlacao de logs, smoke unico, scorecard, runbook.

Evidenciado
- Backlog com DoD e ordem de execucao publicado (`praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md:15-73`).
- Artefatos SRE publicados: dashboard (`docs/ai/observability/fase5-dashboard-minimo.grafana.json`), alertas (`docs/ai/observability/fase5-alert-rules.prometheus.yml`) e runbook (`docs/ai/runbooks/fase5-canary-rollback.md`).

Lacunas
- Scorecard/matriz de assertividade de canario ainda nao comprovados por artefatos QA versionados.

## 4) Diagnostico aprofundado: FE component docs + RAG para escala corporativa

### 4.1 Maturidade atual do padrao FE -> catalogo -> RAG

Forca
- Existe pipeline de extração e ingestao (`praxis-ui-angular/tools/ai-registry/extract-component-docs.js`, `praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts`).

Risco
- Extrator gera snapshot sem gate de schema/unicidade (`praxis-ui-angular/tools/ai-registry/extract-component-docs.js:141-151`).
- Duplicidade de ID ja existe no snapshot (`praxis-ui-angular/tools/ai-registry/component-docs.json:81`, `praxis-ui-angular/tools/ai-registry/component-docs.json:132`).
- Ingestion sobrescreve entradas duplicadas pelo map final (`praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts:609`).
- Deteccao automatica de schema e restrita a poucos tipos (`praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts:427-435`).
- Guia atual de tooling e minimo e focado em execucao, nao em governanca de qualidade (`praxis-ui-angular/tools/ai-registry/README.md:1-44`).

### 4.2 Medicao objetiva do snapshot (2026-02-22)

Comando executado
```bash
node - <<'NODE'
const fs=require('fs');
const docs=JSON.parse(fs.readFileSync('praxis-ui-angular/tools/ai-registry/component-docs.json','utf8'));
const ids=docs.components.map(c=>c.id).filter(Boolean);
const dup=[...new Set(ids.filter((id,i)=>ids.indexOf(id)!==i))];
const reg=JSON.parse(fs.readFileSync('praxis-ui-angular/dist/praxis-component-registry-ingestion.json','utf8'));
const list=Object.entries(reg.components||{});
let withCtx=0,withCompCaps=0,withSchema=0,withCaps=0,withAiConcepts=0;
for (const [,v] of list){
  if(v && v.componentContext) withCtx++;
  if(v && Array.isArray(v.componentCapabilities) && v.componentCapabilities.length>0) withCompCaps++;
  if(v && v.configSchemaId) withSchema++;
  if(v && Array.isArray(v.capabilities) && v.capabilities.length>0) withCaps++;
  if(v && v.aiConcepts) withAiConcepts++;
}
console.log({docsCount:docs.count,uniqueIds:new Set(ids).size,duplicateIds:dup,components:list.length,withCtx,withCompCaps,withSchema,withCaps,withAiConcepts});
NODE
```

Resultado sintetico
- `component-docs`: 66 registros, 65 IDs unicos, duplicado `pdx-cron-builder`.
- `registry-ingestion`: 65 componentes efetivos.
- Cobertura estruturada ainda baixa para escala corporativa:
  - `componentContext`: 18/65.
  - `componentCapabilities`: 16/65.
  - `configSchemaId`: 6/65.
  - `capabilities`: 6/65.
  - `aiConcepts`: 1/65.

Leitura
- O pipeline atual e funcional, mas ainda nao esta maduro para sustentar centenas de componentes com previsibilidade corporativa sem gates formais.

## 5) Alvo corporativo (estado desejado)

### 5.1 Principios inegociaveis

1. Orquestrador generico por plugin (`ModuleRegistry`), sem `if` por componente no core.
2. Contrato schema-first versionado (envelope estavel + payload por `target.kind`).
3. Geracao constrangida (plan/patch tipado + validacao semantica deterministica antes de apply).
4. Politica e isolamento server-side (tenant/user/role) antes de planejar e antes de aplicar.
5. Catalogo e releases imutaveis com `schemaHash`, owner e trilha de auditoria.
6. RAG com IDs deterministicas e escopo forte (`tenant/component/release/docType/contentHash/chunk`).
7. CI/CD com gates bloqueantes: schema, dedupe, policy, eval score, regressao.
8. Observabilidade e runbook como requisito de rollout, nao opcional.

### 5.2 Arquitetura alvo resumida

| Camada | Responsabilidade | Resultado esperado |
|---|---|---|
| `ai-platform-core` | Gateway versionado, orchestrator generico, policy, auditoria, idempotencia e cancel coordinator | Escala horizontal de capacidade sem acoplamento por componente |
| `ai-component-modules` | Modulos por familia (`table`, `form`, `crud`, `dashboard`) com planner/compiler/validator proprios | Evolucao independente por squad sem regressao central |
| `ai-catalog-service` | Release imutavel de componente/prompt/schema/exemplos | Governanca e rastreabilidade por versao |
| `ai-rag-service` | Ingestao deterministica, dedupe por hash, retrieval com escopo forte | Recall estavel e isolamento corporativo |
| `@praxis/ai-core` (FE) | Tipos gerados do contrato, client versionado, preview/apply generico | FE desacoplado de payload dinamico |
| `@praxis/ai-modules` (FE) | Adapter por componente para preview/aplicacao local | Escala para centenas de componentes mantendo UX consistente |

## 6) Lacunas para aprovacao corporativa (priorizadas)

| Severidade | Lacuna | Evidencia | Risco de producao | Correcao recomendada |
|---|---|---|---|---|
| Critico | Acoplamento do core em tabela | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:62`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1402` | Regressao ampla ao adicionar novos componentes | Extrair `ComponentAiModule` + `ModuleRegistry` e mover regras de tabela para modulo dedicado |
| Critico | Contrato de patch/contexto dinamico no FE/BE | `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts:50-55`, `praxis-config-starter/src/main/java/org/praxisplatform/config/dto/AiOrchestratorRequest.java:31-36` | Falhas semanticas e baixa previsibilidade em escala | Adotar envelope tipado versionado + codegen TS/Java + validacao obrigatoria |
| Baixo | Compatibilidade retroativa de contrato ainda sem rodada host real (suite `v1.1` local ja versionada) | `docs/ai/intent-contract-v1.1.md:262`, `praxis-config-starter/src/test/java/org/praxisplatform/config/contract/AiContractV11RetroCompatibilityTest.java`, `praxis-config-starter/docs/ai/contract-v11-retro-compat-report.md` | Regressao residual ligada a comportamento de infraestrutura de host/proxy | Manter suite `v1.1` bloqueante no CI e executar rodada host real por release de contrato |
| Alto | RAG upsert com delete/add por ID sem release scope forte | `praxis-config-starter/src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java:42-43`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/RegistryIngestionService.java:108` | Colisoes e perda de contexto em multi-tenant/multi-release | Mudar chave de documento/chunk para escopo composto e upsert por `contentHash` |
| Alto | Retrieval tenant/env nao propagado de forma uniforme | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java:63-65`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:6085` | Possivel mistura de contexto e menor isolamento | Passar `tenantId/environment` em toda chamada de retrieval no orquestrador |
| Medio | Pipeline de catalogo FE com gate ativo, mas com warnings de completude sem bloqueio | `../praxis-ui-angular/tools/ai-registry/schemas/component-docs.schema.json`, `../praxis-ui-angular/tools/ai-registry/schemas/praxis-component-registry-ingestion.schema.json`, `../praxis-ui-angular/tools/ai-registry/validate-catalog-governance.js`, `../praxis-ui-angular/dist/ai-registry-catalog-validation-report.json` | Qualidade minima esta protegida (erro bloqueia CI), mas lacunas de descricao/cobertura podem degradar contexto do LLM | Evoluir gate para promover warnings criticos (descricao/campos minimos) a erro por etapa |
| Baixo | Scorecard de assertividade da Fase 5 depende de reexecucao controlada para manter baseline confiavel | `praxis-config-starter/docs/ai/fase5-llm-report.md`, `artifacts/ai-llm-assertiveness/20260222-040050/summary.json` | Sem regressao continua no gate, ganhos podem se perder sem rotina de revalidacao | Manter execucao periodica da mesma matriz 12x3 como gate de regressao |
| Medio | Fallback por texto de erro ainda presente | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1666-1676` | Classificacao instavel em mudanca de provider | Priorizar uso de `AiProviderStreamException.Kind` e reduzir heuristica textual |

## 7) Plano de ajuste sem big bang (ordem de execucao)

1. Implementar gateway de contrato v1 com schema validation obrigatoria e `409` para hash mismatch.
2. Introduzir `ModuleRegistry` e mover logica de tabela para `TableAiModule`, sem alterar endpoint externo.
3. Definir interfaces `Planner/Compiler/Validator` por modulo; manter `Compiler/Validator` deterministas.
4. Tipar FE com codegen do contrato v1 e remover `Record<string, any>` dos payloads criticos.
5. Criar schema formal para `component-docs.json` e gate de unicidade de `componentId`.
6. Substituir mapeamentos manuais por manifesto versionado de capacidade/contexto por componente.
7. Reestruturar IDs de RAG para escopo composto com `releaseId` e `contentHash`; habilitar upsert deterministico.
8. Propagar `tenantId/environment` em todas as chamadas de retrieval do orquestrador.
9. Criar suite de evals por componente com score minimo de liberacao (exemplo: `>=0.85`).
10. Entregar metricas Fase 5 (`ai_stream_*`) + dashboards + alertas + runbook e evidencias versionadas.
11. Executar canario controlado (1-2 tenants, 5% trafego, rollback por tenant/modulo).
12. Quando ambiente permitir, validar matriz distribuida (2+ nos + LB) e remover bloqueios de ambiente.

## 8) Checklist de aceite corporativo atualizado

- [x] Contrato v1 validado forte no BE e tipado no FE.
- [x] `SCHEMA_HASH_MISMATCH` com `409` no `/patch` e no `/patch/stream/start`, com testes automatizados.
- [x] Suite retro versionada do contrato `v1.1` cobrindo `/patch`, `/patch/stream/start` e envelope SSE.
- [ ] Idempotencia/replay/cancel deterministicos com evidencias de integracao.
- [x] Propagacao de `tenantId/environment` aplicada aos pontos de retrieval do orquestrador (`searchApiMetadata`).
- [x] Catalogo FE sem IDs duplicados e com schema/DoD no CI (gate `validate:catalog` com `errors=0`).
- [x] RAG com IDs escopadas por release e dedupe por hash.
- [x] Retrieval RAG isolado por `releaseId` com fallback controlado (`releaseId` -> `version`) e prova automatizada.
- [ ] Semantica UX `Detalhes` vs `Previa` preservada e coberta por testes.
- [x] Metricas P0 da Fase 5 publicadas (`ai_stream_fallback_total`, `ai_stream_cancel_inflight_total`, `ai_stream_duration_ms`) com testes.
- [x] Correlacao de logs de stream (`requestId/streamId/threadId/turnId`) instrumentada de `start` ate evento terminal.
- [x] Reconciliacao terminal tecnica com `reason_code` deterministico (`legacy_orphan_tail`) em payload de evento.
- [x] Smoke SSE unico (`start/probe/stream/replay/cancel`) executado em host real com artefato markdown versionado.
- [x] Gate de hardening corporativo single-node executado com identidade server-side, anti-bypass, mismatch de contrato (`409`), guardrails e estabilidade SSE repetida.
- [x] Dashboard/alertas/runbook da Fase 5 publicados e validados.
- [x] Scorecard/matriz de assertividade da Fase 5 publicados e validados (gate PASS na rodada `20260222-040050`).
- [ ] Itens distribuidos executados quando ambiente multi-node estiver disponivel.

Observacao
- Enforcement de contrato em `POST /api/praxis/config/ai/patch/stream/start` ja implementado e validado por teste de controller.
- Boundary retro `v1.1` (`entrada/saida/SSE`) consolidado na suite `AiContractV11RetroCompatibilityTest` com relatorio versionado em `docs/ai/contract-v11-retro-compat-report.md`.
- Gate de catalogo FE (B-01/B-02) esta ativo no pipeline: `generate:registry:ingestion` agora executa `validate:catalog` e `ci:verify` inicia por esse gate.

### 8.1 Status do plano executavel original (12 tasks)

| Task | Status | Evidencia |
|---|---|---|
| A-01 | Concluida | `docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml`, `src/test/java/org/praxisplatform/config/contract/AiApiContractOpenApiTest.java` |
| A-02 | Concluida | `tools/contracts/generate-ai-contract-bindings.js`, `src/main/java/org/praxisplatform/config/contract/AiContractSpec.java`, `../praxis-ui-angular/projects/praxis-ai/src/lib/core/contracts/ai-contract.generated.ts` |
| A-03 | Concluida | `../praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts`, `../praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts` |
| A-04 | Concluida | `src/test/java/org/praxisplatform/config/contract/AiContractV11RetroCompatibilityTest.java`, `docs/ai/contract-v11-retro-compat-report.md` |
| A-05 | Concluida | `src/test/java/org/praxisplatform/config/contract/AiContractV11RetroCompatibilityTest.java`, `docs/ai/contracts/README.md` |
| B-01 | Concluida | `../praxis-ui-angular/tools/ai-registry/schemas/component-docs.schema.json`, `../praxis-ui-angular/tools/ai-registry/schemas/praxis-component-registry-ingestion.schema.json`, `../praxis-ui-angular/tools/ai-registry/validate-catalog-governance.js` |
| B-02 | Concluida | `../praxis-ui-angular/package.json`, `../praxis-ui-angular/dist/ai-registry-catalog-validation-report.json` |
| C-01 | Concluida | `src/main/resources/db/migration/V16__harden_vector_store_release_hash_uniques.sql`, `src/main/java/org/praxisplatform/config/rag/RagDocumentIdentity.java`, `src/main/java/org/praxisplatform/config/service/RegistryIngestionService.java`, `src/main/java/org/praxisplatform/config/service/ApiMetadataIngestionService.java` |
| C-02 | Concluida | `src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java`, `src/test/java/org/praxisplatform/config/rag/RagVectorStoreServiceTest.java`, `src/test/java/org/praxisplatform/config/service/ApiMetadataIngestionServiceTest.java`, `src/test/java/org/praxisplatform/config/service/RegistryIngestionServiceIdentityTest.java` |
| C-03 | Concluida | `src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java`, `src/main/java/org/praxisplatform/config/rag/RagFilters.java`, `src/main/java/org/praxisplatform/config/controller/ContextRetrievalController.java`, `src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java`, `src/main/java/org/praxisplatform/config/dto/AiOrchestratorRequest.java` |
| C-04 | Concluida | `src/test/java/org/praxisplatform/config/service/ContextRetrievalServiceTest.java`, `src/test/java/org/praxisplatform/config/controller/AiOrchestratorControllerTest.java`, `src/test/java/org/praxisplatform/config/controller/AiPatchStreamControllerTest.java` |
| D-01 | Pendente (bloqueio de ambiente) | Smoke single-node evidenciado em perfil local e corporativo com autenticacao (`artifacts/ai-rag-tests/20260222-201327/summary.txt`, `artifacts/ai-rag-tests/20260222-202733/summary.txt`, `artifacts/ai-sse-smoke/20260222-202836/summary.md`) e gate corporativo dedicado (`artifacts/ai-corporate-hardening/20260222-211721/summary.md`, `artifacts/ai-corporate-hardening/20260222-211931/summary.md`), porem ambiente distribuido real 2+ nos com LB ainda nao disponivel. |

## 9) Protocolo de atualizacao de memoria (obrigatorio)

1. Toda PR que altere contrato, evento SSE, pipeline de catalogo, RAG ou operacao deve atualizar este arquivo.
2. Toda entrada nova deve conter:
   - data;
   - mudanca;
   - evidencia `arquivo:linha`;
   - impacto em risco;
   - status (`evidenciado`, `parcial`, `nao evidenciado`).
3. Nao remover historico; apenas append em log.

### 9.1 Template de update rapido

```text
Data:
Mudanca:
Fase afetada (A/B/C/4/5):
Evidencia:
Impacto:
Status:
```

### 9.2 Prompt curto para bootstrap de contexto em nova sessao

```text
Leia primeiro:
1) praxis-config-starter/docs/ai/p1-backend/P1-BE-8-memoria-programa-baseline-alvo.md
2) praxis-config-starter/docs/ai/p1-backend/P1-BE-6A-backlog-fase-a-contrato-seguranca.md
3) praxis-config-starter/docs/ai/p1-backend/P1-BE-6-streaming-robusto-e-escalavel.md
4) praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md

Objetivo:
- confirmar estado atual com evidencias de codigo;
- apontar lacunas criticas para canario corporativo;
- atualizar checklist e plano de ajuste sem big bang.
```

## 10) Log de atualizacoes (append-only)

| Data | Mudanca | Evidencia | Status |
|---|---|---|---|
| 2026-02-22 | Criacao da memoria baseline->alvo com diagnostico FE/RAG e lacunas de canario corporativo | Este arquivo + referencias de codigo citadas nas secoes 2-7 | Evidenciado |
| 2026-02-22 | Enforced `409` para mismatch de contrato no `/patch`, propagado `tenant/env` nos retrievals do orquestrador e hardened ingest de metadata nula no RAG | `AiOrchestratorController`, `AiOrchestratorService`, `ApiMetadataIngestionService` + testes (`AiOrchestratorControllerTest`, `ApiMetadataIngestionServiceTest`) | Evidenciado |
| 2026-02-22 | Enforced `409` de contrato tambem no `POST /patch/stream/start` (`UNSUPPORTED_CONTRACT` e `SCHEMA_HASH_MISMATCH`) | `AiPatchStreamController` + `AiPatchStreamControllerTest` | Evidenciado |
| 2026-02-22 | Implementadas metricas P0 de Fase 5 (`ai_stream_fallback_total`, `ai_stream_cancel_inflight_total`, `ai_stream_duration_ms`) com testes unitarios | `AiOrchestratorService`, `AiStreamService`, `AiOrchestratorServiceTextStreamingTest`, `AiStreamServiceTest` | Evidenciado |
| 2026-02-22 | Correlacao de logs por IDs de fluxo (`requestId/streamId/threadId/turnId`) no ciclo `start/connect/cancel/process/terminal`, com propagacao de `requestId` no stream controller e contexto assincrono no worker | `AiPatchStreamController`, `AiStreamService`, `AiPatchStreamControllerTest`, `AiStreamServiceTest` | Evidenciado |
| 2026-02-22 | Reconciliacao de cauda legada agora publica `reason_code` deterministico (`legacy_orphan_tail`) no terminal tecnico (`error`/`cancelled`) | `AiStreamService` + `AiStreamServiceTest` | Evidenciado |
| 2026-02-22 | F5-BE-06 executado em host real (`quickstart`) com fluxo SSE completo e artefato markdown versionado | `scripts/ai/e2e-sse-smoke.sh`, `docs/ai/fase5-sse-smoke-report.md`, `artifacts/ai-sse-smoke/20260221-232941/summary.md` | Evidenciado |
| 2026-02-22 | F5-SRE-01..03 publicados com artefatos versionados (dashboard, regras de alerta, runbook) | `docs/ai/observability/fase5-dashboard-minimo.grafana.json`, `docs/ai/observability/fase5-alert-rules.prometheus.yml`, `docs/ai/runbooks/fase5-canary-rollback.md` | Evidenciado |
| 2026-02-22 | F5-QA-01/F5-QA-02 executados no host real com matriz 12x3 e scorecard versionado; gate de assertividade ficou FAIL (`overallScoreMean=2.4`) | `docs/ai/fase5-test-matrix.md`, `docs/ai/fase5-llm-report.md`, `artifacts/ai-llm-assertiveness/20260222-000358/summary.json` | Evidenciado (nao aprovado para canary) |
| 2026-02-22 | Reexecucao pos hardening + reinstall do starter elevou score medio para `4.2`, porem gate ainda FAIL por casos residuais (`ambiguous-computed-missing-base`, `direct-density-compact-stream`, `risk-invalid-density`) | `docs/ai/fase5-llm-report.md`, `artifacts/ai-llm-assertiveness/20260222-034344/summary.json` | Evidenciado (nao aprovado para canary) |
| 2026-02-22 | Reexecucao apos correcoes no orquestrador fechou os casos residuais e aprovou o gate da matriz 12x3 (`overallScoreMean=5.0`, `overallPassRate=1.0`) | `docs/ai/fase5-llm-report.md`, `artifacts/ai-llm-assertiveness/20260222-040050/summary.json` | Evidenciado (QA assertividade aprovado) |
| 2026-02-22 | Inicio da Trilha A (A-01/A-02): OpenAPI v1.1 unica para `/patch` e `/patch/stream/*` criada e validada por teste automatizado | `docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml`, `src/test/java/org/praxisplatform/config/contract/AiApiContractOpenApiTest.java` | Evidenciado (base pronta para codegen Java/TS) |
| 2026-02-22 | Trilha A (A-02) avancou com geracao automatizada de bindings Java/TS a partir do OpenAPI e eliminacao de drift nas constantes de contrato no backend/frontend | `tools/contracts/generate-ai-contract-bindings.js`, `src/main/java/org/praxisplatform/config/contract/AiContractSpec.java`, `src/test/java/org/praxisplatform/config/contract/AiContractSpecConsistencyTest.java`, `../praxis-ui-angular/projects/praxis-ai/src/lib/core/contracts/ai-contract.generated.ts`, `../praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts` | Evidenciado (drift guard ativo para backend, TS alinhado no monorepo) |
| 2026-02-22 | Trilha A (A-03) expandida no FE com tipagem contratual em `ai-assistant` (estado de patch/diff + stream specs), reduzindo `any` nos pontos de fluxo SSE | `../praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts`, `../praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts` | Evidenciado (tipagem aplicada; execucao FE ainda bloqueada por DNS/feed no ambiente) |
| 2026-02-22 | Trilha A (A-03) endurecida com validacao de `eventSchemaVersion` no boundary SSE + spec dedicada de incompatibilidade e eliminacao do `any` residual no `ai-assistant` | `../praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts`, `../praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.spec.ts`, `../praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts` | Evidenciado (codigo atualizado; execucao FE bloqueada por DNS/feed no ambiente) |
| 2026-02-22 | F5-QA-03 reexecutado no runner WSL apos liberacao de DNS; suites FE SSE (`ai-backend-api` e `ai-assistant`) passaram com `TOTAL: 18 SUCCESS` e `TOTAL: 61 SUCCESS` | `docs/ai/fase5-fe-sse-regression-report.md`, `../praxis-ui-angular/projects/praxis-ai/tsconfig.spec.json`, `../praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts` | Evidenciado (gate FE SSE aprovado nesta rodada) |
| 2026-02-22 | Trilha A concluida em `A-04/A-05`: boundary contratual completo (`entrada/saida/SSE`) e suite retro versionada `v1.1` com execucao automatizada | `src/test/java/org/praxisplatform/config/contract/AiContractV11RetroCompatibilityTest.java`, `docs/ai/contract-v11-retro-compat-report.md`, `docs/ai/contracts/README.md` | Evidenciado (suite v1.1 PASS: 6/6) |
| 2026-02-22 | Trilha B concluida em `B-01/B-02`: schemas formais do catalogo FE, validador de governanca com relatorio versionado e gate bloqueante acoplado ao `ci:verify` | `../praxis-ui-angular/tools/ai-registry/schemas/component-docs.schema.json`, `../praxis-ui-angular/tools/ai-registry/schemas/praxis-component-registry-ingestion.schema.json`, `../praxis-ui-angular/tools/ai-registry/validate-catalog-governance.js`, `../praxis-ui-angular/package.json`, `../praxis-ui-angular/dist/ai-registry-catalog-validation-report.json` | Evidenciado (status `PASS`, `errors=0`, `warnings=93`) |
| 2026-02-22 | Trilha C avancou com `C-01/C-02`: IDs RAG escopadas por `tenant/environment/release/component/docType/contentHash/chunk`, migration de dedupe + indice unico composto e upsert deterministico com dedupe por hash | `src/main/resources/db/migration/V16__harden_vector_store_release_hash_uniques.sql`, `src/main/java/org/praxisplatform/config/rag/RagDocumentIdentity.java`, `src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java`, `src/main/java/org/praxisplatform/config/service/RegistryIngestionService.java`, `src/main/java/org/praxisplatform/config/service/ApiMetadataIngestionService.java`, `src/test/java/org/praxisplatform/config/rag/RagVectorStoreServiceTest.java`, `src/test/java/org/praxisplatform/config/service/ApiMetadataIngestionServiceTest.java`, `src/test/java/org/praxisplatform/config/service/RegistryIngestionServiceIdentityTest.java` | Evidenciado (testes alvo PASS: 6/6) |
| 2026-02-22 | Trilha C concluida em `C-03/C-04`: retrieval RAG por release ficou obrigatorio com fallback controlado (`releaseId` -> `version`) e prova automatizada de isolamento entre releases | `src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java`, `src/main/java/org/praxisplatform/config/rag/RagFilters.java`, `src/main/java/org/praxisplatform/config/controller/ContextRetrievalController.java`, `src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java`, `src/main/java/org/praxisplatform/config/dto/AiOrchestratorRequest.java`, `src/test/java/org/praxisplatform/config/service/ContextRetrievalServiceTest.java` | Evidenciado (suite focal PASS: 25/25) |
| 2026-02-22 | Correcao operacional da Trilha C: migration `V16` ajustada para SQL compativel no `CREATE UNIQUE INDEX` (expressao `CASE` parentizada), destravando boot/Flyway no host `quickstart` | `src/main/resources/db/migration/V16__harden_vector_store_release_hash_uniques.sql`, `praxis-api-quickstart` startup log (Flyway `v16` aplicado com sucesso) | Evidenciado |
| 2026-02-22 | E2E RAG/SSE reexecutado no host real: cenario default corporativo manteve bloqueio de identidade (`PASS=3 FAIL=6`, `403 Corporate identity required`); cenario local de diagnostico (`PRAXIS_AI_SECURITY_CORPORATE_MODE=false`) confirmou contrato (`RAG PASS=9 FAIL=0` + `SSE smoke completed successfully`) | `artifacts/ai-rag-tests/20260222-200803/summary.txt`, `artifacts/ai-rag-tests/20260222-201327/summary.txt`, `artifacts/ai-sse-smoke/20260222-201555/summary.md`, `src/main/java/org/praxisplatform/config/service/AiPrincipalContextResolver.java` | Evidenciado (D-01 continua pendente por identidade corporativa server-side + ambiente distribuido) |
| 2026-02-22 | Bootstrap de autenticacao incorporado aos smokes (`AUTH_LOGIN_URL`, `AUTH_USERNAME`, `AUTH_PASSWORD`, `AUTH_COOKIE_NAME`) e reexecucao em `corporate-mode=true` com principal server-side + tenant corporativo default validou contrato (`RAG PASS=9`, `SSE OK`) | `scripts/ai/e2e-rag-smoke.sh`, `scripts/ai/e2e-sse-smoke.sh`, `artifacts/ai-rag-tests/20260222-202733/summary.txt`, `artifacts/ai-sse-smoke/20260222-202836/summary.md` | Evidenciado (permanece pendente apenas eixo multi-node/LB de D-01) |
| 2026-02-23 | Gate corporativo dedicado de hardening single-node executado com `11/11 PASS` (health, identity gate, anti-bypass, contract mismatch `409`, guardrails e estabilidade SSE repetida) | `scripts/ai/e2e-corporate-hardening.sh`, `artifacts/ai-corporate-hardening/20260222-211721/summary.json`, `artifacts/ai-corporate-hardening/20260222-211721/summary.md`, `artifacts/ai-corporate-hardening/20260222-211931/summary.md` | Evidenciado (D-01 continua pendente apenas por ambiente distribuido 2+ nos + LB) |
