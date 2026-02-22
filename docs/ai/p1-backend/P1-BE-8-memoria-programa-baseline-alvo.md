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

### 2.2 Riscos estruturais relevantes (evidenciado)

| Risco | Evidencia | Impacto de producao |
|---|---|---|
| Core ainda acoplado em tabela | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:62`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:1402`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:5376`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:5419` | Escalar para centenas de componentes aumenta risco de regressao e custo de manutencao por branching central. |
| Contrato FE/BE ainda dinamico | `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts:50`, `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts:51`, `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts:54`, `praxis-config-starter/src/main/java/org/praxisplatform/config/dto/AiOrchestratorRequest.java:31`, `praxis-config-starter/src/main/java/org/praxisplatform/config/dto/AiOrchestratorRequest.java:36` | Baixa garantida de schema para payloads de componente, reduzindo previsibilidade em ambiente corporativo. |
| Drift de contrato hash/version sem enforcement forte | `docs/ai/intent-contract-v1.1.md:262`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java:55`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java:63`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java:81` | FE e BE podem divergir em schema sem bloqueio deterministico de request. |
| IDs de documento RAG com escopo fraco e overwrite | `praxis-config-starter/src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java:42`, `praxis-config-starter/src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java:43`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/RegistryIngestionService.java:108`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/ApiMetadataIngestionService.java:278` | Colisao entre contextos de tenant/version pode degradar recall ou sobrescrever material indevidamente. |
| Retrieval com filtro tenant/env existe, mas nao e propagado de forma consistente pelo orquestrador | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java:63`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java:202`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:6085`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:6439`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:7258`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:9684` | Risco de retrieval menos isolado do que esperado em cenarios multi-tenant. |
| Pipeline de documentacao de componentes sem gate forte de schema/unicidade | `praxis-ui-angular/tools/ai-registry/extract-component-docs.js:141`, `praxis-ui-angular/tools/ai-registry/extract-component-docs.js:150`, `praxis-ui-angular/tools/ai-registry/component-docs.json:81`, `praxis-ui-angular/tools/ai-registry/component-docs.json:132`, `praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts:427`, `praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts:609` | Qualidade do catalogo para RAG pode variar e causar comportamento inconsistente do LLM. |

### 2.3 Itens prometidos e ainda nao evidenciados no codigo

| Item prometido | Onde foi prometido | Evidencia de implementacao | Status |
|---|---|---|---|
| `SCHEMA_HASH_MISMATCH` com HTTP 409 | `docs/ai/intent-contract-v1.1.md:262` | Nao encontrado tratamento explicito no controller/service (`praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java:55-88`). | Nao evidenciado |
| Metricas `ai_stream_fallback_total`, `ai_stream_cancel_inflight_total`, `ai_stream_duration_ms` | `praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md:28-30` | Busca textual encontrou apenas backlog/documentacao. | Nao evidenciado |
| Artefatos operacionais Fase 5 (`docs/ai/fase5-test-matrix.md`, `docs/ai/fase5-llm-report.md`, `docs/ai/runbooks/fase5-canary-rollback.md`) | `praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md:39-45` | Referenciados no backlog, sem evidencia de conteudo consolidado neste corte. | Nao evidenciado |

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

Lacunas
- Enforcement forte de hash/versao de contrato de intents ainda nao comprovado.

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

Lacunas
- Artefatos e implementacoes de Fase 5 acima ainda nao comprovados por codigo/arquivos de entrega nesta data.

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
| Critico | `SCHEMA_HASH_MISMATCH` nao comprovado | `docs/ai/intent-contract-v1.1.md:262`, `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java:55-88` | Drift silencioso FE/BE | Implementar check de hash/versao com `409` deterministico e testes de compatibilidade |
| Alto | RAG upsert com delete/add por ID sem release scope forte | `praxis-config-starter/src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java:42-43`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/RegistryIngestionService.java:108` | Colisoes e perda de contexto em multi-tenant/multi-release | Mudar chave de documento/chunk para escopo composto e upsert por `contentHash` |
| Alto | Retrieval tenant/env nao propagado de forma uniforme | `praxis-config-starter/src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java:63-65`, `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java:6085` | Possivel mistura de contexto e menor isolamento | Passar `tenantId/environment` em toda chamada de retrieval no orquestrador |
| Alto | Pipeline de catalogo FE sem gates de governanca | `praxis-ui-angular/tools/ai-registry/extract-component-docs.js:141-151`, `praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts:609` | Catalogo inconsistente e comportamento irregular do LLM | Criar schemas formais, lint de duplicidade e cobertura minima por componente no CI |
| Medio | Fase 5 operacional ainda nao comprovada por artefatos | `praxis-config-starter/docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md:17-23` | Canary sem observabilidade completa | Entregar metricas, dashboards, alertas e runbook versionados antes de promote |
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

- [ ] Contrato v1 validado forte no BE e tipado no FE.
- [ ] `SCHEMA_HASH_MISMATCH` com `409` e testes automatizados.
- [ ] Idempotencia/replay/cancel deterministicos com evidencias de integracao.
- [ ] Isolamento tenant/user comprovado no retrieval e no apply.
- [ ] Catalogo FE sem IDs duplicados e com schema/DoD no CI.
- [ ] RAG com IDs escopadas por release e dedupe por hash.
- [ ] Semantica UX `Detalhes` vs `Previa` preservada e coberta por testes.
- [ ] Metricas/alertas/runbook de Fase 5 publicados e validados.
- [ ] Itens distribuidos executados quando ambiente multi-node estiver disponivel.

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
