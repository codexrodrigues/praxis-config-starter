# Fase 08: Migracao de Contratos Antigos e Remocao de Duplicidades

Status: concluido.

## Objetivo

Migrar, deprecar ou remover caminhos antigos que ficarem redundantes apos corpus/retrieval granular.

## Escopo

- `/ai/patch`
- `/ai/patch/stream`
- `componentEditPlan`
- contrato especifico da tabela
- `targetKind`
- `praxis-component-registry-rag.json`
- docs historicas/normativas com guidance antigo

## Guardrails

- Nao remover contrato ainda consumido sem substituir uso real.
- Em beta, preferir migracao limpa quando o substituto estiver provado.
- Diferenciar compat temporaria de fonte canonica.

## Entregas

- Matriz: manter, migrar, deprecar, remover.
- Patches de migracao quando seguro.
- Docs atualizadas.
- Testes focais dos consumidores afetados.

## Validacao minima

- Builds/testes focais das libs afetadas.
- Testes Java se endpoints mudarem.
- Validacao de registry/corpus se artefatos gerados mudarem.
- `git diff --check`.

## Criterio de pronto

Nao ha superficie antiga parecendo caminho oficial quando ja existe alternativa canonica provada.

## Classificacao e mapa de impacto

Classificacao executada: `arquitetural` + `contrato-publico` + `transversal`.

Subprojetos canonicos afetados:

- `praxis-config-starter`: dono dos endpoints AI, RAG interno, retrieval e authoring governado.
- `praxis-ui-angular`: dono dos manifests, capabilities, context packs e tooling de registry das libs `@praxisui/*`.

Consumidores impactados:

- `@praxisui/ai`, `@praxisui/table`, `@praxisui/page-builder`;
- fluxos `/api/praxis/config/ai/patch`, `/api/praxis/config/ai/patch/stream/**` e `/api/praxis/config/ai/authoring/**`;
- scripts `tools/ai-registry/run-all.*`, `generate-templates-plan.ts` e validacoes AI.

Docs e artefatos derivados considerados:

- `docs/ai/rag-industrialization/**`;
- `praxis-ui-angular/tools/ai-registry/README.md`;
- `dist/praxis-component-registry-rag.json` como artefato gerado;
- `dist/praxis-component-registry-ingestion.json` como corpus canonico de componente.

Risco de breaking change: alto para remocao de endpoints/DTOs e medio para o artefato RAG compacto. Por isso a fase removeu ambiguidade canonica e manteve compatibilidade onde ha consumo vivo.

## Matriz de contratos

| Superficie | Decisao | Justificativa | Acao executada |
| --- | --- | --- | --- |
| `/api/praxis/config/ai/patch` | manter como compatibilidade governada | Ainda e consumido por `@praxisui/ai`, simuladores e docs contratuais. O substituto completo para todos os consumidores ainda nao esta provado. | Sem remocao. Classificado como facade viva ate migracao por consumidor. |
| `/api/praxis/config/ai/patch/stream/**` | manter | O stream e contrato ativo do assistant e possui cliente Angular vivo. | Sem remocao. |
| `componentEditPlan` | manter como contrato governado | E o formato validado por manifests para tabela, page-builder e respostas deterministicas backend. Nao e duplicidade livre; e materializacao declarativa governada. | Sem remocao. |
| Contrato especifico da tabela | manter e continuar convergindo para manifests | `TableAgenticAuthoringTurnFlow` e `TableAiAdapter` rejeitam patch livre sem plano validado. O contrato especifico ainda e runtime de governanca. | Sem remocao nesta fase. |
| `targetKind` | deprecar, nao remover | O tipo publico ja marca `targetKind` como deprecated e exige paridade com `target.kind`; ainda ha manifests e snapshots gerados que carregam o campo. | Mantido como campo legado sincronizado; remocao fica condicionada a migrar manifests e snapshots. |
| `praxis-component-registry-rag.json` | deprecar como corpus canonico, manter como projecao compacta | O corpus canonico e `praxis-component-registry-ingestion.json` com `components[].chunks`; `generate-templates-plan.ts` ainda consome o formato compacto. | `generate-registry-rag.ts` agora prefere o registry de ingestao, emite metadata de compatibilidade/deprecacao e documenta o replacement canonico. |
| Docs normativas antigas | migrar guidance de fase | As docs historicas continuam uteis como registro, mas nao devem apresentar o RAG compacto como fonte primaria. | README do tooling e esta fase atualizados com a decisao canonica. |

## Patches executados

- `praxis-ui-angular/tools/ai-registry/generate-registry-rag.ts`: o gerador agora exige `REGISTRY_INGESTION_PATH` ou `dist/praxis-component-registry-ingestion.json` com `components[].chunks`, e marca a saida como `artifactRole=compatibility_projection`.
- `praxis-ui-angular/tools/ai-registry/generate-registry-rag.spec.js`: self-test portavel entre Windows/macOS/Linux e cobrindo a nova metadata de compatibilidade.
- `praxis-ui-angular/tools/ai-registry/README.md`: guidance atualizado para declarar o registry RAG compacto como projecao deprecated, nao corpus canonico.
- `praxis-ui-angular/tools/ai-registry/generate-templates-plan.ts`: mensagem de erro ajustada para deixar claro que o RAG compacto e input de compatibilidade do planner.
- `praxis-ui-angular/tools/ai-registry/generate-templates-plan.spec.js`: self-test tornado portavel para validar o planner no macOS sem `cmd.exe`.

## Validacao

- `node tools/ai-registry/generate-registry-rag.spec.js`
- `node tools/ai-registry/generate-templates-plan.spec.js`
- `git -C praxis-ui-angular diff --check`
- `git -C praxis-config-starter diff --check`

Nao foram executados testes Java porque nenhum endpoint/backend Java foi alterado nesta fase.

## Handoff para Fase 09

- Usar `praxis-component-registry-ingestion.json` e `components[].chunks` como fonte canonica para qualquer projecao OpenAI/Gemini.
- Tratar `praxis-component-registry-rag.json` apenas como compatibilidade local do planner ate `generate-templates-plan.ts` consumir diretamente o registry de ingestao.
- Nao projetar `/ai/patch` como API canonica nova; provider projection deve partir do authoring/retrieval granular e publicar apenas evidencias derivadas.
