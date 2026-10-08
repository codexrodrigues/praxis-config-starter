# Reprodução da revisão nativa Form

Config é dono da relação persistida B0 + patch = C0. A candidata privada rc.164 acrescenta UiLayoutFormRevisionReproduction aos caminhos createRevision e frozenRelease (publish/rollback), depois da validação host e antes da revisão/head. Essa guarda não valida a sintaxe completa Form, não executa Angular nem concede admissão de publicação/closure.

Para documentType praxis.dynamic-form.editor, exige target praxis-dynamic-form, documento version 1, descriptor schemaVersion 1, config objeto e patch praxis.ui-layout/v1. O envelope fora de config (incluindo bindings/contextSnapshot) deve permanecer idêntico. A guarda usa o merge patch canônico sobre cópia do baseline e exige igualdade exata com o config candidato.

Presença authored é obrigatória: até propriedades com valor igual a B0 precisam constar no patch, preservando pins. Remoção se representa por null no patch somente quando a propriedade existia. UiLayoutAuthoredPresence é compartilhado com Table; o formato e a política Table existente permanecem iguais.

Null literal em propriedade de objeto que será mesclado não é representável: nesse formato significa remoção. A guarda o nega com VALIDATION_FAILED, sem repará-lo, apagá-lo ou fingir que a ausência é equivalente. Arrays substituem integralmente seus valores e podem conter null, inclusive em objetos internos. Assim span:null em uma coluna dentro de sections/rows/columns permanece representável e preservado. A política de sintaxe e herança desses valores pertence ao owner Form.

Uma revisão inconsistente falha antes de immutable revision/head; descriptor não suportado falha SOURCE_UNAVAILABLE. Documentos/members/hashes/autoridade continuam verificados pelos mecanismos Config existentes. A guarda não substitui frozen admission, owner schema validation, ETag ou autorização corrente. Testes de relação usam fixtures deliberadas, sem alegar saída de produtor TS.

## Corpus nativo B2b

`form-native-revision-conformance.v1.json` contém seis fixtures finitas geradas
pela execução de buildDynamicFormRevisionCommand e do validador raw no owner
Angular. Inclui hashes dos fontes e casos de pin igual, reset conhecido, null
em array, reset aninhado, mudança de tipo e arrays vazios. O teste Config
consome cada comando candidato-only e seu `expectedPatch` explícito, fora do
request, e também nega a omissão de um pin. O builder não calcula o patch; o
compilador Java é comparado com essas expectativas independentes. As oito fixtures
manuais acima continuam sendo testes de relação; não são promovidas a prova TS.

Gerar/verificar com o helper `scripts/workspace/generate-form-native-revision-conformance.mjs`
da raiz da plataforma, fornecendo os roots Angular/Config e opcional `--check`.
A verificação executa novamente o produtor real e exige igualdade byte a byte.
Os hashes registram fontes focais, não uma closure transitiva admitida. A sintaxe
é a coberta pelo validador atual; sem contexto/schema efetivo não há prova de
compatibilidade com recurso, servidor, autoridade ou nova baseline.

## Pendências para ativação

O adapter Quickstart B1 continua negando Form. O produtor/corpus local B2b não
resolve admissão de fonte/closure, deadline total/correlação explícitos, executor
isolado e prova de cancelamento. Nem relação verificável nem testes com gateway
simulado permitem ativar a composição. O produtor é interno e ainda não está
ligado ao fluxo operacional de edição.

Null literal fora de arrays precisa de contrato deliberado se o produto precisar persistir sua presença; não introduzir aliases, fallback ou formatos paralelos preventivos. Rebase contra uma nova baseline e preservação de autorização/schema efetivo permanecem gates separados.
