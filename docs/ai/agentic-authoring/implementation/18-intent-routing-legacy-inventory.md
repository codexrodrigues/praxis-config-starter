# Inventario Inicial de Rotas Textuais Legadas

Classificacao da mudanca: `docs-apenas`.

Este inventario registra pontos encontrados durante a investigacao do fluxo de tabela com IA em 2026-06-01. Ele nao autoriza novos fast paths; serve para orientar a migracao para intencao semantica governada.

## Pontos Prioritarios

### `AiOrchestratorService.buildDeterministicColumnVisibilityEditPlan`

Risco: pode capturar pedidos compostos que mencionam ocultar/exibir colunas e retornar antes que a intencao principal seja resolvida pela LLM. No caso "junte departamento e cargo em uma nova coluna e oculte as originais", o fluxo privilegiou ocultacao e formato de coluna, perdendo a coluna derivada.

Direcao correta: a LLM deve resolver primeiro uma operacao composta, por exemplo `column.derived.add` com `postActions` ou `affectedColumns.visibility`. O resolver de visibilidade entra apenas para grounding das colunas originais depois dessa decisao.

### `AiOrchestratorService.buildDeterministicColumnVisibilityPatch`

Risco: usa splitting de clausulas, diretivas de visibilidade e matching de prompt contra colunas como mecanismo decisorio forte.

Direcao correta: manter apenas como candidato de grounding apos uma decisao canonica `column.visibility.set` ou remover quando o contrato semantico cobrir o caso.

### `AiOrchestratorService.deriveFallbackTableManifestActionPlan`

Risco: fallback textual pode escolher operacao de tabela quando o contrato/LLM deveria decidir.

Direcao correta: fallback nao deve substituir ferramenta ausente. Quando a LLM nao conseguir decidir, retornar clarificacao ou erro governado informando a lacuna de operacao/manifesto.

### `AiOrchestratorService.buildComputedColumnActionPlan`

Risco: cobre casos deterministas especificos de coluna calculada, mas nao uma coluna textual derivada por concatenacao de campos. Isso cria comportamento inconsistente entre prompts semelhantes.

Direcao correta: enriquecer `column.computed.add` ou criar/normalizar `column.derived.add` para expressar origem, composicao, label, renderer, visibilidade das fontes e validacao de tipos.

### Conversao de patch para `componentEditPlan`

Risco: a conversao conhecida para plano de componente cobre propriedades como visibilidade, formato, renderer e valueMapping, mas nao materializa adequadamente `computed`/`derived` quando a intencao principal e criar coluna nova.

Direcao correta: adicionar suporte explicito a coluna derivada/calculada no contrato de plano, com validacao de manifests e teste de materializacao no Angular.

### Backlog historico de routing canonico

Risco: documentacao antiga citava "heuristica deterministica" como implementacao de routing, o que pode induzir agentes a repetir o padrao.

Direcao correta: manter a documentacao como historica e alinhar novas instrucoes aos guardrails semanticos vigentes.

## Validacao Recomendada Para a Correcao de Runtime

- Teste unitario focal no backend para prompt composto: criar coluna derivada de `departamento` + `cargo`, nomear `Posicao Organizacional` e ocultar fontes.
- Teste com ordem invertida: ocultar fontes e criar coluna derivada.
- Teste com erro humano: "junta depto e cargo numa coluna posicao, esconde as antigas".
- Smoke local no Angular validando que a coluna nova aparece, as fontes somem e o plano de revisao descreve a intencao correta.

