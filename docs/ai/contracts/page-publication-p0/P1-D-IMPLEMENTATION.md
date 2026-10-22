# P1-D — grupos nativos e integração do lifecycle

Implementação de [#509](https://github.com/codexrodrigues/praxis-config-starter/issues/509),
com costuras compartilhadas entregues no [PR #517](https://github.com/codexrodrigues/praxis-config-starter/pull/517).
A fonte integrada sob validação é `17592e69917a30ceeea950a7e1eba8ef75b2dcae`.
Os resultados finais e os limites estão em [P2-IMPLEMENTATION](P2-IMPLEMENTATION.md).
Revisão e integração são registradas no PR; este documento não antecipa merge nem
habilita hosts ou mudanças de head de grupos antes de P3-A.

## Inventário e impacto

Classificação `contrato-publico`, com fonte canônica Config e perfil fechado P0.

| Necessidade | Aderência e solução |
| --- | --- |
| Grupos planos, identidade, ícone, ordem e remoção por ausência | `suportado-parcialmente`: reutilizar `groupsDocument` P0 e implementar reprodução Java. |
| Draft, revisão, review, ETag e metadata admission | `ja-suportado-mal-nomeado-ou-mal-materializado`: integrar o lifecycle existente. |
| Root estável por tenant/app/environment/namespace | `ja-suportado-mal-nomeado-ou-mal-materializado`: explicitar a invariável no mapping host de `UiLayoutLifecycleInvocationProvider` e exercitá-la operacionalmente. Validar UUID não registra uma composição. |
| Ícone e fonte concretamente admitidos | `suportado-parcialmente`: usar structure/source/metadata SPIs atuais; o schema não atesta o catálogo do host. |
| Integridade entre grupos e páginas em mudança de head | `lacuna-real-de-contrato`: pertence a P3-A; as transições continuam fechadas. |

O plano isolou o codec, integrou-o sequencialmente no escritor P2, validou as costuras
sem PageCreationHost e submeteu os dois recortes à revisão independente. Consumidores
futuros são Core/shell, Quickstart, Hero e o exemplo oficial. A adoção host/UI, a
projeção de navegação e os artefatos operacionais derivados continuam nos cortes P3–P6.
Não foi publicado artefato Maven, renderer ou nova API de navegação neste incremento.

## Comportamento entregue

`UiLayoutNavigationGroupsRevisionReproduction` seleciona `$defs/groupsDocument` no
mesmo schema P0. Exige target `praxis-app-shell` com UUID canônico, descritor v1 e
patch `praxis.ui-layout/v1`. Baseline/candidato têm perfil fechado, IDs únicos,
limites de 256 KiB/profundidade 64, envelope imutável e reprodução exata pelo patch.
Arrays são substituídos atomicamente; ausência remove uma entrada e `groups:[]`
remove todas. Labels, ícones e ordem não são identidade; empates são admitidos.
A autoria mantém a ordem do array. Não aceita páginas, vínculos, hierarquia, URLs
arbitrárias de ícone, status ou campos externos ao perfil.

As costuras compartilhadas agora validam a seed antes de persistir, despacham a
projeção e reproduzem revisões e frozen releases. Revisão/freeze e callbacks de
commit rechecam metadata do root e são seguidos por revalidação atual de contexto
e autoridade. O histórico verifica integridade sem recapturar a origem.

O provider existente do host deve resolver exatamente um root app-shell por escopo.
A fixture operacional mapeia a tupla autenticada, resolve repetidamente o mesmo root,
rejeita um segundo UUID e rejeita tenant/app/environment/namespace desconhecidos.
Isso não instala um cadastro paralelo. Host sem fonte/root/ícones admitidos deve negar;
os defaults dos SPIs permanecem fechados.

`UiLayoutLifecycleService` bloqueia publish, withdraw e rollback de app-shell até
P3-A, incluindo chamadas diretas com policy/validator permissivos e sem PageCreationHost.
Não há flag temporária para contornar esse gate. V70 também sela UPDATE/DELETE de
qualquer draft já RELEASED e impede TRUNCATE, usando a função canônica de imutabilidade.
A escrita final DRAFT→RELEASED permanece permitida; reviews/heads mantêm seus próprios
registros. O documento congelado e as capturas embutidas não são reescritos.

## Evidência e proveniência

O codec isolado veio de `635c7765`, seguido de documentação em `ecfdf73f`, integrado
pelo PR [#516](https://github.com/codexrodrigues/praxis-config-starter/pull/516) na base
`468f9de696330be2ee16d181c5145cfe849dc29b`. A revisão independente do coordenador
não encontrou bloqueadores nesse recorte. Reserva original:
[comentário](https://github.com/codexrodrigues/praxis-config-starter/issues/509#issuecomment-6352337817).

A prova histórica isolada de 22/10/2026 19:07:48 America/Sao_Paulo executou 112 testes
(12 codec + 100 corpus P0), sem falhas/erros/skips, Java Temurin 21.0.10+7 e Maven
3.9.15, sem banco. Usou `/tmp/praxis-navigation-groups-509-m2`, cópia CoW do cache
pré-existente, sem install/deploy compartilhado. Log bruto:
`/tmp/praxis-navigation-groups-509-tests.log`; manifesto:
`/tmp/praxis-navigation-groups-509-test-evidence.json`. Esse resultado não é apresentado
como gate do lifecycle integrado ou da árvore atual.

A prova integrada e sua fonte, DDL, runtime restrito Neon, logs e XML estão reunidos
com P2. Inclui seed inválida, revisão com remoção/rename/ordem, revogação de metadata,
root estável e guard central sem PageCreationHost. Não equivale a HTTP real do host,
E2E browser, catálogo concreto de ícones ou integridade P3-A entre agregados.

## Skills e continuidade

A avaliação foi `atualizar-existente` para persistence e `existente-adequada` para
fronteiras de domínio. O coordenador integrou os incrementos canônicos, incluindo
PRs 823/824, no estado `de0121b` de `praxis-codex-skills`, com sincronização seletiva
concluída. O guidance distingue sintaxe, fonte/ícones admitidos, root registrado,
selagem de RELEASED, reuso de bytes imutáveis e gates P3/P4. Não há pendência de skill
conhecida neste incremento. O fechamento das issues e a adoção permanecem atos de
coordenação separados da evidência técnica.
