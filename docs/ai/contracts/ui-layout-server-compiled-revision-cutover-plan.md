# Corte canônico: revisão compilada pelo Config

2026-10-06. Corte implementado localmente; aceite integrado e publicação pendentes.
Contrato vigente: [revisões compiladas pelo servidor](ui-layout-server-compiled-revisions.md).
Fonte canônica: Config para compilação, admissão e persistência; Core para o
contrato/transportes do navegador; Table/Form para autoria e materialização.
Quickstart prova downstream. Mudança futura contrato-publico/arquitetural e
transversal. Compilador/projeção/bounds ligados ao comando; request recebe apenas C0.
Candidata privada rc.168 passou 40 casos downstream, mas não é implantação ou
rebuild limpo. Este plano não concede admissão de produtores/baselines.

## Decisões de contrato

Mesmo POST `/api/praxis/config/ui-layouts/drafts/{draftId}/revisions`, mesmos
contexto, principal, If-Match, recibos, transação e rotação ETag. Body novo:

```json
{
  "commandRef": "00000000-0000-4000-8000-000000000001",
  "target": {"componentType": "praxis-table", "componentId": "orders"},
  "authoringDocument": {
    "kind": "praxis.table.editor",
    "version": 1,
    "config": {"columns": [], "columnProjection": {"source": "schema"}}
  },
  "reason": "Ajustar a configuração authorada"
}
```

Exemplo apenas de shape, não baseline/schema admitido de Compras. C0 é autoria
raw completa; bindings/envelope reais devem corresponder ao B0 pinado. Nenhum
B0, descriptor, política, path de projeção ou patch entra pelo cliente.
`patchDocument` passa a ser campo desconhecido (400), sem opcional, flag ou v2.
Remover também do RevisionInput Java e UiLayoutRevisionCommand Core. Patch
gerado permanece no RevisionCommand interno, revision store, validação, freeze,
release e resolução. Não remover da história ou trocar sua semântica de hash.

Manter 1 MiB wire/text, 256 KiB compactos por B0/C0/patch, depth 64 e wrapper 65,
token numérico 256. A quota de saída do patch é independente da candidata:
resets podem aumentar o resultado. 413 é do body HTTP; sintaxe/quota C0 e patch
gerado retornam INVALID_REQUEST; B0 inválido INVALID_STATE; mapping ausente
SOURCE_UNAVAILABLE; política nativa/estrutura VALIDATION_FAILED. Erros sanitizados.
Não inserir redução cosmética de quotas por haver um documento a menos.

## Ordem obrigatória do comando

1. Advice/decoder limitam e decodificam C0; entry Java usa o reader fechado.
2. Preservar principal/EDIT_DRAFT/contexto, uma tentativa e transação Config
   efetiva; verificar strong If-Match/draft editável sem recapturar baseline.
3. Preservar workspaceProjector.project/decode e verificação de metadata/source/
   hash já existente. Resolver target/descriptors/B0 no workspace autorizado;
   validar target e bounds B0. Não chamar producer nem inventar schema refs.
4. Selecionar UiLayoutAuthoringProjection com target.authoring/target.patch,
   B0 original e C0 raw; callback ligado ao validation.require(invocation).
   Verificar envelope/shape/nulls e compilar uma vez B0.config -> C0.config.
   Não usar working como baseline, normalizar C0 ou executar runtime JS. A
   compilação não depende de JSONLogic; suas validações de negócio backend não mudam.
5. Host validateAuthoringRevision recebe B0/C0 originais e patch gerado;
   validatePatch recebe esse mesmo patch. Guard comum verifica reprodução.
   Não relaxar proteção host de meta/bindings nem considerar projeção uma grant.
6. Persistir patch imutável e working C0 raw/seleção na mesma transação atual;
   limpar assignment anterior e rotacionar ETag. Hash do patch mantém algoritmo
   existente; working usa sha256Exact. Rechecagens de autoridade/budget e rollback
   antes/apos extensões/beforeCommit permanecem. Quotas não criam fencing/SLA.

## Disposição dos formatos

