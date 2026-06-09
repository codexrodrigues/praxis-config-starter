# Runtime Related Surface Preview Release Hardening

Data: 2026-06-09

## Veredito

O corte de runtime related surface esta aprovado para release hardening e para
preview tecnico controlado. Este documento nao autoriza nova capacidade
funcional; ele congela o estado validado e define o roteiro minimo para
apresentacao/publicacao preview.

## Escopo validado

- Observacoes runtime frontend entram como evidencia nao confiavel.
- O backend aterra `GroundedRuntimeComponentContext` antes de responder,
  resolver intencao, planejar tools ou produzir evidencia terminal.
- `runtimeToolPlan` governa disponibilidade, list, detail, summary e compare
  sobre superficies relacionadas.
- `runtime-tool-policy:multi-tool-readonly-beta` e selecionada somente por
  configuracao backend-owned e limita reads read-only governados.
- `runtimeRelatedSurfaceReads[]` e a evidencia canonica para leituras
  relacionadas; o alias singular nao aparece em multi-read.
- Summary, compare e detail derivam apenas de reads sanitizados, com
  projection/redaction reconciliados por backend.
- Quick replies e follow-ups multi-turn carregam decisoes/contexto governados,
  mas diagnostics historicos continuam `grounding_only` e nao autorizam reads.
- Replay/idempotencia e eventos SSE duplicados sao diagnosticados como
  tecnicamente replay-safe, sem indicar execucao adicional.

## Gate oficial de preview

O gate local de fechamento e a bateria real curta do workspace Angular contra
Quickstart real e Neon/Postgres remoto configurado localmente:

```bash
cd praxis-ui-angular
npm run smoke:runtime-tool-plan:readonly:short -- --timeout-ms 240000
```

Pre-condicoes:

- Quickstart real em `http://localhost:8088`.
- Angular real em `http://localhost:4003`.
- Neon/Postgres remoto configurado no `.env` local do Quickstart.
- `PRAXIS_AI_AUTHORING_RUNTIME_TOOL_POLICY_REF=runtime-tool-policy:multi-tool-readonly-beta`.
- Para o smoke temporal, usar tambem a policy backend-owned de smoke temporal
  documentada em `agentic-authoring-streaming.md`.

Baseline aprovado em 2026-06-09: bateria oficial `15/15`, `0` retries, sem
smoke/Chromium pendurado e portas `4003`/`8088` livres ao final.

Cenarios cobertos pelo gate:

- `multi-read`
- `summary-governed`
- `detail-governed`
- `detail-ambiguous`
- `detail-target-explicit`
- `detail-option-selection`
- `detail-followup-context`
- `list-followup-context`
- `summary-followup-context`
- `compare-blocked`
- `compare-redacted-dimension`
- `compare-governed`
- `compare-temporal-governed`
- `compare-temporal-missing-type`
- `fail-second-surface`

## Roteiro de demo recomendado

Para execucao operacional, prompts copiaveis e criterios visuais, use
[24-runtime-related-surface-demo-operator-playbook.md](./24-runtime-related-surface-demo-operator-playbook.md).

1. Abrir o Page Builder IA na recipe `mission-command-center`.
2. Selecionar uma missao na tabela principal.
3. Perguntar quais superficies relacionadas estao disponiveis para a selecao.
4. Pedir uma listagem governada de participantes e eventos relacionados.
5. Pedir um resumo governado das superficies relacionadas.
6. Pedir um compare governado entre participantes e timeline.
7. Pedir detalhe de forma ambigua e mostrar as quick replies governadas.
8. Escolher a opcao de detalhe e mostrar que o follow-up executa apenas o read
   reconciliado.
9. Fazer um follow-up natural logo depois da ambiguidade e mostrar que o
   contexto historico ajuda o grounding, mas nao autoriza sozinho.

## Limites do preview

- `runtime-tool-policy:multi-tool-readonly-beta` e beta e deve permanecer
  backend-owned.
- O budget e pequeno por desenho: no maximo duas leituras read-only para
  multi-surface e exatamente uma leitura para detail/list/summary direcionados.
- Compare so emite fact kinds backend-reconciled e permitidos pela
  `allowedFactKinds`.
- `temporal_coverage` exige dimensao temporal reconciliada (`date` ou
  `date-time`) em todas as superficies comparadas.
- Diagnostics historicos de desambiguacao sao `grounding_only`; eles ajudam o
  classificador, mas nunca substituem `DETAIL_TARGET_SURFACE_REF`,
  `LIST_TARGET_SURFACE_REF` ou `SUMMARY_TARGET_SURFACE_REF` reconciliados pelo
  backend.
- O gate oficial e local-first. GitHub Actions deve ficar para fechamento de
  fase/publicacao quando houver autorizacao explicita.

## Recomendacao antes de publicar

Nao abrir novos fact kinds ou novas capacidades de leitura antes da
apresentacao. O proximo trabalho deve ser apenas:

- release notes/changelog;
- roteiro de demo;
- revisao de docs publicas;
- empacotamento preview;
- gate final local com backend baseline, smoke real oficial e `git diff --check`.
