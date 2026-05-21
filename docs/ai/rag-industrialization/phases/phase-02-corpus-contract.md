# Fase 02: Contrato Canônico de Corpus Chunk

Status: concluido.

## Objetivo

Definir o contrato canônico de metadados para chunks AI-ready publicados no `vector_store`, garantindo busca semântica precisa, versionamento determinístico por release e prevenção de documentos órfãos (duplicidade).

## Decisão de Mapeamento (Campos Novos vs Aliases)

Para manter compatibilidade com o esquema existente do banco PGVector e seus índices de unicidade (que dependem de campos históricos), foi adotada uma estratégia híbrida:
- **Novos campos reais**: `sourceKind`, `sourceId`, `chunkKind`, `sourcePointer`, `corpusVersion`, `publishedAt` e `embeddingProfile` passam a ser gravados de forma explícita no JSONB de metadados do documento.
- **Duplicação/Alias para retrocompatibilidade**: Para garantir que buscas legadas e o índice de unicidade do banco (`idx_vector_store_scope_release_hash_chunk_unique`) continuem funcionando normalmente, o gerador e os injetores devem replicar os valores nos campos clássicos:
  - `sourceKind` é mapeado também para `resourceType` e `docType`.
  - `sourceId` é mapeado também para `componentId` e `resourceId`.

## Contrato Canônico de Metadados (Metadata Spec)

| Propriedade Lógica | Tipo | Chave `vector_store` JSONB | Mapeamento no `RagMetadataKeys` | Descrição |
| :--- | :--- | :--- | :--- | :--- |
| **Tenant** | String | `tenantId` | `RagMetadataKeys.TENANT_ID` | Identificador do tenant do cliente (ex: `desenv`). |
| **Environment** | String | `environment` | `RagMetadataKeys.ENVIRONMENT` | Ambiente operacional (ex: `local`, `production`). |
| **Release ID** | String | `releaseId` | `RagMetadataKeys.RELEASE_ID` | Chave da release ativa/versão do release cut (ex: `v0.1.0-rc.5`). |
| **Fallback Version** | String | `version` | `RagMetadataKeys.VERSION` | Versão auxiliar do request/manifesto para fallback. |
| **Corpus Version** | String | `corpusVersion` | `RagMetadataKeys.CORPUS_VERSION` | Versão do esquema de fatiamento do corpus (ex: `1`). |
| **Source Kind** | String | `sourceKind` | `RagMetadataKeys.SOURCE_KIND` | Tipo de recurso (ex: `component_definition`). Replica em `resourceType`/`docType`. |
| **Source ID** | String | `sourceId` | `RagMetadataKeys.SOURCE_ID` | ID único da entidade (ex: `praxis-dynamic-form`). Replica em `componentId`/`resourceId`. |
| **Source Pointer** | String | `sourcePointer` | `RagMetadataKeys.SOURCE_POINTER` | Caminho repo-relativo do arquivo fonte no workspace (ex: `praxis-ui-angular/projects/...`). |
| **Chunk Kind** | String | `chunkKind` | `RagMetadataKeys.CHUNK_KIND` | Tipo semântico da fatia indexada (ex: `summary`, `capabilities`). |
| **Chunk Index** | Integer| `chunkIndex` | `RagMetadataKeys.CHUNK_INDEX` | Índice sequencial do chunk, iniciando em `0`. |
| **Content Hash** | String | `contentHash` | `RagMetadataKeys.CONTENT_HASH` | Hash SHA-256 gerado a partir do conteúdo textual do chunk. |
| **Visibility** | String | `aiVisibility` | `RagMetadataKeys.AI_VISIBILITY` | Política de acesso/visibilidade da IA (ex: `allow`, `mask`). |
| **Embedding Profile**| String | `embeddingProfile` | `RagMetadataKeys.EMBEDDING_PROFILE` | Perfil/Modelo utilizado na geração do embedding. |
| **Published At** | String | `publishedAt` | `RagMetadataKeys.PUBLISHED_AT` | Data/hora em formato ISO-8601 da sincronização. |

## Lista Inicial de `chunkKind` para Componentes Angular

Para decompor o payload de componentes sem estourar o limite de tokens do embedding, o gerador dividirá os arquivos nos seguintes chunks:

