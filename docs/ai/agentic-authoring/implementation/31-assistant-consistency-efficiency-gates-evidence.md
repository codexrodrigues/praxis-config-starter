# Evidência 31 — conformidade de schema e gates de eficiência do assistente

## Resultado

O smoke SSE e o corpus de consistência do assistente passaram a validar o que
a plataforma realmente governa, sem repetir campos de domínio no verificador:

- o plano mínimo é confrontado com o `/schemas/filtered` referenciado pelo
  próprio resultado;
- campos inventados, obrigatórios ausentes, semântica `required` perdida e
  pointers divergentes falham o smoke;
- o gate de consistência reutiliza a telemetria opt-in já presente no evento
  terminal para medir invocações, latência e tokens;
- uma fotografia versionada de preços transforma o uso observado em estimativa
  operacional de custo, sem alterar o contrato HTTP/SSE público;
- o perfil de release falha fechado quando a execução não possui chamada de
  provider bem-sucedida, uso contabilizável ou preço aplicável.

## Inventário de aderência

| Necessidade | Classificação | Decisão |
| --- | --- | --- |
| Validar o plano contra o schema do recurso | `ja-suportado-mal-nomeado-ou-mal-materializado` | Consumir `minimalFormPlan.fieldSelectionPlanRef` e `/schemas/filtered`, já canônicos. |
| Medir latência e tokens por turno | `ja-suportado-so-ux` | Projetar `decisionDiagnostics.providerTelemetry`, implementado no slice anterior. |
| Agregar os indicadores no relatório do corpus | `suportado-parcialmente` | Evoluir apenas o runner e seus relatórios derivados. |
| Estimar custo monetário de modelos | `lacuna-real-de-contrato` interno | Versionar fotografia operacional de preços; não adicionar campo ao runtime público. |

Não foi criada uma segunda ontologia de formulário, tabela ou domínio. A fonte
canônica dos campos continua sendo o metadata starter exposto pelo quickstart,
e o Config Starter apenas prova a aderência de sua decisão materializada.

## Gates de release

O perfil `RELEASE_GATE=true` preserva os limites já existentes de primeiro
feedback e terminalidade e adiciona limites por execução:

| Indicador | Limite padrão |
| --- | ---: |
| Primeiro feedback | 2 s |
| Orientação da plataforma | 12 s |
| Authoring | 45 s |
| Tokens totais | 12.000 |
| Custo estimado | 10.000 micros de USD (US$ 0,01) |

O custo estimado usa somente usage reportado pelo provider:

`input não cacheado × preço de input + input cacheado × preço cached input + output × preço de output`.

Os preços são armazenados por um milhão de tokens em
`provider-pricing-snapshot.v1.json`, com schema, instante de captura, moeda,
tier e URL da fonte. IDs datados de modelo são associados apenas por prefixos
declarados na fotografia. Ausência de usage ou preço impede aprovação no gate,
em vez de produzir custo zero enganoso. A fotografia é evidência operacional,
não contrato comercial, e deve ser atualizada quando a política de modelos ou
os preços do provider mudarem.

## Provas com OpenAI real

### Conformidade canônica do formulário

O smoke SSE real em `2026-07-16` terminou com compile, preview, apply, readback,
replay, bloqueio de ETag obsoleto, cleanup e stream aprovados. O plano usou os
campos canônicos do recurso de incidentes — `missaoId`, `descricao`,
`severidade`, `local`, `ocorridoEm`, `danosCivis`, `feridos` e `mortos` — sem
campos inventados ou obrigatórios ausentes. Evidência local:
`artifacts/local-e2e/agentic-http-sse-openai-20260716-105819`.

### Falha operacional detectada

Uma primeira execução do gate encontrou o quickstart empacotado com um JAR
anterior ao runtime corrente. A execução de formulário chegou a 101,184 s,
33.852 tokens e 15.215 micros de USD, após timeout do planner e resoluções
adicionais. O novo gate reprovou corretamente a execução. A comparação de SHA
do `AgenticAuthoringTurnEngine.class` confirmou o drift; o starter foi instalado
localmente e o quickstart reempacotado sem mudança em seu código-fonte.
Evidência local:
`artifacts/local-e2e/assistant-consistency-20260716-110205`.

Esse resultado é uma prova negativa do controle, não o baseline atual do
produto: demonstra que host desatualizado, latência anômala e consumo excessivo
agora são visíveis e bloqueantes.

### Pacote atual

Com o quickstart contendo o starter atual:

- orientação focal: 2/2, mediana 9,355 s, p95 10,758 s, 2.794 tokens e 663
  micros de USD no total;
- formulário focal transacional: 1/1, 24,533 s, 1.742 tokens e 994 micros de
  USD, incluindo apply, readback, replay, ETag obsoleto bloqueado e cleanup;
- gate de release com três repetições das três jornadas fundamentais: 9/9,
  mediana 8,028 s, p95 23,451 s, máximo de 1.743 tokens e 996 micros de USD por
  execução; as três jornadas transacionais passaram.

Evidências locais:

- `artifacts/local-e2e/assistant-consistency-20260716-110654`;
- `artifacts/local-e2e/assistant-consistency-20260716-110735`;
- `artifacts/local-e2e/assistant-consistency-20260716-110826`.

## Mapa de impacto

- fonte canônica afetada: nenhum contrato público; foram alterados runners,
  documentação, fotografia interna de preços e teste de governança;
- host de referência: quickstart foi apenas reempacotado localmente para a
  prova, sem alteração de fonte;
- consumidores: operadores e gates locais de release do assistente;
- docs públicas, Angular, landing page e corpus HTTP: sem sincronização
  necessária, pois rotas, payloads, eventos SSE e public APIs não mudaram;
- risco de breaking change: baixo e restrito ao comportamento fail-closed do
  runner quando `RELEASE_GATE=true`.

## Validações

- `bash -n` nos dois runners alterados;
- JSON Schema e JSON da fotografia validados por teste focal;
- teste de unicidade, sobreposição, preços positivos e cobertura do modelo
  padrão;
- gate completo `ci-smoke-unit`: 1.973 testes, sem falhas ou erros, além de
  JAR, sources e javadocs;
- smoke SSE OpenAI real schema-driven;
- corpus focal de orientação e formulário;
- release gate OpenAI real, três jornadas por três repetições.

## Próximos passos recomendados

1. Concluído: as seis jornadas `must-pass` passaram 18/18 e o perfil
   `extended` passou 12/12 depois da correção da variância de Table. Evidência
   em
   [`32-assistant-extended-consistency-evidence.md`](32-assistant-extended-consistency-evidence.md).
2. Comparar a versão compatível mais atual de Spring AI e a política de modelos
   em slice isolado, usando assertividade, p50/p95, tokens e custo deste gate;
   manter Spring AI 2/Boot 4 como spike arquitetural separado.
3. Certificar a mesma shell e o contexto mínimo assistível em Table e Dynamic
   Form e fechar a prova browser de UX do Page Builder.
4. Definir responsável e cadência de atualização da fotografia de preços e só
   ativar enforcement para outro provider depois de obter fonte oficial e
   cobertura equivalente.
