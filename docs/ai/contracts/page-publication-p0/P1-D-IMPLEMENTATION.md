# P1-D — documento nativo de grupos

22/10/2026. Issue [#509](https://github.com/codexrodrigues/praxis-config-starter/issues/509).
**Corte isolado de codec: não habilita um host nem transições operacionais.**
Base confirmada `6144b4855d7e852ecffaa9af80abd3de2a0721e6`, após P0/P1-A.
Branch `codex/navigation-groups-509`; autor `p1d_groups`; revisão independente:
coordenador, concluída para o codec `635c7765`, sem bloqueadores identificados.
Costuras e aceite integral da issue continuam pendentes. Modelo/esforço solicitados pelo
coordenador: Sol/high; não há verificação independente da configuração efetiva.

## Inventário, plano e impacto

Classificação `contrato-publico`, transversal. A fonte canônica é Config.

| Necessidade | Aderência e decisão |
|---|---|
| Grupos planos, identidade/ícone/ordem e exclusão por ausência | `suportado-parcialmente`: P0 já especifica `groupsDocument`; faltava reprodução Java. Implementar o perfil existente, sem outro DTO/schema. |
| Draft/revisão/review/head, ETag, bounds e metadata admission | `ja-suportado-mal-nomeado-ou-mal-materializado`: reutilizar lifecycle e patch existentes. Não criar máquina de publicação. |
| Root registrado estável e despacho nativo app-shell | `lacuna-real-de-contrato`: costuras no registro/dispatcher atuais; escrita exclusiva do trabalhador P2, em coordenação. O codec apenas valida a forma UUID; não gera nem registra root. |
| Catálogo de ícones e fonte concretamente admitidos | `suportado-parcialmente`: SPIs de policy/source existem; nenhum catálogo app-shell está comprovado neste corte. Sintaxe aceita nunca equivale a admissão. |
| Integridade grupos/páginas em mudança de head | `lacuna-real-de-contrato`: gate P3-A. Nenhuma ativação operacional pode precedê-lo. |

Plano: (1) codec/reprodução fechado reutilizando o schema P0; (2) testes focais de
corpus, identidade/presença/remoção e patch; (3) entrega da API package-private ao
escritor único das costuras; (4) revisão independente e integração sequencial.

Consumidores futuros: Core/shell, Quickstart, Hero e exemplo oficial. Este corte não
altera qualquer artefato Maven já publicado, DTO de navegação, endpoint, DDL,
renderer ou consumidor. O helper fará parte do próximo JAR quando distribuído.
Risco: novos documentos nativos só serão aceitos no perfil P0; não muda os perfis
Form/Table/Page. Landing, corpus HTTP e recipes não precisam de alteração neste
corte isolado porque não há operação nova ativa; sua adoção continua em P3–P6.

## Implementação e fronteiras

`UiLayoutNavigationGroupsRevisionReproduction` usa o mesmo recurso de schema P0,
selecionando `$defs/groupsDocument`; não mantém outra cópia. Exige target
`praxis-app-shell` com UUID canônico, descritor v1 e patch `praxis.ui-layout/v1`.
Valida baseline e candidato, incluindo unicidade de group.id, documento fechado e
os limites existentes de 256 KiB/profundidade64. Usa `UiLayoutRevisionRelation`
para envelope imutável e reprodução exata. Os arrays são substituídos atomicamente:
omitir entrada remove grupo; `groups:[]` remove todos, sujeito ao gate P3-A no head.
Labels/ícones/ordem não são identidade; empates são admitidos. A autoria preserva
a ordem do array sem ordenar ou normalizar. A futura projeção de navegação ordena
por order/id conforme P0/P3-B, sem reescrever a autoria.

Não admite pages, vínculos, hierarquia, URLs de ícone, status ou campos externos ao
perfil P0. Não introduz fallback, feature flag, fonte paralela ou storage no host.

Admissão existente e limites da prova:

- `UiLayoutDraftWorkspaceSource.capture` fornece B0/metadata correlacionados sob
  invocation; `UiLayoutBaselineMetadataAdmission.require` exige origem verdadeira,
  reprodução e política atual. Nenhum payload do cliente atesta a própria fonte.
- `UiLayoutLifecycleStructureValidator.validateAuthoringBaseline` e
  `validateAuthoringRevision` são a fronteira atual para política de apresentação,
  incluindo IDs do catálogo de ícones; `validateFrozenRelease` repete esses checks
  sob o contexto da operação. `UiLayoutBoundedStructureValidator` conserva o budget.
- Este corte não implementa nem atesta um catálogo de ícones. Sem implementação
  host capaz de admitir fonte/root/ícones, o host deve negar. Os defaults dessas
  SPIs já negam. A regex do schema verifica só sintaxe de ID.

## Costuras reservadas ao escritor P2

API entregue: constantes `KIND`/`TYPE`, `requireNative(target, descriptor,
baseline, candidate, patchDescriptor)` e `require(..., patch)` (package-private).
O último método ignora outros documentTypes, igual aos codecs existentes.

Integração ainda necessária: despacho `UiLayoutAuthoringProjection`; reprodução
nas revisões e frozen releases; registro único UUID de app-shell por escopo
server-owned de navegação; bloquear publish/rollback/withdraw até P3-A. Não criar
UUID derivado de string ou presumir que validar UUID prova unicidade por escopo.
O registro deve vir da fonte canônica atual tenant/app/environment/namespace.
`UiLayoutDraftWorkspaceCodec` continua codec de envelope/hash, sem virar autoridade.
O escritor P2/coordenador mantém esses arquivos compartilhados e a integração
sequencial. Lifecycle/mock de root, admissão concreta, integridade entre agregados,
Neon, HTTP real e host/browser continuam pendentes; não são provados pelo codec.

## Validação observada

Runtime: macOS arm64; Java Temurin21.0.10+7; Maven3.9.15; Config fonte
0.1.0-rc.166 (sem alteração/publicação de versão). Target exclusivo desta worktree;
repo Maven exclusivo `/tmp/praxis-navigation-groups-509-m2`, cópia CoW do cache
pré-existente, sem install/deploy e sem coordenadas sobrescritas no cache compartilhado.
Nenhum recurso de banco ou servidor foi usado.

```sh
mvn -B -Dmaven.repo.local=/tmp/praxis-navigation-groups-509-m2 \
  -Dtest=UiLayoutNavigationGroupsRevisionReproductionTest,PagePublicationP0ContractTest test
```

Execução concluída em 22/10/2026 19:07:48 America/Sao_Paulo: Maven exit0,
112 testes, falhas0/erros0/skips0 (12 codec; 100 corpus P0). Output limpo antes da
primeira execução: worktree nova sem target. Validação focal suficiente para o
codec isolado; não executa banco, HTTP ou lifecycle de grupos. Log bruto local
`/tmp/praxis-navigation-groups-509-tests.log`; XMLs novos em target/surefire-reports;
identidades/hashes em `/tmp/praxis-navigation-groups-509-test-evidence.json`.
`git diff --check` aprovado. Revisão independente do codec `635c7765` concluída
pelo coordenador, com leitura de fonte/testes/registro e logs, sem bloqueadores;
costuras e aceite integral continuam pendentes. Ajuste editorial posterior não
alterou código/testes e não exigiu repetição da suíte.

## Skills e continuidade

Fonte inspecionada: `praxis-codex-skills` commit `1405560`,
`codex-skills/praxis-config-runtime-persistence/references/governed-layout-integrity.md`.
Classificação `atualizar-existente`: documentar a distinção entre sintaxe do codec,
admissão de ícone/fonte/root e gate P3-A antes de ativar head; sem instruir uma
fonte/catálogo não implementado. Escritor canônico é o coordenador; nenhuma skill
ou espelho local alterado por este trabalhador. Entrega/sincronização pendentes.

Reserva de execução publicada antes da edição e confirmada pelo coordenador:
[comentário de reserva](https://github.com/codexrodrigues/praxis-config-starter/issues/509#issuecomment-6352337817).
Correção da reserva: repo Maven efetivamente usado é `/tmp/praxis-navigation-groups-509-m2`.
Não há dev server, shell interativo ou conexão de banco a encerrar. Recursos
permanecem atribuídos ao trabalhador até revisão; nenhuma migração foi reservada.
PR draft [#516](https://github.com/codexrodrigues/praxis-config-starter/pull/516).
Próximo passo: integrar costuras pelo escritor P2 e revisar o fluxo integrado. Sem merge,
fechamento de issue, deploy ou release autorizado neste pacote.

## Shared lifecycle integration in P2

The P2 implementation supplies the previously pending shared seams: navigation-groups
seed validation before persistence, authoring projection and revision/frozen reproduction,
root metadata/source rechecks followed by current context/admission at commit, and the
central `UiLayoutLifecycleService` block on publish/withdraw/rollback until P3-A.
The operational fixture uses the existing host scope-to-root mapping, repeats the same
scope/root and rejects alternative UUIDs or tenant/application/environment/namespace.
It starts the real lifecycle graph without `PageCreationHost`.

V70 physically seals every RELEASED workspace, including app-shell, against UPDATE and
DELETE and prevents TRUNCATE. The final DRAFT→RELEASED write is still allowed. The table
contains permanent frozen documents and embedded captures; later reviews/heads use their
own records. The codec alone neither registers a root nor admits icons/domain sources.

Source checkpoint: `8d588c41` plus final P2 evidence follow-up. The focused Neon run
`neon-diagnostics` passed the groups scenario, but its combined run failed a separate page
copy timeout; that mixed result is not a global gate. Final source/log/XML and acceptance
status are tracked in [P2-IMPLEMENTATION](P2-IMPLEMENTATION.md). This does not enable P3-A
or independently close issue #509.
