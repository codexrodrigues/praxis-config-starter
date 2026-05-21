# Agrupamento Recomendado de Commit/PR

Status: sugestao operacional pos-validacao integrada.

## Objetivo

Evitar que o programa das 9 fases seja revisado como uma mudanca unica indistinta.

## Regra

Nao separar commits de uma forma que quebre validacao local. Cada bloco abaixo deve compilar/testar no escopo descrito ou declarar dependencia clara do bloco anterior.

## Bloco 1: Corpus e tooling AI Registry

Escopo:

- `praxis-ui-angular/tools/ai-registry/**`;
- schema de ingestion;
- geradores de registry, RAG compat e provider projection;
- specs focais de tooling;
- `tools/ai-registry/README.md`;
- artefatos docs relacionados ao corpus.

Validacao minima:

```bash
npm run validate:ai
node tools/ai-registry/generate-provider-projection.spec.js
npx ts-node --project tools/tsconfig.tools.json tools/ai-registry/generate-provider-projection.ts
git diff --check
```

## Bloco 2: Backend RAG, retrieval e authoring

Escopo:

- `praxis-config-starter/src/main/java/org/praxisplatform/config/rag/**`;
- `RegistryIngestionService`;
- `ContextRetrievalService`;
- `AgenticAuthoringToolRegistry`;
- `AgenticAuthoringTurnEngine`;
- context bundle e autoconfiguracao relacionada;
- testes focais Java.

Validacao minima:

```bash
mvn -B -Dtest=RagVectorStoreServiceTest,RegistryIngestionServiceIdentityTest,ContextRetrievalServiceTest,AgenticAuthoringToolRegistryTest,AgenticAuthoringTurnEngineTest test
git diff --check
```

## Bloco 3: Docs, runbooks e fechamento

Escopo:

- `docs/ai/rag-industrialization/**`;
- prompts e handoffs;
- release readiness;
- plano de avaliacao;
- relatorio de validacao integrada.

Validacao minima:

```bash
git diff --check
```

## Bloco 4 opcional: Smoke operacional

Escopo:

- smoke HTTP com `praxis-api-quickstart`;
- E2E browser/LLM real se for decidido como gate de release.

Validacao minima:

- executar somente depois dos blocos 1 a 3;
- registrar provider, credenciais usadas via secrets/config local e resultado;
- nao persistir API keys nem IDs externos no repo.

## Ordem recomendada de review

1. Revisar contrato de corpus e tooling.
2. Revisar backend de publicacao/retrieval/authoring.
3. Revisar docs e readiness.
4. Decidir smoke externo ou provider projection real separadamente.

