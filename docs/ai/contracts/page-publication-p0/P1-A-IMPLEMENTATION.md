# P1-A — codec de página e admissão de filhos

Estado: implementação para revisão da issue #508. P0 integrado em `996e15ffb305dfef9a5456530e3d677abf100c15`.
Não habilita catálogo/criação/cópia, grupos, navegação por head ou hosts de páginas.

## Inventário e impacto

Classificação: contrato público/transversal. Config já possui lifecycle, CAS, patches relativos ao B0,
capturas correlacionadas de metadata, revisão, aprovação e publicação. A lacuna era projetar a página
nativa e conservar/admitir fontes dos filhos internos sem criar targets externos adicionais.
`UiLayoutAuthoringProjection`, `UiLayoutNativeStructureChecks`, compilador e leitores existentes
permanecem donos de reprodução e integridade. Form/Table continuam donos das regras nativas.

O root `praxis-dynamic-page` exige UUID minúsculo de 36 caracteres e `targets=[root]`. O documento
`praxis.dynamic-page.editor` versão 1 usa o perfil fechado P0. Cada filho é reconstruído sem defaults:
`inputs.config` contém a única configuração do filho; bindings/contexto são autoria admitida e preservam presença, ausência e null. A key fixa
seu envelope. Arrays atômicos da página não dispensam guards nativos. Form aceita cinco modos
(create/edit/view/presentation/dossier), conforme tipo público/runtime Angular; a restrição anterior
no codec TS era drift do owner, corrigido em P1-C. Config não redefine essa semântica.

Consumidores afetados: SPI server-side `UiLayoutDraftWorkspaceSource`, DTO de revisão, Core e
adapters Form/Table. Construtores antigos apenas delegam listas vazias para superfícies sem filhos;
páginas com filhos sem evidência falham fechadas. Nenhuma migração, endpoint ou armazenamento
alternativo foi criado. Grupos continuam indisponíveis. O snapshot do AI registry é derivado do
script oficial Angular e integra somente os deltas correlatos de Form/Filter Form.

## Contrato de fonte e captura

Na criação, `TargetSeed.pageChildren` contém um `UiLayoutPageChildSeed` por filho original.
Na revisão, `childSources` contém somente as keys novas, com `registered-native/sourceId/revision`.
O cliente nunca fornece documento-fonte, hash de captura, admissão ou atestado. O servidor resolve
exatamente a revisão pedida por `capturePageChild`; resposta com request divergente é rejeitada.

O host deve implementar `requirePageChild` vinculando **o request completo** à fonte versionada,
documento, descriptor e reprodução efetivamente admitidos sob o invocation corrente. Verificar
somente a existência de uma key não satisfaz esse SPI. Os defaults negam operação. A admissão de
metadata existente continua obrigatória e complementar; nenhuma das duas substitui a outra.

A captura usa o codec de metadata existente, vinculada a tenant/environment, UUID do draft, único
root externo e key interna. `captureText` é texto server-owned para preservar bytes/ordem histórica
através de JSONB; não é autoridade atual. Ele não aparece no receipt. Filhos removidos conservam a
captura, permitindo restauração com a mesma identidade; a restauração revalida a fonte original,
sem recapturar latest nem aceitar declaração substituta para uma key já conhecida.

Criação, replay, revisão, freeze e ativação de contribuição congelada revalidam os filhos ativos.
A rechecagem em `beforeCommit` cobre revogação da fonte mesmo quando o invocation não muda.
Inspeção current/frozen mantém a fronteira existente: exige contexto/READ_DRAFT, integridade e
referências persistidas, mas pode mostrar história cuja fonte deixou de admitir novas mutações.
Um receipt de leitura não atesta admissão atual para executar/publicar.

## Limites e correções encontradas pela prova remota

O documento permanece limitado a 256 KiB/profundidade 64; o corpo de revisão e evidência agregada
usam os limites canônicos existentes. UUIDs, texto, whitespace ECMA e terminações absolutas têm
vetores compartilhados P0. `NativeSource` reutiliza o schema, inclusive maxLength em codepoints.

O schema P0 inicial dizia 2000 para reason, divergindo de `created_reason`, approval e event com
VARCHAR(1024). A correção reutiliza 1024 codepoints Unicode e valida antes de writes de revisão,
aprovação/eventos; ASCII e astral 1024/1025 têm corpus. Não há requisito de negócio que justifique
ampliar storage, portanto nenhuma migration foi adicionada.

A prova JPA real encontrou dois problemas preexistentes ocultados pelos mocks: entidades novas
com UUID atribuído e `@Version Long=0` eram consideradas detached; agora Hibernate inicializa a
versão. E `JpaTransactionManager` expõe um ConnectionHolder sem o flag específico do manager JDBC.
O store aceita esse caso somente quando o TransactionTemplate Config aponta ao mesmo DataSource
e EntityManagerFactory, o EntityManager transacional está sincronizado/ativo e sua conexão JDBC
é exatamente a do holder. Autocommit continua proibido e READ_COMMITTED obrigatório. Holder avulso,
outro DataSource e outra conexão continuam rejeitados. Não foi introduzido bypass no teste.

## Evidência e limites

