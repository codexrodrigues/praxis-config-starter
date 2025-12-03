# Praxis Config Starter

![Version](https://img.shields.io/badge/version-0.0.1--SNAPSHOT-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2%2B-brightgreen)
![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL%20Vector-blue)

The **Dynamic Configuration Kernel** of the Praxis ecosystem. This starter provides persistence, versioning, and semantic intelligence (vector-ready) for API metadata and UI configurations.

## 🏗 Architecture

This module attaches to your Spring Boot application and automatically manages:
1.  **Metadata Tables** (`api_metadata`, `ui_configuration`).
2.  **REST Endpoints** for context management.
3.  **Ingestion** of OpenAPI definitions for vector search.

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
```

## 📡 Key Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/praxis/config/ai-context/ingest-registry` | Ingests metadata JSON for indexing. |
| GET | `/api/praxis/config/ui/{key}` | Returns UI configuration for a specific key. |

### Customizações de UI (novo endpoint transacional)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/praxis/config/ui/{componentType}/{componentId}` | Busca configuração por chave canônica (resolve fallback user→tenant). Usa headers `X-Tenant-ID`, opcional `X-User-ID`, `X-Env`, `If-None-Match`. |
| PUT | `/api/praxis/config/ui/{componentType}/{componentId}?scope=user|tenant` | Upsert com `If-Match` (`*` para criar). Body: `{ payload, tags? }`. |
| DELETE | `/api/praxis/config/ui/{componentType}/{componentId}?scope=user|tenant` | Remove config do escopo informado (If-Match obrigatório). |

### Headers/ETag
- `X-Tenant-ID` (obrigatório), `X-User-ID` (opcional para user scope), `X-Env` (opcional).
- `If-None-Match` em GET retorna 304 quando o ETag não mudou.
- `If-Match` é obrigatório em PUT/DELETE (`*` para criação).

## 🧠 AI Context Retrieval & LLM Integration

This starter is the bridge between your metadata and your LLM agents. It provides a specialized `ContextRetrievalService` for RAG (Retrieval-Augmented Generation).

### Features
*   **Semantic Search:** Uses `pgvector` to find endpoints and components conceptually related to the user's query (e.g., "Add employee" finds `POST /api/employees`).
*   **Dual-Mode Schemas:**
    *   **Full Schemas (`requestSchema`, `responseSchema`):** Delivers the *complete*, non-truncated JSON structure to the LLM. This is critical for generating accurate `FormConfig` and `TableConfig` without hallucinations.
    *   **Snippets (`requestSchemaSnippet`, `responseSchemaSnippet`):** Delivers truncated previews (~500 chars) for UI lists or debug logs, saving bandwidth when the full schema isn't needed.

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
- `component_definition`: catálogo de componentes (schema/descrição) com vetores para RAG.
- `ui_configuration`: guarda configurações “base” de componentes para contexto de IA (scopes `SYSTEM`/`USER`), com embedding. **Não** foi desenhada para ser storage transacional de preferências/edit de usuário.
- `ui_user_config` (novo): storage transacional de customizações da UI por tenant/usuário/ambiente, com `version`/`etag`/`jsonb`. Serve à lib Angular via `ConfigStorage` remoto.
- `config_entries`: key/value simples, pensado para flags globais; não atende multi-tenant/usuário nem versionamento.

Para persistir customizações de UI por usuário/tenant em projetos host, use o endpoint de `ui_user_config` acima (version/etag/jsonb) em vez de `ui_configuration`, mantendo o catálogo/IA isolado de preferências transacionais.
## 🤝 Ecosystem Integration

*   **With Metadata Starter:** Consumes outputs generated by `@UISchema` annotations.
*   **With UI Angular:** Serves JSONs that dynamically build frontend screens (Forms, Grids).

## 🛠 Development

To run locally, ensure Docker/Podman is running Postgres with the `pgvector/pgvector:pg16` image.

## 📄 License

Proprietary / Internal Use
