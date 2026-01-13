# Praxis Config Starter

![Version](https://img.shields.io/badge/version-0.0.1--SNAPSHOT-blue)
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
    <groupId>org.praxisplatform</groupId>
    <artifactId>praxis-config-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
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

# Opcional: firewall (Spring Security)
praxis.config.firewall.allow-encoded-slash=true # default; aceita %2F nas chaves de UI
```

### Baseline (installs limpos)

Para uma instalação nova sem executar migrações legadas, use a baseline "squashed":

```properties
spring.flyway.locations=classpath:db/baseline
```

Para upgrades com histórico de migrações, mantenha `classpath:db/migration`.

### AI Orchestrator (Gemini/OpenAI/xAI + Schemas)

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
    gemini:
      api-key: ${GEMINI_API_KEY}
      model: gemini-2.0-flash
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini
    xai:
      api-key: ${XAI_API_KEY}
      model: grok-2-latest
      base-url: https://api.x.ai/v1
    api-key:
      encryption-key: ${PRAXIS_AI_API_KEY_ENCRYPTION_KEY} # base64 AES key (16/24/32 bytes) to encrypt ui_user_config ai.apiKey
    keys:
      admin-token: ${PRAXIS_AI_KEYS_ADMIN_TOKEN}
      require-admin-token: true
    schemas:
      base-url: http://localhost:8080 # host que expõe /schemas/filtered (metadata-starter)
```

### Embeddings (RAG)

```yaml
embedding:
  provider: gemini # gemini|mock (valores invalidos geram erro; mock apenas para testes)
  dimensions: 768
  gemini:
    api-key: ${GEMINI_API_KEY}
```

## 📡 Key Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/praxis/config/api-catalog/ingest` | Ingests API catalog entries into `api_metadata` (RAG). |
| GET | `/api/praxis/config/api-catalog/search` | Vector search over API metadata in `api_metadata`. |
| POST | `/api/praxis/config/ai-registry/component-definitions` | Ingests UI component definitions into `ai_registry`. |
| GET | `/api/praxis/config/ai-registry/component-definitions/search` | Vector search over component definitions. |
| GET | `/api/praxis/config/ai-context/{componentId}` | Returns AI context (runtime + metadata). Requires `componentType` query param. |
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
- The AI flow must not read `ui_user_config`. The caller must send `currentState` on every request.
- Templates are resolved using the namespaced key `<componentType>:<componentId>` (example:
  `table:praxis-table`), where `componentType` is a logical namespace (table/form/page), not a
  runtime state source.

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

### Create flow (schema on demand)
When `aiMode=create` (or `requireSchema=true`) and the resource path is not chosen:
1. Call `/api/praxis/config/api-catalog/search` with the intent.
2. Ask the user to pick the endpoint.
3. Call `/schemas/filtered` for the chosen path/operation/schemaType.

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
