# Preparação da jornada real — 2026-09-05

O corte foi reconciliado e validado localmente. O bloqueio imediato do Render é operacional:
o login da aplicação retornou HTTP 500 e o log correspondente confirmou `OutOfMemoryError: Metaspace`.
O serviço sobrescreve o Dockerfile com um limite de Metaspace de 160 MiB. Não houve inferência,
criação/edição de registros, apply, alteração de modelo, deploy ou mudança de variável no Render.

## Corte isolado e inventário de aderência

Classificação: `transversal`. A preparação usa os contratos existentes e preserva as bases originais
sujas. O diretório de trabalho é `.worktrees/free-authoring-live-20260905/`, contendo:

| Owner | Base imutável | Impacto |
| --- | --- | --- |
| Config | `5c6cd02b` | Resolução semântica, lineage, preview, projeção de transformações, admissão e replay |
| Angular | `87fd2f257` | Normalização canônica de `required`; Page Builder atual já preservava decisão após apply |
| Landing | `c4440e5` | Status/modelo efetivo, readback/ETag, canary completo e regressões browser |
| Quickstart | `0361e6cd698bda033a8a57816afe1200f2332634` | Empacotamento do host implantado contra o Config reconciliado; source sem alterações |

| Necessidade | Aderência | Decisão |
| --- | --- | --- |
| Constraints e lineage | `suportado-parcialmente` | Reconciliar o resolver e preservar os avanços recentes; não copiar source antigo em bloco |
| Admissão concorrente e retry | `suportado-parcialmente` | Lock transacional da conversa e recuperação da decisão admitida originalmente |
| COUNT e required | `ja-suportado-mal-nomeado-ou-mal-materializado` | Projetar a medida retornada no chart e o required do objeto no field |
| Modelo da lane | `ja-suportado-mal-nomeado-ou-mal-materializado` | Consumir status canônico; remover constante de modelo da landing |
| Transformações para query context | `ja-suportado-mal-nomeado-ou-mal-materializado` | Preservar `TransformOutputHint` já consumido pelo Core |
| Jornada paga | `suportado-parcialmente` | Estender o runner existente com escopo próprio, autenticação, limites, observação e cleanup |
| Login HTTP 500 | `ja-suportado-mal-nomeado-ou-mal-materializado` | Alinhar override JVM do Render ao Dockerfile já publicado |

Não foi criada uma segunda semântica de intenção, DTO, endpoint, versão de contrato ou fachada entre
libs. Config continua dono de decisão/persistência e Core da compatibilidade de portas e schema.
Os consumidores afetados são Page Builder, Dynamic Form, charts/list/table e a landing. Docs de schema,
streaming e jornada foram atualizadas. Manifests e public APIs foram revisados e não exigem novos
inputs, exports, capabilities ou catálogos: as correções materializam contratos já existentes.

Riscos comportamentais: decisões antigas passam a ser recusadas na admissão; o replay exato continua
idempotente e vinculado ao principal. `requiresFullIntentResolution=true` pode provocar uma chamada
que o atalho anterior evitava, conforme a decisão explícita do planner. Não se promete serialização
de toda execução de turnos já admitidos. O modelo/provider do backend não foi alterado.

## Mudanças reconciliadas

- Preservação de predicados, tuple semântica, exclusões e resource grounding após a resolução completa;
  continua obrigatório resolver o alvo de recurso relacionado.
- Lineage dos filhos consultativos e continuidade de decisões; fingerprint recente que inclui principal
  e lógica de refinamento já existente na base foram preservados.
- Lock de conversa na admissão/publicação de resultado e replay com o id da decisão inicial, inclusive vazio.
- COUNT de entrada HTTP sem campo permanece distinto do campo de medida retornado para o chart.
- Required estrutural do JSON Schema materializado no field; `x-ui.required=false` não relaxa o schema.
- `output.semanticKind=query-context` e `stableShape=true` publicados pelo planner/normalizador e
  preservados pelo compilador. O Core continua rejeitando portas incompatíveis.
- Landing aguarda status de IA válido e readback; projeta modelo/provider efetivos, restaura página/ETag,
  protege reset contra leitura tardia e habilita diagnósticos pelo input já existente.

A conciliação usou merge de três vias sobre o baseline sujo preservado da primeira implementação,
seguido de revisão manual. Os arquivos originais monitorados nos três repositórios permaneceram
byte a byte iguais ao início desta sessão (`original-preservation.json`). Nenhum arquivo de configuração
Angular, package manifest, lockfile ou dependência declarada foi alterado.

## Evidência local

- **755 testes Java, 15 classes, zero falhas/erros**, executados em grupos focais: resolver, engine,
  preview, compiler, planner, SSE/persistência, replay, dois domínios e concorrência PostgreSQL.
- **23 testes Core** de normalização.
- **11 cenários browser aprovados**: cinco de composer/status/readback, dois de tabela com três turnos
  e edição, quatro de form/dashboard em dois domínios. São simulações HTTP/provider com componentes
  reais e fixtures produzidas pelo compilador Java atual; não são inferência real.
