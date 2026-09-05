# Fonte semantica persistente do UiCompositionPlan

## Objetivo

Este corte torna reabrivel a fonte semantica especifica de uma pagina criada
pelo authoring governado. O runtime continua consumindo o
`WidgetPageDefinition` de `ui_user_config.payload`; o editor pode recuperar, na
mesma revisao e pelo mesmo GET, o `UiCompositionPlan` que originou essa
materializacao.

O owner canonico e `praxis-config-starter`. Angular, Page Builder, host e
aplicacoes consumidoras nao devem criar outro store de planos.

## Inventario de aderencia anterior

| Capacidade existente | Evidencia | Classificacao anterior |
| --- | --- | --- |
| Templates reutilizaveis de composicao | `ai_registry`, escopo `SYSTEM/GLOBAL` | `suportado-parcialmente` |
| Plano completo emitido pelo authoring | evento terminal `result.preview.uiCompositionPlan` | `suportado-parcialmente` |
| Pagina executavel persistida | `ui_user_config.payload` via `page-apply` | `suportado-parcialmente` |
| Linhagem de apply | tags com stream, thread, turn, result e decisao | `ja-suportado-mal-nomeado-ou-mal-materializado` |
| Fonte especifica da pagina, duravel e reabrivel | inexistente depois da expiracao do stream | `lacuna-real-de-contrato` |

Templates nao resolvem essa lacuna: sao receitas reutilizaveis, enquanto o
plano aplicado contem as decisoes concretas daquela tela. O event log tambem
nao e um store de authoring duravel, pois possui expiracao operacional.

## Decisao canonica

`ui_user_config` passa a manter dois documentos alinhados pela mesma versao e
ETag:

- `payload`: materializacao executavel `WidgetPageDefinition`;
- `authoring_source`: envelope server-attested com o plano semantico,
  identidade, materializacao e proveniencia.

O `page-apply` extrai a fonte exclusivamente do evento terminal autorizado. O
request do cliente nao aceita `authoringSource`; portanto o browser nao pode
forjar um plano ou sua linhagem. O insert/update grava payload e fonte na mesma
linha e na mesma operacao de persistencia.

```json
{
  "schemaVersion": "praxis.ui-authoring-source/v1",
  "kind": "ui-composition-plan",
  "source": {
    "version": "1.0",
    "kind": "praxis.ui-composition-plan",
    "widgets": []
  },
  "sourceSha256": "<sha-256 canonico>",
  "materialization": {
    "kind": "widget-page-definition",
    "componentType": "praxis-dynamic-page",
    "componentId": "<identidade da pagina>",
    "sha256": "<sha-256 canonico do payload>",
    "profileId": "<quando emitido>",
    "catalogReleaseId": "<quando emitido>",
    "builderVersion": "<quando emitido>"
  },
  "provenance": {
    "streamId": "<uuid>",
    "threadId": "<uuid>",
    "turnId": "<uuid>",
    "resultEventId": "<uuid>",
    "semanticDecisionId": "<id>",
    "templateRef": {
      "registryKey": "<template canonico>",
      "configSha256": "<hash da revisao>",
      "version": 1,
      "etag": "<etag>"
    }
  }
}
```

`diagnostics` e evidencia volatil do turno, nao semantica autorada. Ele e
removido antes de persistir e antes de calcular `sourceSha256`. Referencia de
template e linhagem ficam em `provenance`, fora da identidade funcional do
plano. O hash de `materialization` e calculado sobre a projecao publica segura
do payload, exatamente como ela e devolvida ao Angular. Ciphertext e plaintext
de credenciais nao entram no documento reaberto nem tornam o hash impossivel
de validar. Isso permite ao consumidor provar que fonte e materializacao
publica continuam alinhadas.

### Serializacao canonica compartilhada

Os dois hashes usam SHA-256 sobre UTF-8 de uma serializacao JSON canonica, e
nao sobre o texto recebido nem sobre a formatacao padrao de cada runtime:

- propriedades de objetos sao ordenadas pelo nome; valores nulos de objetos
  nao participam da identidade, preservando a semantica do cliente;
- arrays preservam ordem e posicao, inclusive valores nulos;
- strings e nomes de propriedades usam escaping JSON;
- numeros usam a representacao finita IEEE-754 compativel com
  `JSON.stringify`: zero e `-0` materializam `0`, notacao exponencial e usada
  para expoentes menores ou iguais a `-7` e maiores ou iguais a `21`, e o
  expoente positivo inclui `+`;
- `NaN` e infinitos sao rejeitados, porque nao possuem representacao canonica
  no contrato.

Angular e Config devem conservar os mesmos vetores dourados para os limites
decimal/exponencial, fracoes, `-0`, UTF-8, arrays e objetos aninhados. Uma
mudanca unilateral no algoritmo e breaking change de integridade, mesmo que o
wire format do envelope continue igual.

