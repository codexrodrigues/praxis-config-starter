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

Revisão independente: `creation_boundary_review` aprovou tecnicamente o diff e
conferiu os relatórios XML (167/0/0/0) e `git diff --check`. Esse parecer não aprova
o prazo Hero, Neon, adoção no host ou release. Skills: `atualizar-existente`, porque o guidance em
`praxis-config-runtime-persistence/references/governed-layout-integrity.md`
ensinava limpeza de bytes sem renovação de admissão após savepoint. Sua atualização
canônica é coordenada separadamente no mesmo incremento. Angular, corpus HTTP e
payloads de examples não mudam; nenhuma sincronização desses derivados é necessária.


## Admissão conjunta de criação — etapa de custo, 24/10/2026

Classificação `contrato-publico`/`transversal`. Inventário: as três políticas já
existem; a `lacuna-real-de-contrato` é uma unidade SPI explícita que permita ao host
autorizar conjuntamente CREATE_PAGE, READ_SOURCE e COPY_SOURCE sem três transações.
Dono: `PageCreationHost.requireCreation`, com ponte e checkpoints em Config;
consumidor: `QuickstartPageHost`, fixtures implementadoras e decorator diagnóstico.
Não mudam DTOs, HTTP, schema, Angular ou corpus; o SPI Java beta exige adoção conjunta.

O Quickstart avalia as três políticas puras na mesma `authorizeCurrent`. Mantém duas
observações frescas, SESSION/membership locks, expiração e coerência pós-política.
A unidade tem uma linearização conjunta; revogação entre suas políticas espera seu
fim, enquanto entre checkpoints/I/O continua sendo observada. Não há cache de grants.
Config mantém os seis checkpoints e os checks de budget na entrada/saída da unidade.
As admissões de captura, proveniência, metadata, filhos e extensões não foram agrupadas.

Prova offline: 24 testes Config (`PageCreationAdmissionCheckpointTest`,
`PageCatalogLifecycleBridgeTest`, `PageCreationHttpTest`, `PageCreationAutoConfigurationTest`)
e 46 Quickstart (`QuickstartPageHostTest`, `QuickstartPageNeonDiagnosticsTest`,
`EnterpriseRuntimeConfigurationTest`), zero falhas/erros/skips. A fixture Config executa
sequenciamento real de completion Spring com armazenamento sintético; uma negativa
em qualquer checkpoint 1–6 impede commit e retorno bem-sucedido, inclusive após receipt
provisório. Isso não prova rollback PostgreSQL. O teste host usa serviço de autorização
real e transação sintética: três chamadas isoladas fazem 6 snapshots/3 commits;
a unidade faz 2 snapshots/1 commit, com negativas e erro na segunda observação.

Dependência privada usada apenas em testes: `io.github.codexrodrigues:praxis-config-starter:0.1.0-creation-admission-SNAPSHOT`.
O POM público permanece rc170. Nenhum banco, HTTP, release ou deploy foi executado.
C5 (Config169) e Hero com Config170 (503 em41,085s; lookup404) permanecem negativos.
Os oito HOST_REQUIRE de C5 são compatíveis com só os dois checkpoints iniciais mais
READ/COPY da captura: esta etapa reduz quatro admissões nesse prefixo, não demonstra
criação completa abaixo de40s. A redução integral desta unidade em uma criação
completa é18→6 transações e36→12 snapshots; o fanout de proveniência continua pendente.


## Proveniência nativa conjunta — segunda etapa de custo, 24/10/2026

Classificação `contrato-publico`/`transversal`. Inventário: Config já conhece a origem,
metadados e conjunto exato de filhos; Quickstart já valida produtor, metadata da raiz,
origem e metadata do filho em `QuickstartPageDocuments.requirePage`. A lacuna real é a
unidade SPI que permita realizar essas políticas puras dentro da mesma admissão atual.
`PageCreationHost.requireNativeProvenance` é o dono canônico; não há novo DTO ou camada
de evidência. Todos os implementadores de host, fixtures e decorator foram atualizados.