- Corpus P0: 100 testes, incluindo refinamentos de UUID/whitespace, cinco modos e reason Unicode.
- Codec e captura: 6 + 5 testes, igualdade schema/resource, guards Form/Table em arrays, envelopes,
  metadados alterados, UUID de draft divergente, remoção/restauração e fontes exatas.
- Regressão focal dos owners, entrada HTTP modelada, lifecycle e store: ver relatório de execução.
  MockMvc/Mockito provam entrada/ordenação, não durabilidade PostgreSQL.
- Neon: projeto `ancient-leaf-72055027`, branch exclusiva `br-round-sun-a4fj4hz6`, endpoint
  `ep-calm-tree-a432ds1p.us-east-1.aws.neon.tech`, PostgreSQL 17.11, schema
  `b1a_it_page_p1_508_20261022`, runtime `p1_page_508_runtime` sem superuser/createdb/createrole/bypassrls.
  O socket JDBC comprova TLSv1.3/hostname via sslmode=verify-full e truststore Java. `pg_stat_ssl=false`
  observado no proxy Neon não foi usado como prova de TLS.
- DDL focal V64–V68 aplicado pelo owner só no schema atribuído. Não prova Flyway integral. O runtime
  não tem DDL/DELETE/update de evidência imutável; UPDATE(id) em definition permite seu lock canônico.
  Fixtures usam source/policy sintéticos, mas JPA, JDBC, JSONB, constraints e transações são reais.
  Fresh connections conferem rollback. Não é prova HTTP do Quickstart, browser ou autorização corporativa.
- Quickstart: checkout isolado `64080d77986f567f2d9bc6b27b7c6e218dbd7834`, repositório Maven isolado
  `/tmp/praxis-page-p1-508-m2`; build/linkagem focal, sem ampliar para host de páginas.

Execução remota opt-in: `PRAXIS_PAGE_P1_NEON_RUNTIME_FILE=<arquivo privado atribuído> mvn -B
-Dtest=UiLayoutPageLifecyclePostgresIT -Dpraxis.page.neon.it=true test`. O arquivo privado (chmod 600) contém `projectId`, `branchId`, `endpoint`, `schema`, `username`,
`password` e `jdbcUrl`. A coordenação deve atribuir a branch e confirmar no control plane a relação
projeto/branch/endpoint antes da execução. O teste confere hostname/TLS, banco neondb, identidade,
privilégios e schema reais contra o assignment; projectId/branchId são a correlação control-plane,
pois PostgreSQL não publica esses IDs na sessão. Prefixos exclusivos `b1a_it_page_`/`p1_page_` e
transporte verify-full são obrigatórios. `UiLayoutPageNeonAssignmentTest` rejeita assignment
incompleto, schema/role fora do perfil, hostname divergente e TLS enfraquecido. Uma nova sessão
requer nova reserva oficial e novo arquivo; não exige reutilizar os IDs desta execução.
Credenciais não são versionadas. Preparar V64–V68 no schema reservado como owner, depois conceder
USAGE, SELECT/INSERT nas tabelas, UPDATE em draft/review/head e UPDATE(id) em definition ao runtime;
conferir ausência de DDL/DELETE e privilégios de owner no runtime antes do gate.

Template do arquivo privado (substituir apenas por atribuição oficial; nunca commitar a senha):

```json
{
  "projectId": "<projeto-Neon-atribuido>",
  "branchId": "br-<branch-filha-atribuida>",
  "endpoint": "ep-<endpoint>.<regiao>.aws.neon.tech",
  "schema": "b1a_it_page_<ensaio>",
  "username": "p1_page_<ensaio>_runtime",
  "password": "<segredo-privado>",
  "jdbcUrl": "jdbc:postgresql://ep-<endpoint>.<regiao>.aws.neon.tech/neondb?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
}
```

Confirmar projeto/branch/endpoint pelo preflight da API Neon e registrar o vínculo antes de conectar.
O teste não deriva autorização de escrita de nomes ou regex; estes são restrições adicionais sobre
um recurso previamente atribuído. A execução documentada usou exclusivamente a branch acima.

## Derivados e skills

Atualizados schema/corpus/README, resource runtime, documento de entrega e snapshot AI registry
via `tools/ai-registry/sync-config-starter-registry-snapshot.js`. Core/Adapters mantêm testes de
paridade nos seus owners. Landing/HTTP examples e hosts ainda não expõem P1-A; suas publicações
operacionais pertencem aos pacotes posteriores e não foram apresentadas como concluídas.

Skills: `praxis-config-runtime-persistence` = **atualizar-existente**, ensinando a distinção entre
config pessoal e lifecycle governado, fonte exata por key, captura histórica vs admissão atual,
bounds e prova JPA/JDBC remota. `praxis-config-domain-decisions` = **existente-adequada** para a
fronteira decisão/materialização; não precisa duplicar o procedimento de páginas. Fontes inspecionadas
na worktree canônica de skills `page-publication-p1-skills`, base `0bf25f1`, incluindo
`references/governed-layout-integrity.md`. O checkout principal estava desatualizado; não representa
remoção da referência canônica. O incremento correspondente está no PR de skills #819, sob revisão
do coordenador. Integração/publicação e sincronização desse guidance permanecem gates do pacote.

Revisão independente, paridade Angular, evidência final por SHA e entrega canônica de skills continuam
gates. Este documento não declara P1 concluído nem autoriza merge/release/deploy.
