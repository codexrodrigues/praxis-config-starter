# P3-C / #521 — uma tentativa dona por operação

Classificação: transversal/arquitetural interna. Baseline Config074cc0e8 (rc168),
Quickstart0d1c1956 (PR463), pin público168 integrado em466. Fonte canônica Config.

Inventário: UiLayoutValidationAttempt já define ticker, duração, saldo monotônico,
correlação, operação iniciadora e fechamento. Context.forPurpose retém tentativa;
Chain e NativeSnapshotReader reconstruíam tentativas com tempo restante. Comando
reservado, receipt read e verify de adapters abriam budgets novos. Admission inicial
rodava antes de beginValidation. Aderência: suportado-parcialmente/mal-materializado,
sem lacuna real de DTO, endpoint ou novo conceito de autorização.

Plano antes de editar:
1. Conservar o estado da tentativa dona em vistas internas correlacionadas. Permitir
   mudar composição, nunca ator/tenant/unidade/ambiente/contextVersion. Fechar vista
   não fecha pai; fechar dono invalida todas; não reconstruir now+remaining.
2. Propagar contexto explicitamente em overloads package-private de reserved draft
   e receipt. Escopo léxico interno somente para atravessar callbacks sem validation;
   guarda apenas tentativa, nunca principal/membership/grant/resultado. Restaurar por
   try/finally, sem herança para workers e sem reter ThreadLocal após retorno participante.
3. Colocar admission inicial dentro do budget; preservar callbacks de entrada/retorno/
   commit e reinstalar contexto capturado nos callbacks. Não renovar policy filha.
4. Host: fundir apenas a dupla admissão dentro de require; separar estrutura pura de
   autoridade, mantendo escopo/composição/attempt/contratos. Metadata/child/native/
   mapping/lifecycle continuam readmitidos no presente. Não aumentar40s ou cachear grant.
5. Testes determinísticos de relógio/fechamento/correlação, troca de autoridade,
   reentrância/exceção/thread, nestedcommand/read/admission/commit e rollback. Focais
   unit sem banco; todo banco real só Neon atribuído root. Depois candidato privado
   isolado, HTTP focal de criação/publicação e falha beforeCommit observada por outra
   conexão ordinária antes do cleanup; preservar negativos e parar para diagnóstico.
6. Revisão independente antes de merge/release Config, adoção pública e PR463. Prova
   privada não certifica pacote público. Se a prova exigir release compatível, usar
   workflow oficial autorizado pela jornada, sem retag/upload duplicado; conferir bytes.

Mapa de impacto: Config Attempt/Context e serviços de criação/lifecycle/bridge/native;
Quickstart providers/harness/pin/docs; skill canônica runtime-persistence e operational-
proof842 devem refletir a correção e provas. Hero/Angular/landing/corpus sem payload
novo; reavaliar derivados se surgir contrato público. Risco: expiração agora alcança
trabalho antes não contado e pode revelar custo restante. Não prometer40s end-to-end
só por compilação; não confundir budget cooperativo com cancelamento de SQL bloqueado.

Coordenação: root único escritor Config/harness/POM/docs/release/Maven/Neon. Agente
p3c_page_providers escreve só Host/Configuration e testes atribuídos; revisão independente
p3c_security_review somente leitura. Modelo/esforço herdados nos followups (sem override),
adequados a autorização/transação; decisões críticas com root. Instruções raiz/local e
/Users/rodrigo/Dev/pessoal/praxis-plataform/docs/engineering/agentes-e-modelos.md aplicadas.
Neon br-wandering-frog-adu4l0bu root exclusivo, c1 encerrada; nova atribuição/atestações
antes de conectar. Reusar P3-A/B/b1/b2 inalterados com fonte/limites; não repetir por reflexo.