## Invariantes de integridade

- Um plano terminal presente com `kind` ou `version` invalidos falha fechado;
  nao e degradado silenciosamente para uma fonte local.
- Um fluxo governado antigo que emite somente patch continua aplicavel, mas
  retorna o warning `ui-composition-authoring-source-not-issued` e nao afirma
  possuir fonte reabrivel.
- `createAuthored` e `upsertAuthored` sao chamadas internas do owner e exigem
  um objeto JSON de fonte.
- Um PUT generico nao pode atestar nova fonte. Se o payload mudar, a fonte
  anterior e removida atomicamente; se o payload for materialmente identico,
  ela pode ser preservada.
- Rotacao ou remocao administrativa de credenciais tambem muda o payload fora
  de um novo resultado de authoring e, por isso, invalida a fonte anterior.
- Payload e fonte possuem limites independentes de 256 KiB e passam pela mesma
  protecao de segredos antes da persistencia e da resposta.
- A atestacao de `sourceSha256`, identidade e hash de materializacao e refeita
  no owner de persistencia; um caller interno nao pode persistir hashes stale.
- `GET /api/praxis/config/ui` e a leitura canonica e agora retorna o campo
  aditivo `authoringSource`; `payload`, versao e ETag permanecem inalterados.

## Impacto e compatibilidade

- Owner: `praxis-config-starter`, migration `V61` e baseline novo.
- Consumidores diretos: Page Builder e loaders de configuracao em
  `praxis-ui-angular`.
- Prova operacional: `praxis-api-quickstart` deve validar apply, GET, reopen e
  reapply usando PostgreSQL/Neon ou banco local compativel.
- Docs/playgrounds: os exemplos oficiais precisam ensinar reopen pela fonte e
  nunca reconstruir o plano a partir do payload compilado.
- Wire format: mudanca aditiva; consumidores que ignoram campos desconhecidos
  continuam funcionando.
- Risco principal: apresentar fonte obsoleta. A invalidacao em escrita
  generica e os hashes canonicos existem especificamente para bloquear isso.

## Validacao deste corte

O gate focal deve cobrir:

1. criacao atomica de payload e fonte;
2. apply atestado pelo evento terminal;
3. remocao de diagnostics da identidade persistida;
4. copia segura da referencia de template;
5. rejeicao de plano terminal malformado;
6. preservacao da fonte em escrita generica sem mudanca de payload;
7. invalidacao da fonte em escrita generica que muda o payload;
8. round-trip de `authoringSource` pelo endpoint de leitura.
9. migration idempotente e constraint de objeto em PostgreSQL real.
10. os mesmos vetores SHA-256 no runtime Java e no verificador Angular.
11. payload com credencial protegida continua reabrivel pela projecao publica,
    sem expor ciphertext e sem gerar divergencia de hash.

## Continuidade semantica depois do reopen

O refinamento de uma configuracao `update` agora resolve a fonte no backend a
partir da identidade exata de `agenticApplyTarget` e do principal autenticado.
Antes de entregar o plano ao provider, o Config valida o ETag base, o escopo, o
ambiente, a identidade do componente, `sourceSha256`, o hash do payload
persistido e a igualdade entre esse payload e `currentPage`. O hint
`uiCompositionAuthoringSource` do browser e removido do contexto e nunca e
tratado como autoridade.

Providers que recebem uma fonte persistida valida precisam devolver outro
`UiCompositionPlan`. Retornar somente um patch de pagina nesse caminho falha
com `persisted-ui-composition-refinement-plan-required`. Operacoes de
componente que ainda nao possam ser projetadas no plano tambem falham
explicitamente, em vez de remover a fonte semanticamente sem aviso.

O piloto deterministico cobre `apply -> GET/reopen -> set_chart_type -> compile
-> apply com If-Match -> GET`: o segundo plano preserva `widgets`, `bindings`,
`state` e `canvas`, altera somente o chart selecionado, produz novos hashes e
proveniencia e rejeita a reaplicacao com o ETag anterior sem substituir a
versao vencedora. Casos de fonte forjada, identidade divergente, hash de fonte
ou materializacao divergente, pagina corrente divergente, ETag stale e usuario
diferente falham fechados.

## Proximo gate obrigatorio

O owner Java e o piloto deterministico nao equivalem a certificacao
operacional publicada. O proximo gate e executar a mesma jornada por HTTP real
no `praxis-api-quickstart`, com PostgreSQL compativel e o consumidor Angular
real. O provider OpenAI entra somente como canario final, depois dos gates
deterministicos; indisponibilidade de LLM nao bloqueia persistencia, reopen,
integridade, compilacao nem concorrencia. Somente depois dessa prova e dos
gates corporativos dos cinco arquetipos novas integracoes devem voltar ao
Ergon.