| Formato | Novas revisões compiladas | Leitura/evidência existente |
| --- | --- | --- |
| Table editor 1 + target praxis-table + patch praxis.ui-layout/v1 | Mapping exato existente; B0/C0 compactos, columns vazio, columnProjection.source=schema, sem null em config | Guard e host continuam obrigatórios |
| Form editor 1 + target praxis-dynamic-form + patch praxis.ui-layout/v1 | Mapping exato; null em objetos mesclados rejeitado, valores sob arrays atômicos preservados | Admissão real do host permanece separada; Form lab negado |
| Table flat authoring-document/v3 | SOURCE_UNAVAILABLE, sem alias/conversão automática | Preservar histórico; fixtures que efetivamente criam revisão precisam de B0/C0 canônicos |
| Page editor 1 com widgets no root | SOURCE_UNAVAILABLE; não inferir config ou criar adapter nesta entrega | Capture/replay com doubles continua; não prova escrita compilada |
| native/test-native | SOURCE_UNAVAILABLE para nova compilação | Fixtures puras de codec/history/evolution/frozen podem permanecer sintéticas |

Onde um teste sintético chama createRevision, separar prova de autoridade/transação
da positiva de compilação: migrar apenas seu target authorável para documento
nativo válido, mantendo os demais dados e asserções. Não converter toda composição
ou aceitar sintético por no-op validator. Publicação de revisões históricas não
ganha gate novo de compilação, nem passa a recapturar a origem.

Baseline lab Table atual: seis columns materializadas, sem columnProjection;
não atende o mapping. Preservar arquivo/hash/admissão e registrar indisponibilidade
de nova revisão. Substituição futura exige autoria compacta e admissão de publicação
novas; não converter esse B0 na leitura. A ação A bloqueada não será repetida.

## Migração do navegador e infraestrutura comum

Core altera modelo exportado, testes de transporte e documentação; o serviço
createRevision permanece o dono do POST/contexto/ETag/reconciliação. Table mantém
createRevisionCommand e Form seu produtor interno como builders de C0/comando.
Remover authoredPatch de ambos no mesmo corte; manter materialização e diagnostics
nas libs, sem reimplementar merge/diff/quotas/validação autoritativa do servidor.

Proteção residual necessária: valores JS podem ser omitidos/convertidos antes de
chegar ao Java. Centralizar em Core uma superfície mínima de cópia JSON sem perda
e comparação exata local de autoria, usada por Table/Form e pelo transporte
de revisão. Rejeitar undefined, functions, symbols, getters/accessors/toJSON,
objetos não JSON, sparse arrays, ciclos e números não finitos sem executar getters.
Preservar null, arrays/order, chaves e snapshots independentes. Não admitir schema,
normalizar, compilar patch ou substituir hash persistido nesse helper. Nome/API
final devem ser definidos no pacote focal antes de exportar, com dois consumidores.

A canonicalJsonStringify atual de Core omite null/undefined de objetos: não é
comparação exata para esta finalidade. Não mudar sua semântica global. Table usa
uiLayoutJsonIdentity do Core também para detectar baseline/bindings do editor;
migrar esses callers para a comparação comum, sem apagar checks de estado válido/
idle/working. Owner diagnostics Form ficam locais. Não reexportar Core via barrel
Table/Form nem introduzir dependência circular ou fachada transitiva.

## Mapa de impacto e artefatos

| Dono | Arquivos/superfícies mínimas | Verificação |
| --- | --- | --- |
| Config | DTO, decoder, controller/OpenAPI, RevisionInput/createRevision, projeção/compilador e testes command/HTTP/bounds/authority/history/frozen | Candidate-only, patch rejeitado, B0 original em duas revisões, output quota sem write, revogação/expiry/rollback |
| Core | ui-layout-lifecycle.model/service/spec, public-api mínima para JSON residual, util/spec e docs | Body exato sem patch, serialização sem perda, recibos/ETag, transport failures |
| Table | table-authoring-revision/spec, widget editor/spec | C0 raw, working/bindings correlation, pins explícitos mantidos no documento, nenhum compilador de patch |
| Form | dynamic-form-authoring-revision/spec, testing/dynamic-form-revision-fixtures.ts | Raw diagnostics, null de arrays intacto, cópia/estado/transport loss |
| Quickstart | testes MVC/autoconfig/SharedJson e testes lab da seam de validators | JAR privado distinto, Jackson real, filtros reais, dependências ausentes; sem ativar lab |
| Docs/corpus | ui-layout-authoring-workspace, contratos compiler/projection/bounds, corpora Table/Form e leitores Java/TS/Quickstart | Separar command candidate-only de expectedPatch de teste, preservar casos de pins/reset |
| Derivados | repetir busca local em Landing/recipes/HTTP antes de concluir corte | Busca anterior só tinha GET lookup e não achou Save operacional; não é inventário de hosts externos |