Config escolhe a unidade somente para Native com raiz e todos os filhos não gerados.
São duas chamadas independentes, antes e depois dos validadores estruturais. Checks de
reprodução, sintaxe, correlação e conjunto exato dos filhos permanecem em Config. Cada
chamada Quickstart usa duas observações frescas, sob os locks operacionais existentes;
não há cache de grants, redução do orçamento de observações nem eliminação de checkpoint
após extensão. A linearização é conjunta para as políticas da unidade, não idêntica aos
interleavings anteriores. Captura Native com raiz ou filho gerado mantém o caminho
anterior completo e a recursão Config. A origem Published não seleciona esta unidade;
somente seus ancestrais efetivamente Native podem ser elegíveis.

O diagnóstico acrescenta `HOST_REQUIRE_NATIVE_PROVENANCE`; não reclassifica recibos
históricos. Para uma proveniência Native com um filho, os quatro callbacks anteriores
se tornam duas unidades, com quatro observações atuais no total em vez de oito. No
prefixo histórico C5 houve 21 verificações, correspondentes a 42 admissões/84 observações
a menos nesse trecho sob a nova forma. É contagem estrutural, não medição de latência,
nem previsão de sucesso em 40s. O número total de verificações de uma criação completa
continua sem medida. C5 e o Hero rc170 continuam negativos e não foram repetidos.

Provas focais finais: Config 99 testes em sete classes e Quickstart 49 em três classes,
zero falhas/erros/skips. Os testes são offline, com transações e armazenamento sintéticos;
nenhuma prova PostgreSQL/Neon,
HTTP, release ou deploy é reivindicada. Skills runtime-persistence e security-config:
`atualizar-existente`, com dono separado no mesmo incremento. Não mudam HTTP, DTOs,
payloads, corpus ou Angular; nenhum derivado dessas superfícies requer sincronização.


Comandos finais (JDK21; GAV privado somente para o consumidor):

```sh
mvn -o -q -Dtest=PageCreationAdmissionCheckpointTest,PageCatalogLifecycleBridgeTest,PageCreationHttpTest,PageCreationAutoConfigurationTest test
mvn -o -q -Dtest=PageRuntimeReadServiceTest,UiLayoutLifecycleAuthorityFreshnessTest,UiLayoutLifecycleCommandServiceTest test
mvn -o -q -Dpraxis.config.version=0.1.0-creation-admission-SNAPSHOT -Dtest=QuickstartPageHostTest,QuickstartPageNeonDiagnosticsTest,EnterpriseRuntimeConfigurationTest test
```

Os testes exercitam os seis checkpoints de criação com falha em cada posição e impedem
commit/resultado sintético; produtor, metadata raiz, origem do filho e metadata do filho
adulterados; duas observações por unidade; conflito/expiração pós-políticas; revogação
por qualquer validador estrutural observada na segunda admissão; orçamento na entrada
e saída de ambas as admissões; raiz gerada com recursão e filho gerado sem ledger rejeitado.
A fixture de metadados continua consumindo o orçamento original compartilhado.

Baseline Config `3666986eb1c8029bf2304688e205fbffc879985a`, Quickstart
`687a1fda274bb38c1f3ff3feedbd17392c17a0f6`, mais este incremento. O rebuild privado offline
usou `-DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true install` com troca
temporária/restaurada somente da versão do POM. SHA256 do JAR privado desta árvore:
`6c31b1b9d8251766d8f563fb3dc4562de7f93b196c8f65b0bb69d9e3587e004a`.
O POM público não mudou. Relatórios XML ficam em `target/surefire-reports` de cada
worktree `page-creation-admission-unit`. Revisão independente `creation_boundary_review`:
APTO técnico às etapas 1+2, com XML 29 Config +49 Quickstart conferidos; consumidores
adicionais Config somaram70 testes passando. `git diff --check` passa nos dois repos.
Isso não é aprovação de SLA, banco remoto, release ou adoção operacional.