1. **`summary`**:
   - **Descrição**: Metadados gerais do componente (nome, descrição em linguagem natural, seletores e tags).
   - **Foco Semântico**: Descoberta inicial ("qual componente serve para X?").
2. **`capabilities`**:
   - **Descrição**: Definições dos caminhos de metadados, HATEOAS e anotações permitidas.
   - **Foco Semântico**: Grounding estrutural de campos editáveis.
3. **`authoring_manifest`**:
   - **Descrição**: Contrato de autoria declarando ações (`editableTargets`, `operations`, schemas de validação).
   - **Foco Semântico**: Orientação ao LLM sobre como propor patches (`ai/patch`).
4. **`context_pack`**:
   - **Descrição**: Parâmetros contextuais e ajuda semântica do componente.
   - **Foco Semântico**: Guiar o modelo sobre comportamentos esperados sob condições específicas.
5. **`recipe`**:
   - **Descrição**: Exemplos reais de uso (receitas extraídas de `examples/ai-recipes/*.json`).
   - **Foco Semântico**: Few-shot learning de configurações válidas.

## Regra de Versionamento e Identidade

Para garantir a determinação única dos documentos no banco vetorial, o `chunkKind` é integrado ao ID do documento. O ID do documento (Spring AI / PGVector) é gerado via `RagDocumentIdentity.buildDocumentId(...)`:

`{tenantId}/{environment}/{sourceId}/{releaseId}/{sourceKind}/{chunkKind}/{contentHash}/{chunkIndex}`

Todas as partes são normalizadas usando `RagDocumentIdentity.normalizeToken(...)` (minúsculas, caracteres especiais convertidos em `_`). A presença explícita de `chunkKind` no ID previne colisões semânticas no nível do banco quando um componente publica múltiplos chunks diferentes em uma mesma release.

## Política de Purga (Prevenção de Documentos Órfãos)

Antes de gravar novos chunks para qualquer recurso, o backend executará um purgo transacional genérico usando uma query nativa parametrizável que suporta tanto as chaves novas quanto as clássicas:

```sql
DELETE FROM vector_store
WHERE COALESCE(metadata ->> 'tenantId', 'global') = :tenantId
  AND COALESCE(metadata ->> 'environment', 'global') = :environment
  AND COALESCE(metadata ->> 'releaseId', 'v1') = :releaseId
  AND COALESCE(metadata ->> 'componentId', metadata ->> 'sourceId', metadata ->> 'resourceId', id) = :sourceId
  AND COALESCE(metadata ->> 'docType', metadata ->> 'sourceKind', metadata ->> 'resourceType') = :sourceKind;
```

Essa query de purga garante que se a quantidade de chunks mudar (ex: de 5 para 3), todas as fatias da versão anterior daquele recurso específico serão removidas fisicamente antes da inserção da nova lista de chunks.

## Compatibilidade com Documentos Existentes

Os tipos RAG legados e paralelos continuarão operando sem interrupção:
- **`api_metadata`**: Utiliza `chunkIndex = 0` com propriedades mapeadas. Não há impacto de quebra imediata.
- **`project_knowledge`**: Identifica chunks baseados em hash estável `sha256(conceptKey|evidenceKey)`. Continuará com compatibilidade preservada no builder de identidade.
- **`domain_catalog`**: Indexa com `docType = domain_catalog`.

Os novos campos serão adicionados de forma `null-safe` na serialização do JSONB, mantendo retrocompatibilidade total com as leituras correntes do `ContextRetrievalService`.

## Handoff para a Fase 03

Com o contrato e decisões ajustados de forma consistente, a **Fase 03: Geração AI-ready por componente** focará em:
- **Entrada**: O contrato de chunks definido nesta Fase 02.
- **Objetivo**: Alterar os geradores TypeScript do `praxis-ui-angular` para decompor os arquivos dos componentes no padrão granular (`summary`, `capabilities`, `authoring_manifest`, `context_pack`, `recipe`), injetando os metadados corretos (incluindo o caminho repo-relativo em `sourcePointer`) e compilando-os no artefato unificado `praxis-component-registry-ingestion.json`.
