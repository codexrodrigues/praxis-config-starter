# Fase 03: Geração AI-ready por Componente

Status: concluído.

## Objetivo

Evoluir os generators existentes para produzir corpus granular por componente, usando registry, manifests, capabilities, context packs, recipes e documentacao.

## Escopo e Alterações

- **Backend Contract Sync**:
  - `praxis-config-starter`: Atualizado [RegistryIngestionRequest.java](../../../../praxis-config-starter/src/main/java/org/praxisplatform/config/dto/RegistryIngestionRequest.java) introduzindo a estrutura aninhada de `ChunkEntry` correspondendo ao design do corpus granular.
- **Frontend Schemas**:
  - `praxis-ui-angular`: Atualizado [praxis-component-registry-ingestion.schema.json](../../../../praxis-ui-angular/tools/ai-registry/schemas/praxis-component-registry-ingestion.schema.json) para incluir as definições formais de validação para a lista de `chunks`.
- **Generation & Decomposition**:
  - `praxis-ui-angular`: Evolução do script [generate-registry-ingestion.ts](../../../../praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts) para realizar a coleta recursiva de arquivos de receita (`recipes`), decompor cada componente em múltiplos `chunks` (`summary`, `authoring_manifest`, `recipe`), associar referências seguras via `sourcePointer` e calcular os hashes SHA-256 (`contentHash`).
- **Governance Gate**:
  - `praxis-ui-angular`: Modificação de [validate-catalog-governance.js](../../../../praxis-ui-angular/tools/ai-registry/validate-catalog-governance.js) adicionando a função `validateChunks` para garantir a integridade dos dados, sequência de `chunkIndex`, consistência do hash SHA-256 em relação ao conteúdo e detecção de arquivos de receita órfãos (`drift detection`).

## Guardrails Respeitados

- **Sem novo pipeline**: Apenas estendemos o registry de ingestão já consolidado.
- **Reuso de receitas**: As receitas sob `examples/ai-recipes/` são carregadas dinamicamente e mapeadas para os componentes com seus respectivos `sourcePointer`.
- **Governança**: A validação de catálogo e aceitação de contratos de autoria continua sendo a única e robusta fonte de verdade de governança local.

## Validação Realizada

- Execução bem-sucedida do comando focal local:
  ```bash
  npm run generate:registry:ingestion
  ```
- **Relatório de Validação**:
  - Status: **PASS**
  - Componentes com ingestão: **102**
  - Erros: **0**
  - Avisos: **0**
  - Todos os hashes de conteúdo e caminhos de arquivos foram testados deterministicamente e estão consistentes.

## Handoff para Fase 04

### 1. Interface de Entrada / Saída
- **Entrada da Fase 04**: O arquivo JSON completo gerado em [dist/praxis-component-registry-ingestion.json](../../../../praxis-ui-angular/dist/praxis-component-registry-ingestion.json), validado pelo esquema e pelo script de governança.
- **Contrato de Ingestão de Chunk**:
  Cada chunk no payload possui a seguinte assinatura garantida e validada:
  ```json
  {
    "chunkIndex": 0,
    "chunkKind": "summary | capabilities | authoring_manifest | context_pack | recipe",
    "content": "conteúdo textual do chunk",
    "sourcePointer": "praxis-ui-angular/caminho/do/arquivo",
    "contentHash": "sha256-hex-hash",
    "sourceKind": "component_definition",
    "sourceId": "pdx-color-input",
    "corpusVersion": "1.0.0"
  }
  ```

### 2. Decisões Consolidadas
- **Campos Canônicos Identificadores**: `sourceKind` e `sourceId` são injetados em tempo de geração nos metadados de cada chunk, permitindo que o backend indexe no Vector Store sem necessidade de inferência.
- **Hash do Conteúdo**: O hash SHA-256 (`contentHash`) é gerado deterministicamente sobre o conteúdo bruto do chunk, servindo como chave para verificar alterações de versão e evitar reindexação redundante.

### 3. Dependências
- O DTO do backend `RegistryIngestionRequest` em `praxis-config-starter` deve estar alinhado e compilado (concluído com sucesso na Fase 03).

### 4. Riscos Residuais
- **Consistência de Identidade**: Caso o `sourceId` ou `chunkIndex` mude semântica ou estruturalmente, o backend precisará limpar de forma atômica/purga usando `COALESCE` e a versão de liberação (`releaseId`). A Fase 04 deve prever essa limpeza por `sourceKind` e `sourceId`.
- **Casos Especiais**: Componentes que compartilham manifests agregados (como `praxis-dynamic-fields` e seus subcomponentes) devem ter seus chunks gerados individualmente mapeando para seu respectivo `sourceId`. O generator atual atende isso, mas qualquer nova agregação exige acompanhamento no frontend.


