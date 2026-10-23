# P3-A — integridade transacional entre páginas e grupos

Implementação e gates entregues para revisão final. Integração Config e integração/sincronização do guidance permanecem atos da coordenação; este registro não antecipa merge.

Implementação de [Config #511](https://github.com/codexrodrigues/praxis-config-starter/issues/511), sobre P2 integrado em `78e914fdc1f03c7abf7e201fd2d2a79fc561f26f`. O [desenho](P3A-DESIGN.md) registra inventário, assinatura mínima e protocolo aprovado. Revisão e integração são registradas no [PR #518](https://github.com/codexrodrigues/praxis-config-starter/pull/518). Fonte de código/testes: `307e1079e08692279aa3b0b417441ff58a66b494`; logs, XML, fontes por rodada, dependências e artefato estão no [pacote auditável](evidence/p3a/README.md).

## Comportamento entregue

Publicar, retirar ou retroceder uma página ou grupos passa obrigatoriamente pelo owner interno do lifecycle. Sem esse owner, o core nega a operação mesmo com policy e validator permissivos. Uma página publicada só referencia grupos ativos e admitidos; um grupo usado por qualquer página ativa do escopo não pode ser omitido ou retirado. Uma página privada ou de outra unidade também conta. O scan interno não concede leitura do conteúdo dessas páginas ao ator.

Os heads continuam independentes. Rollback de A restaura sua estrutura completa e seus bindings; não altera B nem o head de grupos. Snapshot B0, patch selecionado, revisões, membros e capturas são correlacionados pelos verificadores existentes. O owner vincula explicitamente cada root ao KIND nativo esperado antes de executar uma única validação/reprodução correspondente; um descritor alheio não permite ignorar o contrato histórico. O estado final é readmitido no commit, incluindo metadata/producer/filhos, estrutura de página e READ_HEAD dos grupos dependentes. Contexto, mapping e grants atuais não são cacheados.

O único contrato novo é `PageCreationHost.navigationGroupsRoot(PageCreationContext)`, abstrato e server-only. O host atesta mapping estável por tenant/application/environment/namespace e sua inversa por `resolveLifecycle`; duas aplicações/namespaces não podem compartilhar o mesmo head físico tenant/environment/type/id. A factory beta `UiLayoutLifecycleCommandService.create` recebe o host; a assinatura anterior foi substituída, sem overload de compatibilidade. O ator da auditoria vem da invocation atestada, sem segundo argumento divergente. Não há endpoint, schema wire, flag, tabela de referência ou permit público novos.

## Locks, freshness e atomicidade

O comando planeja a união dos grupos do head anterior e do candidato, toma try-locks dos groupIds ordenados no escopo e depois o try-lock do root físico, inclusive quando o head não existe. Só então bloqueia/atualiza a entidade do próprio head. Ownwrites JPA são flushed antes da projeção JDBC; a conexão JDBC deve ser a mesma do EntityManager Config. O refresh pessimista é feito diretamente pela identidade autoritativa, evitando que uma locking query rejeite a versão antiga de uma entidade managed antes do refresh. ETag é comparado depois dessa leitura protegida.

Contenção ou alteração da base do plano invalida toda a transação Config. Uma exceção engolida ou rollback de savepoint não apaga o aborto global; o caller deve recarregar e repetir explicitamente em nova transação. Não há REQUIRES_NEW, retry interno ou registro de locks/autoridade. O teste JDBC de savepoint prova o helper transacional e não habilita nested JPA como contrato de negócio.

O beforeCommit verifica a invariável no estado final sob locks retidos, permitindo várias transições válidas do mesmo root na mesma REQUIRED. O attempt original não é renovado. O ensaio de três comandos usa orçamento explícito de 120 segundos e transação externa de 130 segundos; a operação completa mediu 35.815 ms; isso prova a lógica sob esse orçamento, sem afirmar que o default de produção suporta três jornadas remotas. Retorno de chamada participante continua provisório até o commit externo.

As provas distinguem falha após INSERT, revogação antes do commit e rejeição real do COMMIT. Nesta última, a fixture runtime insere catálogo sem evidence, usando a FK diferida V70 existente: o INSERT retorna, head/evento já foram escritos e a chamada real `Connection.commit()` recebe SQLSTATE `23503`. Uma conexão física nova comprova rollback de head, ETag, evento e catálogo órfão. Não foi necessário trigger de teste, owner DML ou alteração de produção para injetar a falha.

## Catálogo e capacidade observada

Grupos usam scan autoritativo keyset UUID no catálogo P2, em tenant/application/environment/namespace, sem OFFSET, filtro READ_PAGE ou truncamento. O lookup interno por página inclui namespace tanto no SQL quanto na chave do readset, além de application/tenant/environment/page. Isso preserva o isolamento na mesma transação e a resposta uniforme para UUID conhecido em outro escopo e UUID inexistente. O lookup por creationKey mantém seu escopo canônico ator/application/tenant/environment; sua semântica não foi ampliada. Heads nunca entram no readset imutável. Novas transições de página exigem catálogo P2; qualquer head ativo de página não atribuível ao catálogo no tenant/environment torna a checagem indisponível. Essa é uma pré-condição explícita de adoção/migração P5, sem inferência, backfill automático ou flag de compatibilidade.

Cada consulta seleciona um prefixo de até 128 candidatos e 8 MiB de texto de workspace medido por PostgreSQL. LEFT JOIN preserva origem ausente; o primeiro item sempre é retornado, mesmo maior que o orçamento, para progresso e negação canônica. O cursor usa a última identidade realmente retornada. Pesos são BIGINT e a soma ordenada usa o resultado NUMERIC de PostgreSQL. Os 8 MiB não limitam heap, patches, objetos Java ou tamanho total do catálogo. Revisões e outras entidades têm custo adicional; a consulta pode reler/descomprimir candidatos grandes que não couberam no prefixo.

O baseline com lotes de 32 expirou em 40.787 ms após 704 identidades do primeiro passe. A conexão nova confirmou rollback; nenhuma leitura parcial foi aceita. O ajuste de lote manteve o prazo de 40 segundos. A prova final usou mil páginas sintéticas válidas, construídas por transformação/evidência/codecs canônicos e inseridas pelo runtime com triggers ativos; não representa mil jornadas HTTP. A preparação levou 90.450 ms, fora da medição do scan.

| Medida | Publicação válida | Omissão bloqueada pela última página |
| --- | --- | --- |
| Tempo total da operação | 35.688 ms | 21.724 ms |
| SQL preparados / tempo JDBC | 165 / 25.032 ms | 89 / 14.714 ms |
| Consultas de isolamento / tempo | 52 / 7.425 ms | 30 / 4.548 ms |
| Identidades carregadas | 1.000 em cada um dos dois passes | 1.000 em um passe |
| Consultas de páginas | 18 | 8 |
| Texto de workspace carregado por passe | 7.381.000 bytes | 7.381.000 bytes |
| Texto de patches carregado por passe | 621.000 bytes | 621.000 bytes |
| Entidades managed: base / pico | 12 / 782 | 12 / 780 |

O maior lote observado carregou 944.768 bytes de workspace e 79.488 bytes de patches. A medição conta texto UTF-8 retornado e entidades managed, não bytes de rede, tamanho físico do PostgreSQL ou heap. Entidades sealed carregadas pelo scan são destacadas por lote; Definitions já managed antes do lote são preservadas, sem clear global. Os valores são evidência deste recurso/fixture, não SLA nem garantia universal de desempenho.

A prova SQL separada usa sete fixtures negativas de armazenamento: prefixos 2/1/1/1/2, limite exato de 8 MiB, primeiro item acima dele e release com sourceDraft nulo. Todos os IDs aparecem; os registros inválidos são negados pelo verifier. Essa prova não é parte da seed válida de mil páginas.

## Validação, recursos e limites

As rodadas estão vinculadas individualmente no manifesto. Contagens históricas se sobrepõem e não formam uma suite única na árvore final.

| Rodada | Fonte | Resultado |
| --- | --- | --- |
| Baseline unitário focal | `da6e7d7a` | 116 verdes no log; 101 XML de casos não substituídos preservados |
| Core após correção de freshness/precondição | `5d8fe117` | 16 verdes; substitui os 15 do core no baseline |
| Neon inicial | `f0444f04` | 3 passaram, 2 falharam e 1 erro; diagnóstico preservado |
| Correções dos três casos afetados | `5d8fe117` | 3 verdes |
| Corridas controladas | `ca7e72c0` | 6 verdes |
| Densidade baseline | `e565b4c6` | timeout diagnosticado; rollback comprovado |
| Prefixo SQL | `8a6d6484` | 1 verde |
| Densidade final | `8a6d6484` | 1 verde |
| Transação/rollback A7 | `8a6d6484` | 7 verdes |
| Isolamento/cópia/rollback B5 | `8a6d6484` | 4 verdes, 1 falha de namespace preservada |
| Binder histórico root/KIND | `307e1079` | 6 verdes; inicial positivo tinha patch fixture incompleto, preservado |
| Correção focal de namespace | `307e1079` | 1 verde em133,078s; SQL/readset e conhecido/desconhecido404 |

As seis corridas alternam vencedores controlados entre publish de página e omissão/rollback/withdraw de grupos. Barreiras da fixture observam duas conexões distintas e o mesmo bind após o try-lock real; head/ETag/evento do perdedor e ausência de órfãos são verificados por conexão nova. Não houve observação via pg_locks. Corridas gerais com múltiplos locks podem abortar ambos; o contrato exige integridade e retry explícito, não um vencedor universal.

A branch exclusiva foi `br-holy-water-a4ihmkkj` (`page-group-integrity-p3a-511-20261022`), projeto `ancient-leaf-72055027`, endpoint `ep-hidden-bread-a4x3zddv.us-east-1.aws.neon.tech`. Schema `p3a_group_511_20261022`, runtime `p3a_group_511_runtime`, PostgreSQL 17.11, TLS verify-full com TLSv1.3 e peer conferidos pelo JDBC real. O runtime não é owner/superuser/createdb/createrole/bypassrls; não cria schema, altera evidence ou remove catálogo. O preparo owner aplicou somente V64/V65/V66/V67/V68/V70 no schema atribuído. Não houve DDL P3 ou mutação do pai/schema herdado/recurso P2. Essa fixture focal não prova upgrade integral de todos os módulos.

O pacote não entrega P3-B, catálogo HTTP de navegação, adoção Quickstart/Hero, Angular/E2E, deploy ou release. A mudança de SPI é intencional na beta; os consumidores atuais inspecionados ainda não a adotam. Schema/corpus P0 não mudaram e não foram reexecutados por alteração apenas de implementação; os cenários HTTP continuam gates posteriores. A menor validação cobre core, conexão/aborto, HTTP mock, fontes congeladas e Neon real nos fluxos alterados.

Os rechecks de contexto, mapping e grants ocorrem no servidor durante a transação Config; não constituem uma transação atômica com autoridade externa ou outro banco. O host deve atestar contexto/versão/grants pelo contrato canônico. O deadline é cooperativo e não cancela I/O bloqueado.

A admissão P2 de producer continua aplicável às mutações de página, inclusive WITHDRAW. Este incremento não promete retirada emergencial com producer indisponível; qualquer exceção exigiria decisão canônica própria. Não existe bypass local dessa política.

Skills: `atualizar-existente` em persistence; a coordenação mantém o incremento canônico no PR 828 e registra seu aceite/sincronização. O head canônico entregue e revisado contra a fonte final é `930bb6a7c68b1a79d0697df440a6f5462a1d590a` ([PR 828](https://github.com/codexrodrigues/praxis-codex-skills/pull/828)); integração e sincronização seletiva ainda pendentes de registro pela coordenação. O pacote não declara concluída essa parte.

As conexões de teste foram encerradas pelos fixtures e os processos de teste terminaram. Schema e branch Neon ficam preservados para evidência, compute0,25–1CU/autosuspend300; não há autorização de reutilização/mutação por etapa futura sem nova atribuição. O artefato foi apenas empacotado localmente; nenhum install/deploy/release foi realizado.