Integração local posterior: rebase sem conflitos sobre Config
`0eaf2f747f49a67e263c449613bc42c2f1d4c2e4` (rc171) e Quickstart `1f1e7327`.
`git range-diff` confirmou patch idêntico antes/depois nos dois repositórios. O avanço
Config toca somente authoring/intent/compiler e versão POM; Quickstart acrescenta
apenas documentação/evidências. Os 99+49 testes acima continuam atribuídos à base
rc170, sem reexecução automática. O rebuild integrado privado, com testes pulados,
passou; novo JAR SHA256 `f0986f6252ff759fe6252d69038de36dd6ef582c37acc9b16171606b712f386c`.
Não houve instalação/publicação sob coordenadas rc170/rc171.
O consumidor integrado também passou `mvn -o -q
-Dpraxis.config.version=0.1.0-creation-admission-SNAPSHOT -DskipTests test-compile`.
Esse resultado comprova compilação na nova composição; não é nova execução dos testes.

## Percurso completo rc172 e plano de ownership de evidências — 24/10/2026

Novo negativo operacional informado pelo coordenador: Hero com Config172 retornou503
SOURCE_UNAVAILABLE em40,790s; não foi repetido por este trabalhador. C5 continua um
prefixo interrompido de Config169, não uma medida do percurso completo rc172.

`PageCreationFullPathCostTest`, baseline `eb5e492c68b87550f480ddb2129033f96589a20f`,
executa criação real pelo PageCreationService, bridge, lifecycle command/read/core,
stores JDBC e readset, com Spring DataSourceTransactionManager. Connection, JDBC
transport e repositórios JPA são doubles; não há banco. A fonte tem uma página Native
com um filho Form. Os2testes passam, incluindo negativa na primeira autoridade de
beforeCommit após escritas provisórias e sem commit físico simulado.

Contagem completa:218unidades de autoridade (180body+38beforeCommit), sendo
resolve19, resolveLifecycle87, requireCreation6, READ_SOURCE1, COPY_SOURCE1,
captureNative1, nativeProvenance84, lifecycleCREATE16 eREAD3. As84admissões Native
correspondem a42travessias: metadata19, child10, lifecycle8, workspaceCapture1 e
captura/criação/commitCheck4. Transporte simulado:1SELECT de isolamento,2leituras de
ledger,1leitura de metadata,1owner-read,3inserts,2draft-lookups,1draft-write e1commit.

As436observações são somente modelo2x por unidade, não chamadas medidas ao reader
Quickstart. O exemplo de250ms/unidade é peso arbitrário de sensibilidade (54,5s),
não medição, extrapolação quantitativa de C5 ou previsão SLA. A fixture não usa sleeps.
Relatório reproduzível: `target/page-creation-full-path-cost.json`, contendo contagem
e caminhos de métodos sem identidades/credenciais/conteúdo. Comando focal:
`mvn -o -q -Dtest=PageCreationFullPathCostTest test`.

Plano aprovado antes de editar produção (`contrato-publico`/`transversal`): corrigir
ownership existente de validação de evidências, sem reter grants. O store package-private
passa a declarar `readVerified`; JDBC continua validando binding/integridade e duas
admissões no codec, eliminando a segunda verificação consecutiva nos callers. Todos
os stores de teste devem respeitar esse contrato. No verificador de filhos, retirar
somente a admissão explícita imediatamente anterior à primeira admissão do codec,
preservando os dois checks deste. A seam pública do source passa a admitir fonte e
metadata completas do filho numa unidade explícita; o bridge Config correlaciona o
filho exato e percorre sua criação uma vez. Não há alias de compatibilidade ou grant
cache. Consumidor Quickstart e todas as fixtures precisam migrar no mesmo incremento.

