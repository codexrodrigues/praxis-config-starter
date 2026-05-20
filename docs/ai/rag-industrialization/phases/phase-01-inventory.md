# Fase 01: Inventario e Saneamento

Status: concluido.

## Objetivo

Inventariar todos os artefatos existentes de RAG, registry, AI-ready docs, authoring manifests, capabilities, context packs e recipes, classificando-os como canonicos, derivados, compat, obsoletos ou historicos.

## Matriz de Artefatos Atuais e Classificação

| Artefato / Caminho | Subprojeto Dono | Função / Propósito | Classificação | Observações / Gaps |
| :--- | :--- | :--- | :--- | :--- |
| **Geração de Registro de Componentes** | | | | |
| `extract-component-docs.js` | `praxis-ui-angular` | Extrai definições `ComponentDocMeta` de arquivos `*metadata.ts` e gera `component-docs.json`. | **Canônico (Tooling)** | Mapeia descrições manuais e presets de inserção. |
| `generate-registry.ts` | `praxis-ui-angular` | Parser AST com `ts-morph` que extrai inputs/outputs `@Component` do código. | **Canônico (Tooling)** | Gera o arquivo intermediário `praxis-component-registry.json`. |
| `generate-registry-ingestion.ts` | `praxis-ui-angular` | Compila o registro base, docs extras, capabilities, context packs e manifests de autoria. | **Canônico (Tooling)** | Gera o payload completo `praxis-component-registry-ingestion.json`. |
| `generate-registry-rag.ts` | `praxis-ui-angular` | Gera a projeção simplificada para planejamento de templates. | **Compat / Deprecated** | Redundante. Deve ser removido após substituir seu consumo pelo payload unificado. |
| `upload-registry.ts` | `praxis-ui-angular` | Envia o payload compilado para o endpoint `/api/praxis/config/ai/registry/ingestion` do backend. | **Canônico (Tooling)** | Faz o sync das definições da UI para a fonte canônica no banco. |
| **Geração de Documentação AI-Ready** | | | | |
| `generate-ai-ready-docs.ts` | `praxis-ui-angular` | Varre markdown/txt do repositório para expor catálogo consolidado. | **Canônico (Tooling)** | Gera arquivos consumíveis por agentes externos. |
| `docs-mcp-server.js` | `praxis-ui-angular` | Servidor MCP local para expor a documentação gerada do workspace. | **Canônico (Tooling)** | Facilita consumo das docs indexadas por subagentes locais. |
| **Projeções e Artefatos JSON/Text (Output)** | | | | |
| `tools/ai-registry/component-docs.json` | `praxis-ui-angular` | Snapshot estático extraído das meta-anotações de componentes. | **Canônico (Metadata)** | Usado como insumo no pipeline de compilação do registro. |
| `dist/praxis-component-registry.json` | `praxis-ui-angular` | Output intermediário com as classes/inputs/outputs extraídos do AST. | **Derivado** | Gerado dinamicamente a partir do código. |
| `dist/praxis-component-registry-ingestion.json` | `praxis-ui-angular` | Payload final contendo toda a semântica de componentes para o backend. | **Derivado** | Fonte de verdade enviada ao banco de dados. |
| `dist/praxis-component-registry-rag.json` | `praxis-ui-angular` | Projeção simplificada de componentes para o gerador de templates. | **Compat / Deprecated** | Apresenta drift potencial e redundância. |
| `dist/ai-ready/llms.txt` | `praxis-ui-angular` | Índice plano consolidado de documentação para agentes LLM. | **Derivado** | Exposto publicamente/localmente. |
| `dist/ai-ready/docs-index.json` | `praxis-ui-angular` | Dicionário estruturado de metadados do catálogo de documentação. | **Derivado** | Usado pelo Docs MCP Server. |
| **Modelos de Capacidades, Contextos e Manifestos** | | | | |
| `*authoring-manifest.ts` | `praxis-ui-angular` | Contratos declarativos de autoria (operações, alvos editáveis, schemas, validadores). | **Canônico (Semântica)** | Define como a IA pode alterar as configs do componente. |
| `*context-pack.ts` | `praxis-ui-angular` | Contextos de ajuda, parâmetros e exemplos dinâmicos passados ao LLM. | **Canônico (Semântica)** | Enriquecimento de prompt em tempo de execução. |
| `*capabilities.ts` | `praxis-ui-angular` | Mapeamento de caminhos HATEOAS e campos elegíveis do componente. | **Canônico (Semântica)** | Grounding de limites e estruturas do schema do componente. |
| `examples/ai-recipes/**` | `praxis-ui-angular` | Catálogo de receitas/templates JSON contendo exemplos de uso real dos componentes. | **Canônico (Exemplos)** | Grounding pragmático de materialização. |
| **Serviços Backend e Tabelas de Banco** | | | | |
| `ai_registry` (Tabela SQL) | `praxis-config-starter` | Tabela persistida de definições canônicas de componentes e capabilities. | **Canônico (Persistência)** | Tabela que armazena payloads e embeddings originais. |
| `vector_store` (Tabela SQL) | `praxis-config-starter` | Tabela PGVector compartilhada com Spring AI para armazenamento vetorial. | **Canônico (Persistência)** | Indexa documentos RAG derivados com seus respectivos embeddings. |
| `RegistryIngestionService.java` | `praxis-config-starter` | Ingesta as definições de componentes e capabilities do Angular na tabela `ai_registry` e no RAG. | **Canônico (Core)** | Converte entradas do manifesto de componentes em registros de banco e RAG. |
| `ApiMetadataIngestionService.java` | `praxis-config-starter` | Ingesta definições e indexa os metadados das rotas de API (`api_metadata`) no RAG. | **Canônico (Core)** | Publisher RAG para contratos de endpoints. |
| `DomainCatalogIngestionService.java` | `praxis-config-starter` | Ingesta e publica conceitos de termos de negócios e catálogo de domínio no RAG. | **Canônico (Core)** | Publisher RAG de descoberta de domínios públicos. |
| `RagProjectKnowledgeDerivedIndexService.java` | `praxis-config-starter` | Publica as evidências ativas de decisões semânticas de Project Knowledge no RAG. | **Canônico (Core)** | Publisher RAG de decisões de governança autoradas por IA. |
| `ContextRetrievalService.java` | `praxis-config-starter` | Orquestra a busca vetorial no PGVector e o recarregamento canônico de evidências. | **Canônico (Core)** | Recuperação semântica e grounding para o Authoring Engine. |
| `RagFilters.java` | `praxis-config-starter` | Builder utilitário para a construção de filtros booleanos de metadados no Spring AI. | **Canônico (Core)** | Componente de filtragem vetorial. |
| `RagProjectKnowledgeMetadata.java` | `praxis-config-starter` | Define chaves de metadados específicas para o domínio de Project/Domain Knowledge. | **Canônico (Core)** | Extensão de metadados do RAG. |
| `RagVectorStoreService.java` | `praxis-config-starter` | Wrapper Java para operações no Spring AI `VectorStore`. | **Canônico (Core)** | Executa escritas/buscas vetoriais. |