- **8 testes do runner operacional**: gates de autorização/orçamento, proteção contra interceptação,
  escopo de URLs, SSE, cancelamento, ausência de counters e estimativa com snapshot canônico.
- Source audit: testes do auditor e auditoria dos serviços reais aprovados.
- Builds de Core, Dynamic Form, Page Builder e landing aprovados. Os consumidores usaram os pacotes
  compilados do corte em diretórios isolados, sem modificar tsconfigs/mappings.
- Quickstart `2.0.0-rc.46` empacotado com Config reconciliado: JAR aninhado idêntico byte a byte ao
  artefato produzido. Cache Maven isolado, sem substituir o artefato rc.147 no cache global.
- Canary pago: descoberto pelo Playwright e **skipped sem opt-in**. Nenhuma alegação de aprovação live.

As primeiras execuções capturaram incompatibilidades reais: testes antigos que ignoravam a flag da
LLM e transformações de dashboard sem tipo de saída. Os testes foram reexecutados após as correções.
O build inicial de consumidores sem os pacotes internos falhou; após instalar os artefatos no checkout
isolado, Dynamic Form/Page Builder/landing passaram sem alterações de configuração.

O novo helper `scripts/workspace/validate-free-authoring-live-preparation.sh` reproduz os grupos
locais com um diretório de checkouts explícito e exclui o canary pago. Seu `bash -n` passou; o helper
agregado não foi reexecutado porque os mesmos grupos focais já haviam sido validados.

Limites: não foi iniciado o Quickstart empacotado, executada uma suite integral, certificada seleção
por teclado de pontos do gráfico ou feita reconciliação de billing. Houve inspeção visual das capturas
narrow, além da prova automatizada de tabulação, foco e Escape já presente no portfólio.

## Bloqueio Render e correção operacional pronta para revisão

No [log do serviço](https://dashboard.render.com/web/srv-d3qjqcemcj7s73bpluv0/logs?t=app&r=1h&q=ERROR),
em **2026-09-05 20:13:30 UTC**, o login que retornou 500 aparece com:

```text
jakarta.servlet.ServletException: Handler dispatch failed: java.lang.OutOfMemoryError: Metaspace
Caused by: java.lang.OutOfMemoryError: Metaspace
```

O [ambiente do Render](https://dashboard.render.com/web/srv-d3qjqcemcj7s73bpluv0/env) tem este override:

```text
-XX:+UseContainerSupport -XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=25.0 -XX:MaxMetaspaceSize=160m -Dfile.encoding=UTF-8
```

O Dockerfile do commit já implantado define corretamente:

```text
-XX:+UseContainerSupport -XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=25.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8
```

**Ação proposta:** substituir o valor de `JAVA_OPTS` no serviço por esse valor do Dockerfile e reiniciar
o serviço, mantendo o mesmo modelo, chaves, plano de compute e demais variáveis. Essa ação ainda não
foi executada: reinicia produção e exige autorização explícita. Um restart sem remover o override não
resolve a causa. A alteração permite que o limite de memória do container governe Metaspace, mas não
é garantia de memória ilimitada; validar login e estabilidade após a reinicialização.

A credencial de aplicação foi localizada nos arquivos ignorados. O retorno 500 **não certifica nem
invalida a senha**; a autenticação só estará provada após login e `/auth/session` bem-sucedidos. A leitura
do schema revelou também que cargo/departamento usam `optionSource.filterEndpoint` via POST;
o runner foi alinhado a esse contrato, não a GET no endpoint legado.

O aviso de cobrança pendente do Render permanece visível. Nenhum dado financeiro foi acessado ou
alterado. As abas temporárias foram fechadas.

## Próxima execução

1. Autorizar e aplicar a correção exata de `JAVA_OPTS`; verificar health, login/session/logout e logs.
2. Publicar o corte reconciliado pelos fluxos oficiais e compor a nova release do Quickstart, após pedido
   explícito. Os JARs locais mantêm as versões das bases para validação; não devem sobrescrever releases
   existentes no Maven Central.
3. Atualizar/verificar o snapshot canônico de preços na data da execução, confirmar os controles de gasto
   e definir autorização/teto USD para uma sessão, três pedidos e os registros descartáveis.
4. Executar uma única vez o comando documentado em `praxis-ui-landing-page/docs/decision-playground-live-journey.md`.
   Interromper diante de clarificação, modelo divergente, custo desconhecido ou falha; analisar o recibo,
   sem repetição automática.

A skill canônica de authoring generativo recebeu guidance focal de preparação live, telemetria e
transformações; somente a referência idêntica instalada foi sincronizada. Os scripts de sync/bootstrap
não existem neste checkout. A skill de operações de provider está instalada, mas sua fonte canônica
não consta do `codex-skills/` disponível; essa lacuna de distribuição foi registrada, sem copiar uma
skill inteira do ambiente local como se fosse fonte versionada.
