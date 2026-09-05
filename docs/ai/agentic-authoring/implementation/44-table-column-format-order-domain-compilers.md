# Compiladores canônicos de formato e ordem de colunas

Data: 2026-09-05

## Problema fechado

O manifesto canônico de `praxis-table` já declarava as operações
`column.format.set` e `column.order.set`, seus schemas, alvos, validadores,
efeitos e exemplos. O snapshot do Config Starter também já recebia essas
declarações, porém o backend ainda não reconhecia os handlers
`table-column-format-set` e `table-column-order-set`.

O resultado era uma quebra entre authoring e materialização: a intenção podia
ser resolvida e o plano podia ser válido contra o manifesto, mas o gate de
contratos detectava que não existia compilador para produzir preview e apply.

Classificação do inventário de aderência: `suportado-parcialmente`. Nenhum
endpoint, DTO, DSL ou campo público novo foi necessário.

## Fonte canônica e responsabilidade

- `@praxisui/table` continua sendo o dono do manifesto, dos presets de formato,
  da configuração runtime/editor e do round-trip visual.
- `praxis-config-starter` reconhece os handlers declarados e materializa o
  efeito governado sobre a configuração proposta.
- O backend não escolhe a operação por palavras-chave e não infere intenção.
  Ele somente compila uma operação canônica já selecionada e validada.

## Semântica de `column.format.set`

O compilador:

1. exige uma coluna alvo resolvida pelo contrato `column-by-field`;
2. rejeita formato ausente ou vazio sem alterar a configuração;
3. preserva todos os atributos não relacionados da coluna;
4. grava o preset canônico em `columns[].format`;
5. materializa `columns[].type` apenas quando o formato implica, de forma
   determinística, `currency`, `percentage`, `boolean`, `number` ou `date`;
6. preserva o tipo existente para máscaras como CPF/CNPJ, que não definem por
   si só o tipo semântico da coluna.

A regra é equivalente à materialização já usada pelo compilador Angular do
Component Edit Plan. A enumeração permitida e os presets continuam governados
pelo manifesto e pelos validadores, não por este handler.

## Semântica de `column.order.set`

Uma alteração isolada de `order` é ambígua quando colunas irmãs ainda usam
ordem implícita ou têm colisões. Por isso o compilador:

1. calcula a ordem visual atual usando `order` explícito e índice original;
2. move a coluna alvo para o índice absoluto solicitado;
3. limita índices acima do tamanho ao final da sequência;
4. regrava uma sequência completa, contígua e sem colisões (`0..n-1`);
5. preserva todos os demais atributos de todas as colunas;
6. rejeita ordem negativa, fracionária ou fora da faixa inteira sem mutação;
7. produz o mesmo resultado quando a mesma operação é reaplicada.

Isso mantém editor, runtime, exportação, reopen e persistência com a mesma
ordem determinística.

## Evidência e validação

Cobertura focal em `AgenticAuthoringEffectCompilerRegistryTest` prova:

- formato monetário com inferência de tipo e preservação de propriedades;
- máscara de CPF sem corrupção do tipo;
- falha fechada para formato vazio;
- reordenação sobre mistura de ordem explícita e implícita;
- sequência completa sem colisões;
- preservação de propriedades das colunas irmãs;
- limite seguro de índice, idempotência e rejeição sem mutação.

O gate transversal deve ser executado no checkout Angular com
`PRAXIS_CONFIG_STARTER_ROOT` apontando para este checkout do Config Starter.
Ele deve deixar de reportar os dois handlers como ausentes sem modificar o
snapshot ou criar uma segunda fonte de verdade.

## Artefatos derivados revisados

- OpenAPI, DTOs, headers, ETag e endpoints: não afetados.
- Snapshot do AI Registry: não muda neste corte; ele já contém os handlers
  declarados pelo manifesto Angular.
- Runtime/editor Angular: não muda neste corte; a semântica equivalente já
  estava implementada e testada.
- Quickstart e corpus HTTP: não redefinem essa semântica e não exigem mudança
  para o gate focal.
- Ergon: permanece congelado; nenhuma alteração é autorizada por este corte.

