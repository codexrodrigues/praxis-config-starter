# P3-B — matriz de validação

Estado: implementação em revisão e campanha Neon ainda não executada. Produção congelada em `d7b572ad369f24f74f3aeb59e47bbf540df2f24d`, após pareceres independentes do coordenador e peer. O SHA de cada ensaio incluirá as fixtures e testes executados; a referência de produção não substitui essa proveniência.

## Recursos e fronteiras

- Worktree `config-page-p3b-512`; Maven exclusivo `/tmp/praxis-page-p3b-512-m2` e target próprio.
- Neon `ancient-leaf-72055027`, branch exclusiva `br-weathered-mountain-a48o0map`, endpoint direto `ep-floral-voice-a40bufyv.us-east-1.aws.neon.tech`.
- Schema `p3b_runtime_512_20261023`, runtime `p3b_runtime_512_runtime`, somente V64–68/V70 atuais. Preparo separado dos testes, sem DDL automática nem reutilização de recursos P2/P3-A.
- API confirmou ADMIN/PG17; SQL confirmou PG17.11 e owner `neondb_owner` não-superuser. Runtime não possui SUPERUSER, BYPASSRLS, CREATEDB, CREATEROLE, schema CREATE, evidence UPDATE ou catálogo DELETE. Permissões de authoring são restritas às tabelas e colunas existentes.
- O primeiro preparo abortou antes de DDL ao exigir `pg_stat_ssl.ssl=true`. Esse dado descreve o backend e retornou false, enquanto libpq `verify-full` confirmou TLS do cliente. Segunda execução preserva ambos; handshake independente validou hostname/CA e certificado TLS1.3 do endpoint. A campanha exigirá também `SSLSocket`/peer real do JDBC, não apenas parâmetros da URL.
- Segredos ficam em arquivo temporário privado, fora de logs, fontes e manifests. Preparo concluído e conexões encerradas. A branch permanece preservada com autosuspend 300 segundos; nenhuma atribuição para outro trabalhador.

## Evidência local já obtida

O diretório temporário `/tmp/praxis-page-p3b-512-evidence` preserva os brutos, inclusive falhas. O export final sanitizado será versionado depois dos gates.

- `focal-initial.log`: 34 casos, oito erros de fixture; não é uma rodada verde. Sete erros vieram de `mockConstruction` não suportado pelo maker global; um de restubbing de mock previamente configurado para lançar. As outras classes (cursor, autoconfig, contexto/default e binder P3-A) passaram, somando 23 casos fora dos dois testes corrigidos.
- `focal-corrections-1.log`: tentativa de maker inline por construção não suportada e assert de serialização ProblemDetail incorreto. Preservado.
- `focal-corrections-2.log`: 12 casos verdes após trocar interceptação de construção por owner/resolver/codecs reais, JDBC/repos simulados e mixin HTTP correto. Não prova conexão JPA/PostgreSQL.
- `focal-boundary.log`: 14 casos verdes, incluindo client input 400, provider/DTO/receipt inválido 503, parser condicional canônico e grants antes de 304.
- Três adendos distintos de um caso verde: `focal-host-root.log`, `focal-node-boundary.log`, `focal-provider-wiring.log`. Host root inválido é 503; nodes inválidos não permitem 200/304; owner presente não substitui provider custom nem o default fechado.
- Nenhuma soma das rodadas sobrepostas é apresentada como suite única. `checkpoint-70bfb856/manifest.json` distingue classes, árvore e limites. A prova final de pacote terá identidade privada coordenada e não sombreará o artefato público rc.166.

## Campanha proposta

| Lote | Prova | Critério e instrumentação |
|---|---|---|
| A1 | Mesmo head em catálogo, URL e menu; D5; revogação antes de 304 | P2 criação e lifecycle revisão/approval/publicação reais; head receipt igual nas três projeções; grupo negado oculta menu mas preserva URL; página negada exclui entrada e URL retorna 404; produtor negado retorna 503; metadata revoga READ_HEAD e impede 304. MockMvc usa owner e banco reais, sem alegar HTTP host operacional. |
| A2 | Phantom sem head e ABA A→B→A | Probe JDBC após primeira SELECT do vetor, antes do consumo/segunda coleta, dispara writer em executor próprio e transação/conexão distintas; Future bounded em 45s, shutdown/await explícitos. Primeiro cenário cria catálogo sem head; segundo publica B e rollback A; ambas as leituras devem conflitar 409. Dois vetores observados, conexões distintas, receipt reconciliado depois. |
| A3 | Próprios writes e composabilidade REQUIRED | Read→withdraw→catalog vazio→URL 404 dentro da mesma transação (rollback-only); rollback externo preserva tuple/head/ETag/events por conexão nova; outra execução commita e URL posterior dá 404 sem fallback B0; read vazio→republish→read retorna head atual na mesma transação. Receipts anteriores são snapshots provisórios, sem terceira coleta no beforeCommit. |
| B | Cursor/ordem, selector e isolamento | Ordenação global grupo/entry, IDs iguais entre páginas sem colisão de node; cursor não mistura publicação/visibilidade/contexto; actor A→B→A com versões diferentes; app/tenant/env/namespace e audiência incompatível excluem antes de nodes/receipts. Parent oculto e desconhecido uniformes. |
| C | Última autoridade, corrupção e recuperação | Mapping/metadata/structure/producer revogados com READ_HEAD permissivo; captura inválida não vira ausência; budget expirado antes da resposta não aceita; publish efetivamente commitado com resposta perdida é recuperado por novo GET/reader sem checkpoint/outbox/cache de grant. |
| D | Capacidade e batches | Seed sintético válido e correlacionado nas fontes existentes, explicitamente diferente de mil jornadas HTTP. Vetor O(N), corpos em prefixos 128/8MiB de texto workspace, top K de resposta; observar identidades consumidas, SQL/tempo/bytes e retenção EM. Oversize-first e sourceDraft ausente permanecem selecionados e são negados pelo verifier; deadline não trunca catálogo nem renova tentativa. |

A fixture `PageRuntimePostgresFixture` mantém atribuição fechada e inspeciona socket TLS do driver, peer, PostgreSQL/role/schema reais antes de Hibernate `validate`. A fixture host usa autoconfig e sources/admission próprios, não mocks do owner. Os helpers de teste foram derivados da prova P3-A, com atribuição P3-B explícita; isso não transporta resultados antigos para a campanha atual.

O baseline de budget do host de ensaio permanece 40s por operação. Runtime readmite producer/metadados de cada página autorizada; batching dos registros frozen não demonstra, por si, ausência de N+1 nas admissões. O lote denso começará com diagnóstico 32→128/129 identidades, incluindo SQL por categoria e chamadas/tempo de isolamento do driver, antes de seed 1000. Deve medir essa diferença antes de qualquer alegação de capacidade ou alteração estrutural. Sem SLA de produção e sem aumento silencioso do default.

## Pendências e limites

Revisar fixtures/barreiras A antes da primeira campanha; B/C/D ainda serão fechados em checkpoints próprios. Regressão P2 criação/publicação real, own-writes e runtime serão provadas no recurso novo, sem rodar suites antigas contra branches antigas. O merge mais recente de `origin/main` será confrontado antes da integração final. HTTP Quickstart operacional, renderer P4, browser/menu real e push invalidation não pertencem à evidência P3-B. Integração de guidance, artefato privado, consumidor Quickstart e Core são coordenados separadamente.