## Decisão Inicial sobre `praxis-component-registry-rag.json`

- **Classificação**: **Compat / Deprecated**.
- **Justificativa**: O arquivo `praxis-component-registry-rag.json` é uma projeção simplificada do catálogo de componentes e apresenta redundância/drift potencial. Ele tem como consumo fonte principal o script `generate-templates-plan.ts` (na geração da matriz de prontidão/templates todo-list), embora existam também referências acessórias em scripts do `package.json`, especificações de teste e relatórios como `dist/ai-artifacts-validation-report*`.
- **Decisão e Encaminhamento**:
  1. Manter temporariamente por razões de retrocompatibilidade enquanto estruturamos o novo contrato de chunks granular.
  2. Na **Fase 08 (Migração de Legados)**, o script `generate-templates-plan.ts` deve ser alterado para ler os dados diretamente do payload completo e unificado `praxis-component-registry-ingestion.json`.
  3. Após essa alteração, o script `generate-registry-rag.ts` e o arquivo `praxis-component-registry-rag.json` serão **removidos permanentemente**, sanando o drift e reduzindo a complexidade de compilação.

## Docs Desatualizadas ou com Gaps

Identificamos a necessidade de ajuste/atualização nos seguintes documentos:
- `docs/ai/rag-industrialization/phases/phase-02-corpus-contract.md`: Deve ser refinado para formalizar os novos metadados obrigatórios do chunk por componente (ex: `chunkKind`, `visibility`, `releaseId`, `sourceRef`).
- `praxis-config-starter/docs/ai/release-decision-2026-05-02-project-knowledge-vector-rag.md`: Precisa de seção ou referência cruzada para a governança de indices de componentes Angular RAG para evitar confusão com o RAG de decisões de domínio (Domain Knowledge).
- `praxis-ui-angular/tools/ai-registry/README.md`: O fluxo documentado para `--with-rag` precisa detalhar que o RAG de componentes está em fase de transição para o modelo unificado de ingestão.

