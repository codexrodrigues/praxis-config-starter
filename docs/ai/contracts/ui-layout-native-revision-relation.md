# Relação comum de revisão nativa

2026-10-06. `UiLayoutRevisionRelation` é helper package-private Config consumido
pelos guards Table/Form. Não define API pública, schema, renderer ou novo formato.

Checkpoint atual: [o comando compila no Config](ui-layout-server-compiled-revisions.md)
e entrega seu patch aos mesmos guards. Corpora agora separam commands candidate-only
de expectedPatch explícito; B0/C0 e expectativas permanecem iguais. As notas de
extração abaixo registram a prova anterior, não o estado do request atual.

Depois de cada adapter validar seu descritor/documento/patch e suas políticas,
a relação comum verifica:

1. Envelope completo fora de config é igual: bindings, kind/version, contexto e
   quaisquer propriedades presentes não podem mudar pela revisão de config.
2. Propriedades autoradas de C0.config devem estar no patch mesmo iguais a B0.
3. Reset null só pode remover propriedade conhecida em B0.
4. Aplicar o merge Config existente numa cópia de B0.config reproduz exatamente
   C0.config. Nenhum dos três inputs é modificado.

Métodos são separados para conservar a ordem de admissão/políticas dos adapters.
O helper recebe ObjectNode já admitidos; não é parser nem validação de origem.
Não transforma comparação por igualdade Jackson em hash/texto, não calcula patch
e não resolve B0/C0/B1. Não exige registro de componentType por conveniência.

## Semânticas preservadas

Table continua exigindo autoria compacta por schema, identidades de colunas
válidas e sua restrição atual de null em todo config. Form continua permitindo
null em arrays atômicos e negando null literal em objetos mesclados. Essa extração
não legitima a diferença de políticas como regra universal; revisão semântica
dessas restrições permanece separada.

Envelope/presença/reprodução agora têm uma implementação comum no Java.
`UiLayoutAuthoredPresence` e `UiLayoutMergePatch` continuam sendo os mecanismos
existentes, sem variantes por componente. Regras específicas ficam nos adapters;
novos componentes só poderão reutilizar a relação se seu formato cumprir essas
premissas. Nenhuma lib UI é executada no backend.

## Validação e limites

Suites Table/Form incluem corpora finitos existentes produzidos em TypeScript;
na extração original, corpus não foi regravado nem promovido a publicação real. Testes do helper cobrem
pins iguais, objetos com ordem diferente, resets aninhados, arrays/null, envelope
e integridade dos inputs. Chamadores comando/frozen/history/evolution são
validados no recorte focal. Isso não atesta baseline Compras nova ou aceite PG/UI.

Mantêm-se os [limites por entrada](ui-layout-json-input-boundaries.md), inclusive
os gaps HTTP/preparse. A extração não acrescenta quotas ou recursão ilimitada
como promessa pública; limites precisam ser impostos nas fronteiras apropriadas.

O pacote original foi correção em source apenas: sem package/install, a rc.167 congelada não contém
esta extração nem a correção posterior de decoder. Consumidor/JAR corrigido deve
ser validado em pacote próprio, sem sobrescrever a candidata histórica.
