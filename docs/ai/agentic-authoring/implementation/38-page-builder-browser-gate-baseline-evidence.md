# Baseline browser do Gate D no Page Builder

Data do corte: 2026-07-16.

## Resultado

O primeiro gate local multiplataforma do Page Builder foi executado em macOS
ARM com Quickstart, Angular, Neon, OpenAI e stream SSE reais. A rodada aceita
fechou com `7 passed`, `2 skipped` e `1 flaky` recuperado pela unica
retentativa. O fluxo basico de dashboard com linguagem imperfeita passou em
`1,4 min` e a auditoria confirmou ausencia do caminho mock legado.

Esse resultado abre o Gate D, mas nao o fecha. A consistencia funcional do
backend continua certificada pelo gate `42/42`; o navegador revelou variancia
de Project Knowledge e problemas visuais que ainda impedem declarar a
experiencia pronta para demonstracao.

## Classificacao e inventario de aderencia

- runner local restrito a executaveis Windows:
  `ja-suportado-mal-nomeado-ou-mal-materializado`; o runner Node gerenciado ja
  existia e foi promovido como orquestrador multiplataforma;
- datasource da fixture nao projetado no processo Playwright:
  `suportado-parcialmente`; os valores canonicos do `.env.dev` ja existiam,
  mas nao atravessavam a fronteira do processo;
- preparo conjunto de Domain Catalog, API catalog e Vector RAG:
  `suportado-parcialmente`; as fases ja existiam, mas precisavam de controle
  independente para o smoke nao publicar `3.718` documentos vetoriais;
- truncamento de contexto, sobreposicao de loading/review e densidade do
  painel: `ja-suportado-so-ux`;
- fundo claro no cockpit Project Knowledge durante dark theme e mistura de
  ingles/portugues: `ja-suportado-mal-nomeado-ou-mal-materializado`;
- lacuna real de contrato publico: nenhuma.

## Evolucao do runner

O comando local canonico passou a:

- instalar o `praxis-config-starter` local;
- iniciar e encerrar Quickstart e Angular sem deixar portas abertas;
- carregar provider e datasources sem imprimir secrets;
- exigir Postgres remoto para o gate live;
- preparar a projecao de dominio de folha no smoke;
- atualizar o API catalog apenas para os seis recursos das jornadas;
- manter reingestao group-wide e Vector RAG como operacoes deliberadas;
- aceitar `smoke|full`, retries, grep e config Playwright;
- preservar screenshots, videos, traces e relatorio HTML.

Comando aceito:

```bash
cd ../praxis-ui-angular
./tools/local-e2e/run-page-builder-agentic-full-local.sh
```

Na repeticao focal, `PRAXIS_E2E_REFRESH_API_CATALOG=false` reutilizou o catalogo
acabado de carregar; isso nao e o default do gate.

## Evidencia executada

### Preflight e unidade

- `node --test scripts/run-page-builder-agentic-authoring-e2e.spec.js`: `2/2`;
- `node --check scripts/run-page-builder-agentic-authoring-e2e.js`: verde;
- `bash -n tools/local-e2e/run-page-builder-agentic-full-local.sh`: verde;
- `git diff --check`: verde.

### Browser live

- quatro cenarios de Shared Rules/cockpit passaram;
- Project Knowledge revert passou na retentativa depois de uma primeira
  amostra sem citacao da fixture;
- Project Knowledge change-set passou em `18,5 s`;
- dashboard de pagamentos com linguagem imperfeita passou em `1,4 min`;
- Fluxo 2 e PR7 permaneceram corretamente fora do perfil `smoke`;
- auditoria de ausencia de mock passou;
- portas `8088` e `4003` ficaram livres no teardown.

A primeira rodada negativa foi preservada: `5 passed`, `2 skipped` e `3
failed`. Ela revelou datasource literal na fixture e ausencia da projecao
minima do Domain Catalog. Depois das correcoes de orquestracao, esses defeitos
nao se repetiram.

## Baseline visual

As capturas desktop mostram que a semantica necessaria ja chega ao cockpit,
mas a materializacao visual ainda nao atende ao Gate D:

1. chips de contexto e labels longos ficam truncados no painel estreito;
2. candidatos de fonte ocupam grande parte do painel e escondem a conversa;
3. loading do formulario e estado de review aparecem simultaneamente sem uma
   hierarquia suficientemente clara;
4. o cockpit de Project Knowledge pode renderizar uma superficie clara dentro
   do dark theme;
5. mensagens e timeline misturam portugues e ingles;
6. status global `Carregando...` continua visivel em capturas terminais;
7. narrow viewport, teclado e AXE ainda nao foram certificados neste corte.

## Proximo corte recomendado

Corrigir primeiro a shell visual compartilhada, sem criar contrato novo:

1. overflow/wrapping e hierarquia do header/contexto;
2. separacao visual de progresso, preview e review terminal;
3. dark theme e i18n do cockpit Project Knowledge;
4. foco, teclado, AXE e viewport narrow;
5. nova repeticao smoke sem flaky e, depois, a matriz `full` com formulario e
   refinamento progressivo.

A variancia de Project Knowledge deve virar caso focal repetivel. O gate nao
deve esconder a primeira falha apenas porque a retentativa passou.

## Atualizacao de 2026-07-17 — orientacao consultiva responsiva

O fluxo production-like `O que eu posso fazer aqui?` passou com OpenAI,
Quickstart, Config Starter, Angular e SSE reais em `1,2 min`. Alem de validar a
resposta amigavel e as quick replies governadas sem patch aplicavel, o mesmo
caso agora certifica:

- shell totalmente contida em viewport `390x844`;
- ausencia de overflow horizontal no documento;
- quick replies visiveis e alcancaveis apenas com `Tab`;
- nome acessivel nao vazio no alvo de teclado.

A suite focal da shell compartilhada passou `59/59`, incluindo clamp de
viewport, wrapping do contexto governado e resize handles fora da arvore de
acoes acessiveis. A classificacao de overflow/wrapping e narrow viewport muda
de pendencia funcional para `ja-suportado-so-ux` certificado no fluxo
consultivo. A auditoria AXE continua pendente porque o workspace ainda nao
possui essa dependencia; este corte nao altera configuracao de pacotes.
