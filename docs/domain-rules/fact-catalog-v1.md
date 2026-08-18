# Domain Rule Fact Catalog v1

O catálogo de facts é o vocabulário governado que conecta uma definição de decisão
aos fatos resolvidos pelo host. Ele pertence à definição no Config; projeções do
Studio, formulários e prompts de LLM não são fontes primárias.

## Contrato

`definition.factCatalog` usa `schemaVersion=praxis.domain-rule-fact-catalog.v1` e
uma lista `facts`. Cada fact declara:

- `path`, `valueType` e `nullable`;
- `labels` e `descriptions` por locale;
- `providerRef` e `evidenceRefs`;
- `sensitivity`: `NON_SENSITIVE`, `PERSONAL`, `SENSITIVE` ou `SECRET`;
- `redaction`: `NONE`, `MASK`, `HASH` ou `OMIT`.

Tipos v1: `boolean`, `string`, `number`, `date`, `string-array` e `date-array`.
Paths são únicos e o catálogo possui no máximo 256 entradas.

## Fronteiras

- Config valida, persiste, versiona e expõe o catálogo.
- O host resolve valores; o catálogo não move facts nem credenciais para o Config.
- Rules Engine avalia o snapshot com facts já resolvidos.
- Studio e assistente usam a projeção read-only para edição, cenários e explicação.
- `redaction` governa exposição de valores, não autorização para executar uma regra.

Definitions legadas sem catálogo continuam legíveis e retornam catálogo vazio. Para
adotar v1, o produtor cria nova versão imutável; não altera a versão histórica.