Pré-ensaio:246 testes focais em13 classes PASS/0skip,21.467s, sem banco, após
corrigir fixtures UUID e contagens das travessias; resultados negativos preservados.
Revisão independente p3c_security_review aprovou desenho e diff para candidato
privado/ensaio, sem aceite funcional. Readmissão de metadata raiz/filho e fonte
filha agora é cercada por entry/return, inclusive após deadline expirar; estrutura
usa wrapper canônico. Lookup por chave preserva normalização anterior de erros.
Candidato terá GAV novo0.0.0-p3c.521-SNAPSHOT em cache isolado, nunca bytes alterados
sob168 pública. Host69 testes focais PASS; diff revisto estaticamente pelo peer.


## Resultado negativo c2 e limite de aceite

O ensaio privado c2 de 24/10/2026 usou Quickstart `78b473cd` e Config `d948c4d3`,
GAV `0.0.0-p3c.521-SNAPSHOT`, com JAR SHA256
`15b450bbb50717df052afb7a2ae7b0f04d27a77308a10f8ad981844e6f3763ed`.
A criação retornou 503 `SOURCE_UNAVAILABLE` em 41.275 ms. A deadline dona não foi
renovada, mas nenhuma criação 201, publicação ou observação independente de rollback
foi alcançada. A limpeza confirmou dois bancos e dois papéis removidos às 03:18:55 UTC.
O recibo negativo está preservado no Quickstart, em
`internal-planning/page-runtime/evidence/p3c-budget-521-20261024/c2-negative.json`.

A prova local não aceita a funcionalidade. O próximo passo é executar o gate focal
em runner remoto com os mesmos 40 segundos e verificações de autorização, atribuição
dedicada, SHA e JAR atestados e revisão independente antes do dispatch. O runner
Ubuntu não comprova localização ou proximidade ao banco. Sucesso privado ainda
exige adoção pública. A negativa não aprova release ou integração.

O preflight adicional do perfil oficial `ci-smoke-unit` passou com 3.692 testes,
sem falhas, erros ou casos ignorados, em 2min41s, sem banco. A fonte de produção
`d948c4d3` permaneceu inalterada. A execução antecipa regressões das entradas
lifecycle fora das 246 verificações focais; não substitui a prova HTTP/Neon
nem o build e a assinatura oficiais de release.


## Prova privada c3 aprovada