## Gaps Reais Identificados no Setup Atual

1. **Granularidade Inadequada (Single-chunk Ingestion)**:
   - Atualmente, o `RegistryIngestionService` cria um único documento RAG por componente contendo o resumo gerado em `buildSummary()`, associando `chunk_index = 0` e enviando toda a informação condensada em uma única string de texto.
   - Componentes complexos com grandes manifests de autoria, múltiplos context packs e dezenas de capabilities estouram a janela ideal de embedding, diluindo o foco semântico da busca.
2. **Definição de Versão e Release no RAG**:
   - O `releaseId` no banco vetorial está atrelado apenas à versão passada no request do manifesto. Embora existam mecanismos estruturados como `RagDocumentIdentity` para geração determinística de hashes e IDs de documentos, chaves padronizadas como `RagMetadataKeys.RELEASE_ID`, e builders de filtros robustos como `RagFilters` e estratégias de fallback de versão, a indexação de componentes Angular RAG ainda não os consome de maneira totalmente integrada ao release do pacote de componentes. A Fase 02 precisará parametrizar a modelagem de chunks granular reutilizando esses padrões existentes para evitar retrabalho de design.
3. **Filtro de Recuperação Semântica Incompleto**:
   - O `ContextRetrievalService` carece de filtros de metadados focados e granulares para o `resource_type` de componentes, dependendo atualmente de buscas amplas ou de processamento de contexto no lado do consumidor.

## Validação Executada nesta Fase

- Varredura de diretórios usando comandos `list_dir` e buscas focadas com `grep_search` para mapear dependências cruzadas de arquivos de script.
- Inspeção detalhada do fluxo do backend Java em `RegistryIngestionService.java` para compreender a criação e inserção de objetos `Document` do Spring AI na tabela `vector_store`.
- Análise de migrações SQL (`V6`, `V12`, `V16`, `V18`) para verificar constraints e índices únicos aplicados à busca semântica e integridade determinística.
- Checagem das chamadas de script de validação de catálogo e planejamento nos arquivos `run-all.sh`, `generate-templates-plan.ts` e `generate-registry-ingestion.ts`.

## Handoff para a Fase 02

A Fase 01 está oficialmente concluída. O handoff para a **Fase 02: Contrato Canônico de Corpus Chunk** baseia-se nos seguintes pontos:
- **Entrada Clara**: Temos o mapa completo de componentes, capabilities, manifests e context packs. Sabemos que o backend ingere esses dados em um modelo "único-documento-por-componente".
- **Objetivo da Fase 02**: Desenhar a estrutura de chunks granulares (ex: chunk de capabilities, chunk de manifest de autoria, chunk de context pack, chunk de exemplos/receitas) e mapear quais metadados (`RagMetadataKeys`) serão inseridos em cada um para garantir busca precisa e com alta fidelidade semântica no `ContextRetrievalService`.