Inventário: o store já entrega evidência verificada, mas o caller repete o mesmo codec
sem I/O entre ambos (`ja-suportado-mal-materializado`); source e metadata possuem todos
os dados do filho, mas falta a unidade canônica de admissão conjunta
(`lacuna-real-de-contrato`). HTTP/DTOs/Angular/corpus não mudam. Skills persistence e
security-config exigem revisão no mesmo ciclo. Testes mínimos: corrupção/binding,
negação em ambos os checks, fonte/metadata de filho adulteradas, generated/mixed/
published e nova contagem completa. A previsão estrutural218→158 precisa ser medida
na fixture após implementação e ainda não estabelece margem operacional em40s.


Etapa 3 implementada sobre rc172: o store `readVerified` é o único dono do codec de
leitura (duas admissões atuais e binding/integridade), incluindo fixtures; append mantém
as verificações anteriores/posteriores ao SQL. O codec de filho mantém suas duas admissões,
sem o preflight duplicado. `requirePageChildEvidence` exige fonte e metadata completas
na mesma decisão; a implementação Config verifica também a página exata e todas as partes
do seed gerado antes de percorrer a criação. Quickstart executa a policy completa dentro
do authorizeCurrent existente, com as duas observações frescas e expiração/coerência.

Nova contagem completa após implementação: 158 unidades, contra 218 da baseline:
resolveLifecycle 57 (antes 87), nativeProvenance 54 (antes 84), demais unidades e transporte
simulado inalterados. As 27 travessias restantes continuam checando autoridade atual.
As 316 observações e 39,5s com peso arbitrário de 250ms são apenas modelos, sem prova de
reader, transporte ou SLA. Os seis checkpoints de criação permanecem. Não houve novo
POST, banco, release ou deploy. A redução exige avaliação adicional de margem antes de
qualquer nova prova operacional.

Validação Config: 135 testes únicos passaram nos XML de `page-creation-cost-map`:
JdbcUiLayoutMetadataCaptureStoreTest 52; UiLayoutPageChildCapturesTest 7;
PageCatalogLifecycleBridgeTest 19; UiLayoutLifecycleCommandServiceTest 18;
PageRuntimeReadServiceTest 13; UiLayoutPageGroupSnapshotContractTest 6;
UiLayoutLifecycleHttpRoundTripTest 18; PageCreationFullPathCostTest 2.
Incluem corrupção/binding persistidos, ambas as negativas do codec, metadata do filho
adulterada, página/source/descriptor/documento/metadata gerados divergentes, caminhos
mixed/published, revogação por extensão e rollback tardio. Os consumidores Postgres foram
migrados e compilados; não executados, conforme limite explícito sem novo ensaio Neon.

Consumidor etapa 3: 52 testes Quickstart PASS (host 26, diagnostics 11, wiring 15), com
`-Dpraxis.config.version=0.1.0-page-evidence-admission-SNAPSHOT`. JAR privado SHA256
`9d04c099ca8a96263f6e7ed8f20261c27bf334acfb6faa0c2773124b01814c4e`;
nenhuma coordenada pública sobrescrita. Relatórios da etapa foram preservados em
`/tmp/praxis-evidence-admission-stage3` antes da próxima mudança.

Plano etapa 4 aprovado antes de editar (`contrato-publico`/`transversal`): o mesmo bean
UiLayoutLifecycleAdmission configurado deve implementar o subtipo PageLifecycleAdmission,
que admite toda a política de operação/domínio e devolve contexto de aplicação/namespace
numa janela atual. A lacuna é o retorno conjunto hoje materializado em duas admissões
consecutivas sem I/O. Não ignorar/substituir outro delegate: configuração sem subtipo falha.
Targets Page (inclusive UUID ainda não catalogada) usam requirePage antes de lookup; demais
targets continuam require. Preservar reobservação pós-byPage, correlação exata de invocation,
namespace do ledger, verificação de origem e admissão final. Quickstart extrai sua policy
existente para as duas entradas, sem duplicar vocabulário nem reduzir regras. Todos os cinco
pontos de composição canônica e fixtures devem consumir o mesmo bean. Validação: plain bean
rejeitado, custom operation denied, non-page delegation, unknown-page ordering, final denial,
null/context/namespace mismatch e nova contagem completa; estimativa 158→139 ainda pendente.


