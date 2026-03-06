# AI API Contract (Single Source)

Arquivo fonte unico atual:

- `docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml`

Escopo coberto:

- `POST /api/praxis/config/ai/patch`
- `POST /api/praxis/config/ai/patch/stream/start`
- `GET /api/praxis/config/ai/patch/stream/{streamId}`
- `GET /api/praxis/config/ai/patch/stream/{streamId}/probe`
- `POST /api/praxis/config/ai/patch/stream/{streamId}/cancel`

Validação automatizada no backend:

- `src/test/java/org/praxisplatform/config/contract/AiApiContractOpenApiTest.java`
- `src/test/java/org/praxisplatform/config/contract/AiContractSpecConsistencyTest.java`
- `src/test/java/org/praxisplatform/config/contract/AiContractV11RetroCompatibilityTest.java`

Comando recomendado para gate de contrato `v1.1`:

```bash
/opt/maven/bin/mvn -Dtest=AiApiContractOpenApiTest,AiContractSpecConsistencyTest,AiContractV11RetroCompatibilityTest test -q
```

Geração de bindings (A-02) a partir da mesma fonte:

1. executar `node tools/contracts/generate-ai-contract-bindings.js` no `praxis-config-starter`;
2. arquivo Java gerado:
   - `src/main/java/org/praxisplatform/config/contract/AiContractSpec.java`
3. arquivo TS gerado (quando o monorepo tiver `praxis-ui-angular`):
   - `../praxis-ui-angular/projects/praxis-ai/src/lib/core/contracts/ai-contract.generated.ts`

Observação:

- o teste `AiContractSpecConsistencyTest` funciona como drift guard entre OpenAPI e constantes geradas no backend.