Corpora não podem continuar chamando o builder de runtime para gerar expectedPatch.
Fixar expectativas explícitas de teste independentes; shape candidato+patch antigo
de fixture não vira contrato compatível. Atualizar schema documental da fixture
quando necessário e todos leitores. Generator Form fica no helper de testing; não
deixar um segundo compilador operacional TS só para conservar o corpus.

## Pacotes, gates e encerramento

Preparação focal independente: infraestrutura JSON residual comum Core/Table/Form
e organização de fixtures; pode manter request atual até o corte, sem modo de
compatibilidade. Não expor novo endpoint/command paralelo nem mudar a seleção de B0.

Checkpoint anterior de preparação (2026-10-06): Core passou a ser o dono comum de
`cloneUiLayoutJsonDocument` e `uiLayoutJsonIdentity`; Table/Form reutilizam a cópia
de B0/C0 e a identidade do envelope. O transporte Core captura o comando antes
da resolução assíncrona do contexto. Naquele pacote, a preparação manteve o request com
candidate e patch, sem ativar o compilador Java no fluxo. O corpus Form conservou
os seis casos e atualizou apenas o hash do produtor; o corpus Table conservou seus
quatro casos. Evidência e limites: `shared-json-transport-2026-10-06` no projeto
de artefatos. O corte foi implementado posteriormente no pacote
`server-compiled-cutover-2026-10-06`, incluindo expectedPatch explícito separado
dos commands. A aprovação integrada permanece pendente.

Corte obrigatório em um ciclo reviewable: DTO/RevisionInput/command Java + Core
modelo/transport + Table/Form builders + corpora/readers/docs + Quickstart. Não
encerrar com só uma ponta candidate-only. Não publicar enquanto consumidores
oficiais ou validadores continuarem exigindo patch de cliente.

Gates mínimos futuros: suites focais Config acima; builds Core/Table/Form e specs
lifecycle/JSON/producers/editors; testes Quickstart atualizados no JAR candidato.
Build de consumidor direto e E2E focal são obrigatórios por contrato exportado.
E2E existente Form: tools/e2e/playwright/praxis-dynamic-form-config-editor.playwright.config.ts;
ele não prova o POST de revisão por si só. Definir cobertura de transporte/autoria
nas superfícies oficiais antes do corte; não inventar porta/origin. Se gate runtime
continuar bloqueado, registrar pendência e não declarar pronto para publicação.

Caso crítico: primeira revisão C1 remove uma propriedade B0; segunda C2 retorna
a propriedade igual a B0 e mantém outra remoção. Persistir contribuição completa
relativa a B0 em cada revisão; reabrir C2 e congelar/atribuir o patch selecionado.
Incluir formatos não mapeados, candidate inválido, envelope/meta alterado, Form
array-null, Table null, pins iguais, arrays vazios, escalars/objects e comando
conflitante, sem mutações nas rejeições. Manter provas de autoridade ambient TX.

Segurança corporativa separada: host atual permitAll admite parsing limitado
anônimo com Origin permitida; comando exige principal/admissão antes de mutação.
Autenticação pré-parse exige decisão de política host com impacto de todos config
endpoints, sem filtro de URL improvisado. Banco/rollback concorrente, IAM vivo,
owner real e browser integrado continuam gates operacionais, não fixtures.

Versionamento: remoção de campo público é breaking. Checkout npm inspecionado
está em 10.0.0-rc.0; isso não confirma canal público. Config POM rc.167, candidata
privada rc.168 e Quickstart pin rc.163 são estados distintos. Antes de qualquer tag,
classificar linha/canal real e fechar mapa de migração/release; nenhum bump/tag ou
publicação é autorizado ou executado por este plano.
