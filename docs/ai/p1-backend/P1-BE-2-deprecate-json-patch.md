# P1-BE-2 Depreciar JSON Patch e formalizar merge patch

Contexto
- Backend converte parcialmente JSON Patch (apenas tabela) e rejeita o restante.
- Contrato de merge patch ainda e implicito.

Objetivo
- Remover conversao de JSON Patch (somente erro claro).
- Formalizar merge patch no contrato do backend.
- Emitir warnings/headers de deprecacao quando JSON Patch for detectado.

Escopo sugerido
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java`
- `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java`
- DTOs de resposta (se necessario para sinalizar deprecacao)

Passos
- [ ] Bloquear JSON Patch (array de ops ou objeto com op/path/from).
- [ ] Remover `convertJsonPatchToSemantic(...)`.
- [ ] Atualizar mensagens de erro para explicitar merge patch como contrato.
- [ ] Atualizar docs e exemplos.

Criterios de aceite
- JSON Patch sempre rejeitado com mensagem unica e consistente.
- Contrato de merge patch documentado no response.
- Sem regressao em patches semanticos.
