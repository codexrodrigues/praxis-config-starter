# P1-BE-1 Patch Gate hard (capabilities obrigatórias + array sanitization fix)

Contexto
- O backend atualmente retorna patch mesmo quando nao ha capabilities (sanitizePatch retorna patch sem filtro).
- Arrays podem escapar do filtro quando o path permitido nao inclui o sufixo [].

Objetivo
- Bloquear patches sem capabilities.
- Corrigir sanitizacao de arrays para nao permitir conteudo nao permitido.

Escopo permitido
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java`
- `praxis-config-starter/src/test/java/org/praxisplatform/config/service/*`

Passos
- [ ] Ajustar `sanitizePatch(...)` para retornar erro (patch nulo) quando capabilities estiverem ausentes/vazias.
- [ ] Corrigir `sanitizeNode(...)` para descartar arrays quando `path[]` nao estiver permitido.
- [ ] Garantir que warnings informem bloqueio por capabilities ausentes.
- [ ] Adicionar testes unitarios deterministas cobrindo os dois casos.

Criterios de aceite
- Patch nao e retornado quando capabilities estao ausentes.
- Arrays nao escapam sem allowlist de `path[]`.
- `mvn test` passa.

Validacao
```bash
cd praxis-config-starter && mvn test
```

Relatorio
- Atualizar `docs/ai/p1-backend/P1-BE-1-summary.md` com arquivos alterados + output do teste.