Etapa 4 implementada e contada na mesma fixture completa: **139 unidades** =119 corpo +20
beforeCommit. São 19 resolve, 38 resolveLifecycle, 6 requireCreation, 1 READ_SOURCE,
1 COPY_SOURCE, 1 captureNative, 54 nativeProvenance e 19 PageLifecycleAdmission (16 CREATE_DRAFT
+3 READ_DRAFT). O total mudou de218→158→139; SQL simulado permanece igual. Os278 snapshots
são modelo2x, e34,75s a250ms/unidade é somente sensibilidade arbitrária. O limite real de40s
não mudou. A prova Hero rc172 de40,790s continua negativa; a árvore nova ainda não foi usada
em banco, HTTP operacional, release ou deploy.

Stage4 Config: bridge23, composição5 e checkpoints3 PASS; full path2 PASS na árvore nova.
Reutilizam-se os demais testes válidos da etapa3, totalizando147 testes únicos Config.
A composição rejeita o mesmo bean plain, preserva custom domain denial antes do catálogo,
delega non-page, não pula UUID desconhecida, rejeita null/invocation/namespace divergentes e
observa revogação final após travessia. A mudança de linearização é explícita: as duas policies
consecutivas compartilham uma janela, sem reter autoridade após a chamada. JAR privado final:
`3b39fd7dc668d2f0d9bbca025f0dd06a3752127a6c540f2f6304e23af9c657ba`, coordenada
`0.1.0-page-evidence-admission-SNAPSHOT`; POM público permanece rc172.


Consumidor final: 56 testes Quickstart PASS (QuickstartPageHostTest29,
QuickstartPageNeonDiagnosticsTest12, EnterpriseRuntimeConfigurationTest15) com o GAV privado
acima; projetor offline `test_corporate_page_focal.py`14 PASS. O wrapper de diagnóstico preserva
PageLifecycleAdmission e a identidade do resultado; a policy original e conjunta são comparadas
em todas as operações/roles, incluindo negação e expiração/coerência na observação posterior.
Comandos incrementais finais, todos offline e sem banco:

```sh
mvn -o -q -Dtest=PageCreationFullPathCostTest,PageCatalogLifecycleBridgeTest,PageCreationAutoConfigurationTest test
mvn -o -q -Dtest=PageCatalogLifecycleBridgeTest,PageCreationAutoConfigurationTest,PageCreationAdmissionCheckpointTest test
mvn -o -q -Dpraxis.config.version=0.1.0-page-evidence-admission-SNAPSHOT -Dtest=QuickstartPageHostTest,QuickstartPageNeonDiagnosticsTest,EnterpriseRuntimeConfigurationTest test
mvn -o -q -Dpraxis.config.version=0.1.0-page-evidence-admission-SNAPSHOT -Dtest=QuickstartPageNeonDiagnosticsTest test
python3 -m unittest discover -s scripts/workspace -p test_corporate_page_focal.py
```

O rerun isolado de diagnostics corrigiu duas falhas de fixture (mock de record final e overload
Mockito inferido num ternário); não houve ajuste de produção para fazer passar. Não se repetiram
suites já válidas sem delta. Os relatórios XML e recibo final ficam em
`/tmp/praxis-evidence-admission-final`; a evidência intermediária permanece em
`/tmp/praxis-evidence-admission-stage3`. Skills runtime-persistence/security-config têm
atualização canônica atribuída ao revisor de guidance no mesmo incremento; integração desse
artefato e adoção operacional são gates separados da conclusão técnica local.
