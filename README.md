# Praxis Config Starter

![Version](https://img.shields.io/badge/version-0.0.9--SNAPSHOT-blue)
![Maven Central](https://img.shields.io/maven-central/v/io.github.codexrodrigues/praxis-config-starter?logo=apachemaven&color=blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2%2B-brightgreen)
![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL%20Vector-blue)

The **Dynamic Configuration Kernel** of the Praxis ecosystem. This starter provides persistence, versioning, and semantic intelligence (vector-ready) for API metadata and UI configurations.

## 🏗 Architecture

This module attaches to your Spring Boot application and automatically manages:
1.  **Metadata Tables** (`api_metadata`, `ai_registry`).
2.  **Runtime UI Config** (`ui_user_config`) for tenant/user overrides.
3.  **REST Endpoints** for context management and ingestion.

```mermaid
graph TD
    A[Dev/CI Pipeline] -->|1. Compile & Extract| B(Praxis Metadata Starter)
    B -->|2. Generate JSON/Swagger| C{Host App (e.g. API Quickstart)}
    C -->|3. Internal Ingestion| D[Praxis Config Starter]
    D -->|4. Save & Vectorize| E[(PostgreSQL + Vector)]
    F[Praxis UI Angular] -->|5. Request Config| C
    C -->|6. Return Dynamic Layout| F
```

## 🚀 Installation

Add the dependency to your Host Application's `pom.xml` (e.g., `praxis-api-quickstart`):

```xml
<dependency>
    <groupId>io.github.codexrodrigues</groupId>
    <artifactId>praxis-config-starter</artifactId>
    <version>0.0.9-SNAPSHOT</version>
</dependency>
```

### Infrastructure Requirements
*   **PostgreSQL 14+**
*   **pgvector extension** enabled in the database.

## ⚙️ Configuration

In your `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/praxis_db
    username: user
    password: password
  jpa:
    hibernate:
      ddl-auto: validate # Flyway manages schema

praxis:
  config:
    firewall:
      allow-encoded-slash: true # default; aceita %2F nas chaves de UI
```

### Baseline (installs limpos)

Para uma instalação nova sem executar migrações legadas, use a baseline "squashed":

```properties
spring.flyway.locations=classpath:db/baseline
```

Para upgrades com histórico de migrações, mantenha `classpath:db/migration`.

### AI Orchestrator (Spring AI: Gemini/OpenAI/xAI + Schemas)

```yaml
praxis:
  ai:
    provider: gemini # gemini|openai|xai
    timeout-seconds: 30
    retry:
      max-attempts: 2
      initial-delay-ms: 500
      max-delay-ms: 2000
    prompt:
      max-chars:
        config: 12000
        schema: 12000
        template-config: 8000
        template-meta: 4000
        capabilities: 12000
        capability-notes: 3000
    api-key:
      encryption-key: ${PRAXIS_AI_API_KEY_ENCRYPTION_KEY} # base64 AES key (16/24/32 bytes) to encrypt ui_user_config ai.apiKey
    keys:
      admin-token: ${PRAXIS_AI_KEYS_ADMIN_TOKEN}
      require-admin-token: true
    schemas:
      base-url: http://localhost:8080 # host que expõe /schemas/filtered (metadata-starter)

spring:
  ai:
    embedding:
      provider: gemini # gemini|openai|mock
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com # para xAI: https://api.x.ai
      chat:
        options:
          model: gpt-4o-mini
      embedding:
        options:
          model: text-embedding-3-large
          dimensions: 768
    google:
      genai:
        api-key: ${GEMINI_API_KEY} # AI Studio (Google GenAI)
        embedding:
          api-key: ${GEMINI_API_KEY} # opcional; usa api-key acima quando omitido
        project-id: ${GCP_PROJECT_ID} # opcional para Vertex AI
        location: us-central1 # opcional para Vertex AI
        chat:
          options:
            model: gemini-2.0-flash
        embedding:
          text:
            options:
              model: text-embedding-004
              dimensions: 768

#### AI Studio (Google GenAI REST) — recomendado para testes rápidos

Se você usa **AI Studio** (https://aistudio.google.com/), não precisa de `project-id` nem `location`.
Basta fornecer a **API key** e o modelo. O backend prioriza o caminho REST do GenAI quando há `apiKey`.

Exemplo (test connection):

```bash
curl -sS -X POST http://localhost:8080/api/praxis/config/ai/providers/test \
  -H "Content-Type: application/json" \
  -d '{
    "provider":"gemini",
    "model":"gemini-2.5-flash",
    "apiKey":"SUA_API_KEY_DO_AI_STUDIO"
  }'
```

Variáveis úteis:
```properties
praxis.ai.gemini.prefer-genai-api=true
```
```

Para xAI, use `spring.ai.openai.base-url=https://api.x.ai` e `spring.ai.openai.chat.options.model=grok-2-latest` no ambiente.
OpenAI e xAI compartilham as mesmas chaves `spring.ai.openai.*`, portanto escolha apenas um por ambiente.

### Embeddings (RAG)

Embeddings usam Spring AI e devem manter o `dimensions` alinhado ao banco (pgvector).
Se o `vector_store` foi criado com `vector(768)`, ajuste `praxis.ai.rag.vector-store.dimensions` e o provedor de embedding para 768 (ou recrie a tabela/migration quando usar outra dimensão).
Para Gemini via Vertex AI, autentique via `GOOGLE_APPLICATION_CREDENTIALS` ou credenciais locais do SDK.
Para OpenAI, o serviço envia `dimensions` quando configurado em `spring.ai.openai.embedding.options.dimensions` para garantir alinhamento com o DB.
Para Gemini, `listModels` requer API key; sem key, o backend retorna apenas o modelo configurado.
Para o RAG nativo, o Spring AI usa o `vector_store` (pgvector). Para desabilitar o VectorStore, use `praxis.ai.rag.vector-store.enabled=false`.
Para ativar RAG no chat (Advisors), habilite `praxis.ai.rag.chat.enabled=true` e escolha `praxis.ai.rag.chat.mode=naive|modular`.
Headers `X-Tenant-ID` e `X-Env` (opcionais) são armazenados no metadata do RAG e usados para filtrar resultados quando presentes. Documentos globais (sem tenant/env) continuam visíveis quando esses headers são informados.
`praxis.ai.rag.max-hints` controla quantas dicas (hints) do componente entram no bloco RAG (default: 6).

## 📡 Key Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/praxis/config/api-catalog/ingest` | Ingests API catalog entries into `api_metadata` (RAG). |
| GET | `/api/praxis/config/api-catalog/search` | Vector search over API metadata in `api_metadata`. |
| POST | `/api/praxis/config/ai-registry/component-definitions` | Ingests UI component definitions into `ai_registry`. |
| GET | `/api/praxis/config/ai-registry/component-definitions/search` | Vector search over component definitions. |
| POST | `/api/praxis/config/ai/suggestions` | Generates AI suggestions (uses tenant/env headers when present). |
| GET | `/api/praxis/config/ai-context/{componentId}` | Returns AI context (runtime + metadata). Requires `X-Tenant-ID` header and `componentType` query param. |
| POST | `/api/praxis/config/ai-context/{componentId}` | Returns AI context using runtime `currentState` sent by the caller. |
| POST | `/api/praxis/config/ai/patch` | Orchestrates prompt → patch generation using runtime `currentState`. |
| POST | `/api/praxis/config/ai/providers/models` | Lists available LLM models for a provider (accepts `provider`, `apiKey`). |
| POST | `/api/praxis/config/ai/providers/test` | Tests provider connection using supplied `apiKey`/`model` (or defaults). |
| POST | `/api/praxis/config/ai/keys/clear` | Clears stored `ai.apiKey` for a config entry (requires tenant/user headers). |
| POST | `/api/praxis/config/ai/keys/rotate` | Re-encrypts stored `ai.apiKey` (optionally with provided old/new encryption keys). |
| GET | `/api/praxis/config/ai-registry/templates/{componentId}` | Reads SYSTEM/GLOBAL template from `ai_registry` (accepts `componentId[:variantId]`). |
| PUT | `/api/praxis/config/ai-registry/templates/{componentId}` | Upserts SYSTEM/GLOBAL template into `ai_registry` (accepts `componentId[:variantId]`). |
| DELETE | `/api/praxis/config/ai-registry/templates/{componentId}` | Deletes SYSTEM/GLOBAL template (accepts `componentId[:variantId]`). |
| POST | `/api/praxis/config/ai-registry/templates/bulk` | Bulk upsert of templates. |
| GET | `/api/praxis/config/ai-registry/templates/search` | Vector search over templates in `ai_registry`. |

Templates are global per key `componentId` (base) or `componentId:variantId` (variants), without `resourcePath` binding.
Base templates can expose `templateMeta.variants` and `defaultVariantId` to guide variant selection.

### AI key maintenance (clear / rotate)

Both endpoints require `X-Tenant-ID` (and `X-User-ID` when `scope=user`).
If `praxis.ai.keys.require-admin-token=true`, include `X-Admin-Token` or `Authorization: Bearer <token>`.

```json
// POST /api/praxis/config/ai/keys/clear
{
  "componentType": "praxis-global-config-editor",
  "componentId": "praxis:global-config",
  "scope": "tenant",
  "environment": "local"
}
```

```json
// POST /api/praxis/config/ai/keys/rotate
{
  "componentType": "praxis-global-config-editor",
  "componentId": "praxis:global-config",
  "scope": "tenant",
  "environment": "local",
  "previousEncryptionKey": "<base64>",
  "newEncryptionKey": "<base64>"
}
```

### Customizações de UI (novo endpoint transacional)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/praxis/config/ui?componentType=...&componentId=...` | Busca configuração por chave canônica (resolve fallback user→tenant). Usa headers `X-Tenant-ID`, opcional `X-User-ID`, `X-Env`, `If-None-Match`. |
| PUT | `/api/praxis/config/ui?componentType=...&componentId=...&scope=user|tenant` | Upsert (last-write-wins). Body: `{ payload, tags? }`. |
| DELETE | `/api/praxis/config/ui?componentType=...&componentId=...&scope=user|tenant` | Remove config do escopo informado (last-write-wins). |

### Headers/ETag
- `X-Tenant-ID` (obrigatório), `X-User-ID` (opcional para user scope), `X-Env` (opcional).
- `If-None-Match` em GET retorna 304 quando o ETag não mudou.
- PUT/DELETE são last-write-wins; `If-Match` não é exigido.

### componentType = selector (ui_user_config)
`componentType` agora deve ser o selector do componente (ex.: `praxis-table`) **somente** para
`/api/praxis/config/ui?componentType=...&componentId=...`. O `componentId` continua com a mesma
semântica (chaves como `table-config:...`, `form-pres:...`, etc.) e é persistido integralmente
como chave. Limite: 255 caracteres. Use o endpoint com query params para suportar `/` no `componentId`.
Não há fallback legado.

Exemplos:

Depois (selector):
- GET `/api/praxis/config/ui?componentType=praxis-table&componentId=table-config:employees`

### Reset de dados
Para limpar dados antigos e recomeçar a persistência com o novo padrão:
- `docs/truncate-ui-user-config.sql`

## 🧠 AI Context Retrieval & LLM Integration

This starter is the bridge between your metadata and your LLM agents. It provides a specialized `ContextRetrievalService` for RAG (Retrieval-Augmented Generation).

### API catalog ingestion (RAG)
The API catalog is sourced from the metadata starter endpoint `/schemas/catalog` and ingested via:
`POST /api/praxis/config/api-catalog/ingest`.

Recommended script (chunked upload, handles large catalogs):
```
cd praxis-ui-angular
BACKEND_URL="http://localhost:8080" \
npx ts-node --project tools/tsconfig.tools.json tools/ai-registry/upload-api-catalog.ts
```

Optional envs: `CATALOG_URL`, `CHUNK_SIZE`, `PAUSE_MS`, `TIMEOUT_MS`.

### Features
*   **Semantic Search:** Uses `pgvector` to find endpoints and components conceptually related to the user's query (e.g., "Add employee" finds `POST /api/employees`).
*   **Dual-Mode Schemas:**
    *   **Full Schemas (`requestSchema`, `responseSchema`):** Delivers the *complete*, non-truncated JSON structure to the LLM. This is critical for generating accurate `FormConfig` and `TableConfig` without hallucinations.
    *   **Snippets (`requestSchemaSnippet`, `responseSchemaSnippet`):** Delivers truncated previews (~500 chars) for UI lists or debug logs, saving bandwidth when the full schema isn't needed.

### AI Context payload
`/api/praxis/config/ai-context/{componentId}` returns runtime + metadata for LLM prompts:
- `componentDefinition`: UI catalog entry (description + inputs/outputs + config schema + capabilities).
- `template`: canonical example config for the component (SYSTEM/GLOBAL), with optional `templateMeta` for variants.
- `aiMode` + `requireSchema`: use `aiMode=create` to signal schema-required flows.
- `schemaContext`: optional `{ path, operation, schemaType }` when the caller already resolved the endpoint.

Important:
- `GET /api/praxis/config/ai-context/{componentId}` pode hidratar estado salvo em `ui_user_config` (fallback de conveniência).
- Para fluxos determinísticos de geração/edição, envie `currentState` explicitamente no `POST /ai-context` e no `POST /ai/patch` (sem depender de estado persistido).
- Templates are resolved by `registry_key = <componentId>` (base) or `registry_key = <componentId>:<variantId>` (variants).
- `componentType` remains a logical namespace for context/runtime lookup and is not part of the template key.

POST `/api/praxis/config/ai-context/{componentId}` accepts `currentState` in the body so AI
can operate on unsaved runtime changes (no dependency on `ui_user_config`):

```json
{
  "currentState": { "columns": [], "behavior": { "pagination": { "enabled": true } } },
  "resourcePath": "/api/orders",
  "schemaContext": { "path": "/api/orders", "operation": "GET", "schemaType": "response" }
}
```

POST `/api/praxis/config/ai/patch` accepts runtime state and user prompt and returns a patch:

```json
{
  "componentId": "praxis-table",
  "componentType": "praxis-table",
  "userPrompt": "Mostrar status com badge verde",
  "aiMode": "edit",
  "currentState": { "columns": [] },
  "resourcePath": "/api/orders",
  "schemaContext": { "path": "/api/orders", "operation": "GET", "schemaType": "response" }
}
```

### Clarification responses (UI signature)
When the backend needs more user input, it can respond with a clarification payload that includes an explicit UI
signature. The frontend should render the response based on `clarification`.

```json
{
  "type": "clarification",
  "message": "Qual coluna devo usar?",
  "options": ["status", "createdAt"],
  "clarification": {
    "responseType": "choice",
    "selectionMode": "single",
    "presentation": "buttons",
    "allowCustom": false
  }
}
```

Notes:
- Clarification responses are **mutually exclusive** with `patch` in the same payload.
- If `clarification` is partially filled, the backend will apply safe defaults (e.g., `choice` implies `allowCustom=false`).

### Create flow (schema on demand)
When `aiMode=create` (or `requireSchema=true`) and the resource path is not chosen:
1. Call `/api/praxis/config/api-catalog/search` with the intent.
2. Ask the user to pick the endpoint.
3. Call `/schemas/filtered` for the chosen path/operation/schemaType.

Important:
- `/schemas/catalog` is discovery/RAG input, not a structural substitute for runtime schema resolution.
- Request/response schemas used by AI or UI generation must come from `/schemas/filtered` for the chosen `path`, `operation`, and `schemaType`.
- `SchemaRetrievalService` now classifies operational outcomes explicitly: `SCHEMA_NOT_FOUND`, `SCHEMA_ACCESS_DENIED`, `SCHEMA_PLATFORM_UNAVAILABLE`, `SCHEMA_INVALID_RESPONSE` and `SCHEMA_RESOLUTION_FAILED`.
- Corporate recommendation: treat `SCHEMA_NOT_FOUND` as functional absence, `SCHEMA_ACCESS_DENIED` as security/configuration error, and `SCHEMA_PLATFORM_UNAVAILABLE` as transient operational failure eligible for retry/backoff upstream.

### JIT schema degradation
When schema resolution happens in the best-effort JIT enrichment path, the orchestrator no longer degrades silently.

Important:
- the main required-schema flow still returns explicit typed errors such as `SCHEMA_NOT_FOUND`, `SCHEMA_ACCESS_DENIED` and `SCHEMA_PLATFORM_UNAVAILABLE`;
- the JIT enrichment flow can continue the response, but it now emits warnings such as `SCHEMA_CONTEXT_DEGRADED: SCHEMA_PLATFORM_UNAVAILABLE`;
- consumers should treat these warnings as a quality degradation signal, not as a successful structural resolution.

Corporate recommendation:
- if the use case depends materially on structural schema, fail explicitly;
- if schema is only contextual enrichment, allow best-effort continuation but surface warnings in logs, metrics and client UX.

### Authoring turn stream
The agentic authoring turn stream is published by the config starter under
`/api/praxis/config/ai/authoring/turn/stream/**`.

Canonical flow:
1. `POST /api/praxis/config/ai/authoring/turn/stream/start` creates the turn, returns the `streamId`, and, when configured, returns a signed `streamUrl`.
2. `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}` replays and follows turn events as Server-Sent Events.
3. `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}/probe` validates that the client can open the stream before the UI commits to streaming.
4. `POST /api/praxis/config/ai/authoring/turn/stream/{streamId}/cancel` cancels a running turn.

Authentication modes:
- `cookie`: use when the browser already has the corporate principal in cookies/session state.
- `signed-url-token`: use for local or browser-only `EventSource` flows where the client cannot attach custom identity headers to the SSE request.

Important:
- `EventSource` cannot send custom request headers, so local authoring streams should use `PRAXIS_AI_STREAM_AUTH_MODE=signed-url-token` unless the host provides cookie/session authentication.
- Signed stream tokens carry the resolved tenant/user/environment for that stream. When a valid signed token is present and the request has no server-authenticated principal, token identity is authoritative over local fallback identity.
- The host must set a stable `PRAXIS_AI_STREAM_AUTH_TOKEN_SECRET`; do not use a production secret in local examples or docs.

### Downstream validation before release
The recommended downstream validation target is `praxis-api-quickstart`.

Validated release pattern:
- the quickstart must prove that `/api/praxis/config/ai/patch` propagates typed schema outcomes from the starter;
- the quickstart must prove that `/api/praxis/config/ai/authoring/turn/stream/start` and `/probe` work with real signed stream tokens in browser-compatible `signed-url-token` mode;
- CI must not depend on shared database, pgvector, external embedding quota or dynamic OpenAPI self-calls for this check;
- downstream smokes may isolate host-only concerns with mocks, as long as they still exercise the real config starter HTTP controllers and validate the HTTP contract returned to the client.

Recommended downstream commands:

```powershell
mvn "-Dtest=AiPatchSchemaResolutionIsolatedIntegrationTest" test
mvn "-Dtest=AgenticAuthoringStreamIsolatedIntegrationTest" test
```

### Usage Example (Java)

```java
@Autowired
ContextRetrievalService retrievalService;

// Search for endpoints related to "creating a user"
List<ApiSearchResult> results = retrievalService.searchApiMetadata("create new user", "POST", null, 5);

results.forEach(result -> {
    // Use Full Schema for LLM Context
    String fullContext = "Endpoint: " + result.getMethod() + " " + result.getPath() + "\n" +
                         "Schema: " + result.getRequestSchema();
                         
    // Use Snippet for Logging/UI
    log.info("Found: {} - Preview: {}", result.getPath(), result.getRequestSchemaSnippet());
});
```

## 🎯 Escopo das tabelas (para evitar confusão)

Este starter nasceu para **catálogo de metadados e contexto de IA**, não para armazenar customizações de usuário da UI Angular.

- `api_metadata`: catálogo de endpoints (OpenAPI) com vetores para busca semântica.
- `ai_registry`: catálogo unificado de metadados e templates de IA (ex.: `component_definition`, `template`) limitado a `SYSTEM/GLOBAL`.
- `ui_user_config`: storage transacional de customizações da UI por tenant/usuário/ambiente, com `version`/`etag`/`jsonb`. Serve à lib Angular via `ConfigStorage` remoto.
- `config_entries`: key/value simples, pensado para flags globais; não atende multi-tenant/usuário nem versionamento.

Para persistir customizações de UI por usuário/tenant em projetos host, use o endpoint de `ui_user_config` acima (version/etag/jsonb) em vez do catálogo `ai_registry`, mantendo o catálogo/IA isolado de preferências transacionais.
## 🤝 Ecosystem Integration

*   **With Metadata Starter:** Consumes outputs generated by `@UISchema` annotations.
*   **With UI Angular:** Serves JSONs that dynamically build frontend screens (Forms, Grids).

## 🛠 Development

To run locally, ensure Docker/Podman is running Postgres with the `pgvector/pgvector:pg16` image.

## 📄 License

Proprietary / Internal Use
