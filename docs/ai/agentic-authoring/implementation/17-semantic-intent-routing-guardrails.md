# Guardrails de Roteamento Semantico de Intencao

Classificacao da mudanca: `docs-apenas`.

## Objetivo

Impedir regressao arquitetural em que o authoring agentico volte a decidir a intencao primaria do usuario por palavras-chave, regexes, aliases ou fast paths locais.

Praxis deve tratar authoring como decisao semantica governada: a LLM, usando contexto governado, catalogos canonicos, manifests de operacoes e tools declaradas, resolve primeiro a intencao. O runtime e os services locais materializam a decisao, validam limites e ranqueiam alvos.

## Fluxo Canonico

1. O usuario envia um pedido natural, possivelmente ambigo ou imperfeito.
2. O cliente envia prompt, estado do componente, manifests, capabilities, filtros, colunas, surfaces, actions e contexto selecionado.
3. A LLM/backend resolve uma decisao canonica, por exemplo `column.derived.add`, `column.visibility.set`, `table.filter.apply`, `recordAction.add`, `surface.open` ou `export.run`.
4. Resolvers locais usam metadados para grounding: campos candidatos, aliases declarados, tipos, renderers, filtros, ranges, option sources e surfaces.
5. O plano e validado, explicado e materializado apenas se passar pelos limites de governanca.

## Proibido Como Decisor Primario

- `if prompt contains "ocultar" then column.visibility.set`.
- `if prompt contains "departamento" then filter department`.
- `if prompt matches /junte|concatene/ then add computed column`.
- Listas locais de palavras para escolher entre filtro, renderer, exportacao, acao de linha ou surface.
- Fast path que retorna antes da LLM/tooling resolver a operacao canonica.

Esses padroes sao perigosos porque capturam apenas fragmentos do texto. No exemplo "junte departamento e cargo em uma nova coluna e oculte as originais", um roteador textual pode privilegiar `oculte` e perder a intencao principal `column.derived.add`.

## Permitido Depois da Intencao Resolvida

- Depois de `column.visibility.set`, usar aliases, fuzzy matching e metadata para encontrar a coluna alvo.
- Depois de `table.filter.apply`, usar option sources e labels exibidos para ranquear valores.
- Depois de `column.derived.add`, usar schema fields para validar tipos, nomes de origem, separador e nome da coluna nova.
- Depois de `recordAction.add`, usar `recordSurfaces`, `actions` e `navigationDestinations` para escolher candidatos e pedir clarificacao.
- Guardrails deterministas que bloqueiem operacoes inseguras ou inconsistentes, desde que nao escolham a intencao primaria.

## Checklist de Revisao

- A operacao foi escolhida pela LLM/tooling governado, ou por um `if` local sobre texto?
- O matching textual esta limitado a grounding/ranking de alvo depois da operacao canonica?
- O manifesto possui operacao suficiente para expressar a intencao sem inventar patch incidental?
- A resposta explica a decisao em linguagem humana, sem vazar nomes tecnicos como fonte primaria de UX?
- Existe teste com prompt humano imperfeito cobrindo ordem, sinonimos e intencao composta?
- O fallback, se existir, esta inventariado como legado e fora do caminho preferencial?

## Direcao de Migracao

Fast paths legados devem ser migrados em tres passos:

1. Inventariar o caminho e identificar qual operacao canonica ele deveria representar.
2. Adicionar ou enriquecer o manifesto/tooling para que a LLM consiga selecionar essa operacao.
3. Rebaixar o codigo textual para grounding pos-intencao ou remove-lo quando os testes provarem cobertura suficiente.

