# Contrato v1.1 - Boundary + Retro Suite (A-04/A-05)

Data: `2026-02-22`

## Escopo

- Fechar `A-04` com boundary completo de contrato em `entrada/saida/SSE`.
- Implementar `A-05` com suite retro versionada de contrato `v1.1`.

## Artefatos principais

- `src/test/java/org/praxisplatform/config/contract/AiContractV11RetroCompatibilityTest.java`
- `src/test/java/org/praxisplatform/config/contract/AiApiContractOpenApiTest.java`
- `src/test/java/org/praxisplatform/config/contract/AiContractSpecConsistencyTest.java`

## Comando executado

```bash
cd praxis-config-starter
/opt/maven/bin/mvn -Dtest=AiApiContractOpenApiTest,AiContractSpecConsistencyTest,AiContractV11RetroCompatibilityTest test -q
```

## Resultado consolidado

- `AiApiContractOpenApiTest`: **PASS** (`Tests run: 1, Failures: 0, Errors: 0`).
- `AiContractSpecConsistencyTest`: **PASS** (`Tests run: 1, Failures: 0, Errors: 0`).
- `AiContractV11RetroCompatibilityTest`: **PASS** (`Tests run: 6, Failures: 0, Errors: 0`).

## Cobertura `A-04` (boundary completo)

1. Entrada `/patch`:
   - fallback retro quando metadata de contrato nao e enviada;
   - precedencia de metadata enviada no body sobre header;
   - bloqueio `409` para `SCHEMA_HASH_MISMATCH`.
2. Saida `/patch` e `/patch/stream/start`:
   - headers `X-Praxis-Contract-Version` e `X-Praxis-Schema-Hash` consistentes;
   - payloads retornam `contractVersion/schemaHash` esperados.
3. SSE `/patch/stream/{streamId}`:
   - envelope com `eventSchemaVersion=v1`;
   - tipos de evento restritos ao enum oficial;
   - campos obrigatorios (`streamId/threadId/turnId/timestamp/payload`) presentes.

## Cobertura `A-05` (suite retro versionada)

1. Suite dedicada e nomeada por versao (`AiContractV11RetroCompatibilityTest`).
2. Matriz de compatibilidade em HTTP real (`MockMvc`) para `PATCH` e `stream`.
3. Drift guard mantido via OpenAPI + constantes geradas (`AiApiContractOpenApiTest` e `AiContractSpecConsistencyTest`).
