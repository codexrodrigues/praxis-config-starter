# P2 — criação governada, catálogo de identidade e cópia independente

Implementação da issue [Config #510](https://github.com/codexrodrigues/praxis-config-starter/issues/510), incluindo as costuras compartilhadas de [#509](https://github.com/codexrodrigues/praxis-config-starter/issues/509). Implementação entregue; gates e limites abaixo. Revisão e integração são registradas no [PR #517](https://github.com/codexrodrigues/praxis-config-starter/pull/517); adoção por hosts depende de P3/P4. Este artefato não autoriza release ou deploy.

O [desenho aprovado](P2-DESIGN.md) registra inventário de aderência, alternativas rejeitadas, assinaturas e decisões de coordenação. A base integrada é `468f9de696330be2ee16d181c5145cfe849dc29b`; os checkpoints de revisão são `1b060a8e` (costuras/guard transacional) e `b4a176e7` (bridge, HTTP e wiring). A fonte final de código/testes é `fef0cb2cf31ad0bd9be6fc0424dcdf41b92283f0`; logs, XML e hashes estão no [pacote de evidência](evidence/p2/README.md).

## Contrato entregue

- `POST /api/praxis/config/pages`: recebe o comando P0 bruto e fechado. `201` cria; `200` recupera a criação do mesmo comando sob autoridade atual; comando diferente na mesma chave retorna `409`. O ETag é o validador forte atual do draft. `Location` inclui o root necessário para abrir o workspace. Todas as respostas de criação/reconciliação são `no-store` e não geram `304`.
- `GET /api/praxis/config/pages/by-creation-key?creationKey=…`: reconcilia identidade original por ator+app+tenant+environment. Revalida READ_CREATION e READ_DRAFT inclusive no commit. Não exige READ_SOURCE do material histórico nem retorna ledger ancestral. Uma ausência não prova falha de comando ainda em trânsito. Se o COMMIT já ocorreu mas sua resposta ultrapassa o prazo, o serviço retorna SOURCE_UNAVAILABLE sem receipt: isso é resultado incerto, não rollback. O cliente preserva creationKey e reconcilia com lookup/replay idêntico, sem gerar outra chave.
- Criação e replay exigem CREATE_PAGE, READ_SOURCE e COPY_SOURCE da fonte selecionada, rechecados após locks, após extensões e antes de commit. As cópias independentes não exigem ACL de leitura/cópia de cada ancestral; produtor, reprodução e dependências de domínio/metadata continuam admitidos sob a política atual.
- Fonte publicada usa release/revision exatas, composição congelada, capturas originais e evento histórico de publicação. O head corrente não substitui a seleção. Release apenas congelada, nunca publicada, não é fonte válida.
- UUID da página, widgets e links são novos. O mapa é tipado; endpoints de links usam o mapa de widgets. A transformação `praxis.page-copy/1` preserva autoria suportada, configurações evoluídas dos filhos e strings comuns, mas limpa navegação. Dados/runtime, grants, assignments, reviews, approvals e head não são herdados.

Não foram criados endpoints de publicação paralelos. Descoberta paginada, resolução publicada, catálogo efetivo de grupos e integrações host/UI permanecem no corte P3/P4. O catálogo guarda apenas identidade, namespace e criação; título, ícone e status continuam projeções das revisões e heads autorizados.

## Integração do host

`PageCreationHost` é server-only, sem default permissivo. `resolve(Principal, contextVersion)` atesta app e namespace registrados além da invocation existente. `resolveLifecycle(invocation)` reatesta independentemente o contexto atual nas callbacks sem Principal; uma invocation recebida não é credencial. A comparação ocorre antes da leitura app-scoped e do rebasing. O host mantém seus delegates originais de lifecycle, estrutura, fonte e metadata.

A auto-configuração só instala a fronteira páginas com o grafo completo do lifecycle disponível. Os quatro adapters do PageCreationService retornam interfaces existentes e não são novos beans concorrentes. Command/read são suppliers para quebrar a dependência de construção, testados com inicialização efetiva e primeiro request; a ausência ou incompletude do host não expõe um controller inutilizável.

Cada novo draft recebe uma observação atual. A evidência original e o output produzido pelo Config continuam imutáveis. O prefixo de produtor Config sem ledger não é admitido, mesmo com delegates permissivos. O B0 nativo histórico vem do texto preservado da captura; JSONB da workspace não substitui a ordem exata exigida por NativeIdentity.

## Persistência e transação

`V70__create_page_catalog_and_creation_evidence.sql` cria catálogo e evidência append-only, chave única de criação no escopo e vínculos diferidos com o draft. A mesma transação Config contém catálogo, evidência, draft e capturas; o lock é transacional. `ConfigJdbcTransactionGuard` compartilha com o store de metadata a prova de READ_COMMITTED, conexão JPA/JDBC idêntica e estado não rollback-only. Nenhuma autoridade fica em ThreadLocal, cache de registro ou tabela paralela de head/título. V70 também sela UPDATE/DELETE de draft já RELEASED e TRUNCATE, reutilizando a função canônica de imutabilidade; a escrita final DRAFT→RELEASED permanece permitida. Isso protege o documento congelado e as capturas embutidas para todos os tipos de root.

O readset privado reutiliza apenas bytes positivos imutáveis dentro da mesma transação Config e conexão, até 16 entradas/8 MiB com eviction e releitura. Não armazena misses, autoridade, contexto, grants ou resultados de admissão. Suspensão/REQUIRES_NEW têm conjuntos próprios; conclusão e rollback para savepoint limpam os bytes. O callback de savepoint não renova a prova de conexão/isolamento e não introduz suporte a nested JPA no lifecycle canônico. A reprodução B0+patch selecionado, os hashes e todos os callbacks atuais continuam obrigatórios. Lookup por creationKey permanece SQL. A admissão do readset executa SELECT current_setting do isolamento uma vez (READ COMMITTED), que fixa a transação PostgreSQL; cada hit ainda verifica identidade JDBC/JPA, autoCommit e rollback. O require completo dos stores de mutação/metadata permanece inalterado.

Criação e lookup conservam o mesmo attempt até o commit real. Quando participam de uma transação Config externa REQUIRED, o retorno é provisório; beforeCommit e afterCommit ainda validam o prazo, e afterCompletion encerra o attempt. Na transação própria há também checagem após execute, antes de fechar. Uma resposta tardia após COMMIT produz SOURCE_UNAVAILABLE, embora os dados possam estar persistidos: recuperar com a mesma creationKey, sem alegar rollback nem gerar outra chave.

O ensaio remoto usa exclusivamente:

| Recurso | Identidade |
| --- | --- |
| Projeto Neon | `ancient-leaf-72055027` |
| Branch atribuída | `br-aged-bonus-a4rhfqwp` / `page-publication-p2-510-20261022` |
| Endpoint direto | `ep-withered-rice-a48n0xi0.us-east-1.aws.neon.tech` |
| Schema | `p2_page_510_20261022` |
| Runtime | `p2_page_510_runtime` |
| Owner | `neondb_owner`, usado somente no preparo do schema atribuído |
| Transporte/versão | PostgreSQL 17.11, TLS verify-full; cliente comprovou TLSv1.3 |

O runtime não é owner, superuser, createdb, createrole ou bypassrls; não pode criar schema, atualizar revisões/evidências ou remover catálogo. A branch pai e schemas herdados não foram mutados. Foram aplicadas apenas V64/V65/V66/V67/V68/V70 ao schema atribuído; isso é fixture focal e não prova de upgrade integral de todos os módulos.

## Costuras de grupos

O codec P1-D integrado valida a seed antes da primeira escrita, a projeção e a reprodução de revisões/frozen. As capturas de metadata do root são revalidadas em revisão/freeze e no commit; a última extensão é seguida pela revalidação do contexto/autoridade. Histórico preserva evidência original e não recaptura fonte.

A resolução usa o mapping host existente, com um único app-shell UUID estável por tenant/app/environment/namespace. Shape UUID sozinho não é registro. O teste operacional exerce o provider real via commands, reuso estável, segundo UUID e dimensões desconhecidas. `UiLayoutLifecycleService` bloqueia publish/withdraw/rollback de app-shell até P3-A, inclusive chamadas diretas com policy e validator permissivos e sem PageCreationHost. Não há flag de ativação.

## Validação e limites

O [manifesto auditável](evidence/p2/manifest.json) vincula fonte, árvore, hashes por arquivo/rodada, 208 dependências efetivas, artefato e logs/XML sanitizados. As rodadas são separadas e suas contagens se sobrepõem:

| Rodada | Fonte | Resultado |
| --- | --- | --- |
| Baseline focal | `17592e69` | 206 (192 unitários + 14 Neon), zero falhas/erros/skips |
| Lifetime/savepoint | `cd91f4d0` | 13 unitários, zero falhas/erros/skips |
| Entryflow final | `8cc699ec` | 20 (16 Neon + 4 HTTP), zero falhas/erros/skips |
| Concorrência com barreira | `fef0cb2c` | 2 Neon focais, zero falhas/erros/skips |

Não são 206 testes executados na árvore final. Após o baseline mudaram apenas PageCreationService e ConfigTransactionReadSet em produção, com os fluxos afetados cobertos pelas rodadas posteriores. O JAR local `0.1.0-rc.166`, construído em cd91, tem SHA-256 `9dac94dbcda6999d91d150ac8ac1f1ecdb5744ed10ab60f51fc4a2f6caacd91f`; `src/main` e `pom.xml` são idênticos em cd91/fef. Não houve install/deploy.

A barreira de concorrência é exclusiva da fixture: duas conexões subjacentes distintas devem chegar ao mesmo escopo/chave antes de executar qualquer lock, com espera limitada. As contagens são verificadas após ambas as futures; comandos iguais retornam created true/false, e divergentes têm um vencedor e um conflito. Não foi observada espera via pg_locks. O diagnóstico inicial de cópia publicada excedeu 40 s (46.743 ms/295 SQL preparados); a prova final mediu 6.535 ms/24 SQL preparados, 3.441 ms em JDBC, com o mesmo orçamento de 40 s. Isso não estabelece SLA de produção.

A falha denominada commit na fixture ocorre em beforeCommit, após as escritas, e prova rollback dessa callback. Não foi simulada rejeição do JDBC COMMIT pelo servidor ou validado rollbackOnCommitFailure. O cenário de resposta tardia delega um COMMIT real bem-sucedido e só depois atrasa a resposta, comprovando recuperação do resultado incerto pela mesma chave.

A política operacional fecha cadeia em 16 nós, 8 MiB distintos de ledger e 2.048 checkpoints por travessia. O prazo monotônico externo não renova em fontes internas; respostas tardias e callbacks fora do orçamento são rejeitados. Isso não cancela I/O bloqueado. O orçamento do host é obrigatório; a fixture de teste não define um default de produção.

Unitários cobrem corpus P0 (incluindo os dois exemplos de cópia), ingresso bruto, source/receipt, invariantes de transformação, adulteração, prefixo sem ledger, ciclo, limite de cadeia/bytes, prazo externo, isolamento de app e grafo de auto-configuração. Neon cobre JPA/JDBC real, concorrência igual/divergente, lost response, falha após cada INSERT, rollback-only, revogação tardia, novo autor/contexto, cópia histórica e lifecycle de grupos.

Não foi rodada suite integral, host real Quickstart, UI/P3/P4 ou release. A menor validação suficiente é focada nos fluxos e stores alterados. Artefatos públicos operacionais dos consumidores permanecem gates posteriores; a documentação Config e o OpenAPI local descrevem este corte. Skills: persistence `atualizar-existente` entregue pelo coordenador nos incrementos canônicos, incluindo PRs 823/824/825 no estado `7988937c2b2fbb02ae569257da0e987fa783203a`, com sincronização seletiva concluída; domain-decisions `existente-adequada`. A revisão independente e a integração do pacote são registradas no PR #517. Processos/aplicações/conexões dos testes foram encerrados; schema e branch Neon ficam preservados para evidência com autosuspend300, sem autorização de reutilização por P3 sem nova atribuição.