O [ensaio remoto 40059431500](https://github.com/codexrodrigues/praxis-api-quickstart/actions/runs/40059431500)
passou no mesmo Neon isolado, com Quickstart `7d729013` e Config `d948c4d3`.
O GAV privado exclusivo foi `0.0.0-page-40059431500-1-d948c4d39509-SNAPSHOT`;
seu JAR SHA256 é `d02bbbc052c25d4922b739e40a2d7f76c48c4da7783bdd50b3149212f7640ac6`.
A árvore `src/main` é `2df3d15ceb0066ebec9061eb7b3dff55ff3d1ea1`, idêntica
à correção revisada. A custódia confirmou o JAR efetivamente aninhado no host.

Criação 201 em 29.813 ms; leitura do draft 200; publicação pelos seis comandos
editoriais; documento publicado 200, condicional 304 e head confirmado por conexão
ordinária independente. A sequência editorial totalizou 83.195 ms: são seis
operações com orçamentos próprios de 40 segundos, não uma única janela de 40 segundos.

A falha deliberada antes do commit retornou 503 em 23.957 ms, depois de observar
catálogo, evidência de criação, draft e metadata preparados. Outra conexão ordinária
confirmou a ausência de todos, da chave e do head antes da limpeza. A mesma chave
criou novos IDs com 201 em 29.194 ms. Isolamento por tenant 404 e revogação atual 403
passaram. JUnit: um teste aprovado, sem casos ignorados. Dois bancos e dois papéis
foram removidos com seus OIDs confirmados, às 03:49:20 UTC. O secret temporário foi
removido; a atribuição básica do Quickstart permaneceu intacta.

Os seis recibos sanitizados estão no Quickstart em
`internal-planning/page-runtime/evidence/p3c-budget-521-20261024/c3-remote-private/`.
Root e `p3c_security_review` conferiram fonte, artefatos, fases e limpeza. Aceite
restrito ao candidato privado na topologia testada: permite integrar esta correção
e seguir o release compatível oficial. A negativa local c2 permanece válida; não
há promessa de SLA local. A integração Quickstart 463 ainda exige Config público,
POM sem override, nova prova pública e documentação/guidance finalizadas. Menu,
cópia, browser e deploy não foram aceitos por este ensaio.

## Correção de custo da prova de isolamento — 24/10/2026

Classificação: `arquitetural`, restrita à infraestrutura transacional interna do
Config. Inventário: `ja-suportado-mal-nomeado-ou-mal-materializado`; o readset já
fixava READ COMMITTED com `SELECT current_setting('transaction_isolation')`, mas
os demais guards repetiam `getTransactionIsolation()` (SHOW no PgJDBC). A fonte
canônica é `ConfigJdbcTransactionGuard`, consumida pelos ledgers de catálogo,
runtime, grupos de página e metadata. Não há novo SPI, DTO, endpoint ou grant.

O guard compartilha a prova pela sincronização da transação, datasource, holder e
conexão exatos. Cada acesso continua verificando transação ativa, autocommit,
rollback-only e identidade física JPA. `REQUIRES_NEW` suspende a prova; completion
encerra sua validade. Sem sincronização, permanece a inspeção JDBC fresca anterior.
A primeira prova só permite reutilização quando a lista de sincronizações ainda
está vazia; com callbacks anteriores, mantém leituras frescas pelo restante da
transação. Isso cobre o primeiro acesso ocorrido dentro de um callback já em
execução. `PageCreationService.transaction` chama `store.requireTransaction()`
antes de registrar suas sincronizações, preservando a otimização na transação nova
normal; participação em uma transação com callbacks prévios é conservadora.
O readset usa o mesmo guard. Nenhuma chamada de autorização, observação de SESSION,
lock, callback de extensão ou deadline de 40 segundos foi removida ou relaxada.

`savepointRollback` ocorre antes do rollback físico. Por isso, após esse callback,
proof e readset abandonam a reutilização pelo restante da transação: isolamento
é consultado em cada acesso, bytes são recarregados e não são retidos. Isso impede
que uma sincronização posterior reentre e preserve prova ou bytes pré-rollback.
Novos owners de readset também consultam a elegibilidade da prova compartilhada
antes/depois do loader: criação durante callback não restaura a retenção. Prefetch
continua verificando seus limites e a quantidade consumida.

Validação focal sem banco, em `bcb67869` mais este diff: 167 testes em dez classes,
zero falhas, erros ou skips. Comandos:

```sh
mvn -q -Dtest=ConfigTransactionReadSetTest,ConfigTransactionAbortTest,JdbcUiLayoutMetadataCaptureStoreTest,PageCatalogLifecycleBridgeTest,PageRuntimeReadServiceTest test
mvn -q -Dtest=PageCreationHttpTest,PageCreationCodecTest,PageRuntimeCursorTest,UiLayoutLifecycleAuthorityFreshnessTest,UiLayoutLifecycleCommandServiceTest test
```

O teste de consumo simula vinte ciclos com dois guards, `require`, flush, refresh
e dois owners de readset: uma consulta SELECT de isolamento e zero getters de
isolamento. Há provas adversariais de reentrada no rollback, troca de holder/JPA,
autocommit, isolamento incorreto, falha SQL, suspensão e término de transação.
São contagens determinísticas em JDBC simulado, não medição de latência ou prova
de isolamento PostgreSQL real. Os resultados C5 já coletados (312 snapshots em
31,052s; requisição CREATE503 em40,579s) continuam evidência histórica do gargalo;
este patch não mediu a contribuição dos SHOW nessa requisição nem demonstrou
criação Hero abaixo de40s. Não foi repetido Neon/HTTP, publicado ou implantado.

Revisão independente do diff atribuída a `creation_boundary_review`, com aceite
pendente do parecer final. Skills: `atualizar-existente`, porque o guidance em
`praxis-config-runtime-persistence/references/governed-layout-integrity.md`
ensinava limpeza de bytes sem renovação de admissão após savepoint. Sua atualização
canônica é coordenada separadamente no mesmo incremento. Angular, corpus HTTP e
payloads de examples não mudam; nenhuma sincronização desses derivados é necessária.
