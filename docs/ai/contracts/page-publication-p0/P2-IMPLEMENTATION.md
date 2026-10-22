# P2 — criação governada, catálogo de identidade e cópia independente

Implementação da issue [Config #510](https://github.com/codexrodrigues/praxis-config-starter/issues/510), incluindo as costuras compartilhadas de [#509](https://github.com/codexrodrigues/praxis-config-starter/issues/509). Estado: implementação sob revisão; gates finais e aceite ainda pendentes. Nenhum merge, deploy ou release é autorizado por este artefato.

O [desenho aprovado](P2-DESIGN.md) registra inventário de aderência, alternativas rejeitadas, assinaturas e decisões de coordenação. A base integrada é `468f9de696330be2ee16d181c5145cfe849dc29b`; os checkpoints de revisão são `1b060a8e` (costuras/guard transacional) e `b4a176e7` (bridge, HTTP e wiring). A fonte final e os hashes da evidência serão adicionados após os gates.

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

O readset privado reutiliza apenas bytes positivos imutáveis dentro da mesma transação Config e conexão, até 16 entradas/8 MiB com eviction e releitura. Não armazena misses, autoridade, contexto, grants ou resultados de admissão. Suspensão/REQUIRES_NEW têm conjuntos próprios; conclusão limpa o conjunto. A reprodução B0+patch selecionado, os hashes e todos os callbacks atuais continuam obrigatórios. Lookup por creationKey permanece SQL. A admissão do readset executa SELECT current_setting do isolamento uma vez (READ COMMITTED), que fixa a transação PostgreSQL; cada hit ainda verifica identidade JDBC/JPA, autoCommit e rollback. O require completo dos stores de mutação/metadata permanece inalterado.

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

Gate final em andamento. Os resultados finais, comandos, SHA de fonte/dependências/artefato e logs/XML sanitizados serão vinculados aqui. Checkpoints anteriores não representam aceite da árvore final.

A política operacional fecha cadeia em 16 nós, 8 MiB distintos de ledger e 2.048 checkpoints por travessia. O prazo monotônico externo não renova em fontes internas; respostas tardias e callbacks fora do orçamento são rejeitados. Isso não cancela I/O bloqueado. O orçamento do host é obrigatório; a fixture de teste não define um default de produção.

Unitários cobrem corpus P0 (incluindo os dois exemplos de cópia), ingresso bruto, source/receipt, invariantes de transformação, adulteração, prefixo sem ledger, ciclo, limite de cadeia/bytes, prazo externo, isolamento de app e grafo de auto-configuração. Neon cobre JPA/JDBC real, concorrência igual/divergente, lost response, falha após cada INSERT, rollback-only, revogação tardia, novo autor/contexto, cópia histórica e lifecycle de grupos.

Não foi rodada suite integral, host real Quickstart, UI/P3/P4 ou release. A menor validação suficiente é focada nos fluxos e stores alterados. Artefatos públicos operacionais dos consumidores permanecem gates posteriores; a documentação Config e o OpenAPI local descrevem este corte. Skills: persistence `atualizar-existente` entregue pelo coordenador nos incrementos canônicos, incluindo PRs 823/824 no estado `de0121b`, com sincronização seletiva concluída; domain-decisions `existente-adequada`. A revisão independente do código e dos resultados ainda é requisito para aceite.
