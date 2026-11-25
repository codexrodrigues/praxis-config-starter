# Contrato canônico `praxis.domain-rule-fact-catalog.v1`

Versão canônica única do catálogo de fatos governados anexado a `DomainRuleDefinition.definition.factCatalog` e servido em `GET /api/praxis/config/domain-rules/definitions/{id}/facts`.

## 1. Estrutura base (obrigatória por fato)

Cada item em `facts` declara obrigatoriamente:

- `path`: caminho estável do fato (`^[A-Za-z][A-Za-z0-9_.:-]{0,254}$`), único na definição (máximo de `256` fatos por definição).
- `valueType`: `boolean | string | number | date | string-array | date-array | number-array | array<number>`.
- `nullable`: booleano indicando se aceita valor nulo.
- `labels`: mapa de rótulos curtos por idioma (`{"pt-BR": "..."}`) contendo apenas locales realmente disponíveis (sem duplicação artificial).
- `descriptions`: mapa de descrições semânticas de negócio por idioma, sem concatenação de texto operacional.
- `providerRef`: identificador do provedor/contexto proprietário do fato.
- `evidenceRefs`: lista não vazia de referências rastreáveis de origem/linhagem.
- `sensitivity`: `NON_SENSITIVE | PERSONAL | SENSITIVE | SECRET`.
- `redaction`: `NONE | MASK | HASH | OMIT`.

## 2. Metadados editoriais, de apresentação e governança (opcionais e *fail-closed*)

Campos aditivos evoluídos no contrato único (mantendo leitura compatível com definições históricas armazenadas no banco):

- `hints`: orientação operacional complementar por idioma (`<= 500` chars por entrada), derivada de `@UISchema.helpText` / `@UISchema.hint`.
- `groupKey` (`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, `<= 64` chars) e `groupLabels` (`<= 120` chars por idioma): agrupamento editorial derivado de `@UISchema.group`.
- `valuePresentation`: taxonomia canônica minúscula (`currency`, `percentage`, `date`, `datetime`, `time`, `boolean`, `number`, `integer`, `duration`, `identifier`, `text`).
- `unit` (`<= 32` chars) e `mask` (`<= 64` chars): unidade e máscara explícitas.
- `allowedValues`: de `1` até `64` opções de domínio fechado (`value`, `labels`, `icon` `<= 64`, `tone` `<= 32`, `description` `<= 256`) com valores escalares únicos compatíveis com `valueType`. **Proibido quando `sensitivity = SECRET` ou `redaction = OMIT`.**
- `optionSourceRef`: referência declarativa para lookup dinâmico (`{ sourceKey (<= 128), resourceKey? (<= 255) }`).
- `example`: valor de exemplo (`<= 256` chars). **Proibido quando `sensitivity = SECRET` ou `redaction = OMIT`.**
- `constraints`: restrições numéricas/textuais (`min <= max`, `step > 0`, `0 <= minLength <= maxLength`, `pattern` regex válido `<= 256` chars).
- `governance`: `{ classification?, dataCategory? (<= 64), exportable?, aiVisibility?, complianceTags? (<= 32 itens não vazios) }`. Se `SECRET` ou `OMIT`, `aiVisibility` deve ser `"deny"`.
- `lineage`: `{ attributeId? (<= 255), sourceResourceKey? (<= 255), transformation? (<= 64) }` — caminhos internos de persistência (`sourcePaths`) são rejeitados na ingestão e nunca publicados.

## 3. Overlay *read-only*, precedência e projeção sanitizada na IA

- **SPI `DomainRuleFactMetadataOverlayProvider`:** `MetadataDtoFactMetadataOverlayProvider` permite enriquecer metadados editoriais na leitura de `GET .../facts` diretamente dos DTOs vinculados em `GovernedRuleFamilyRegistry`, sem mutar linhas JSONB imutáveis nem alterar o hash SHA-256 (`DomainRuleDefinitionFingerprint`).
- **Precedência editorial no overlay (`DTO > curado > JSON persistido`):** o DTO Java anotado é a fonte canônica de semântica editorial (`labels`, `descriptions` sem placeholder genérico, `hints`, `groupKey`/`groupLabels`, `allowedValues`, `optionSourceRef`, `constraints`, `mask`, `unit`, `valuePresentation`), preservando fallback em catálogos curados ou no JSON persistido apenas quando o DTO não declarar o atributo.
- **Guarda *fail-closed* no `DomainRuleService`:** o overlay nunca altera `path`, `valueType`, `nullable`, ordem ou cardinalidade dos fatos, nunca enfraquece `sensitivity`/`redaction`, e remove `example` e `allowedValues` sempre que o fato for efetivamente `SECRET`/`OMIT`.
- **Projeção sanitizada na IA (`DomainDecisionExplanationEvidence` / `SafeFactEvidence`):** publica `facts[]` (`DomainDecisionExplanationSafeFact`) apenas quando o modo de exposição global permite (`full` ou `summary_only`, emitindo `factCatalog` em `redaction.omittedFields` nos modos `masked`/`denied`) e omite qualquer fato com `sensitivity = SECRET`, `redaction = OMIT` ou `governance.aiVisibility = deny` (registrando `factCatalog.secretOrDeniedFacts` em `redaction.omittedFields`). Fatos permitidos em modo `summary_only`, `MASK`, `HASH` ou `aiVisibility = mask|summarize_only` suprimem `hints`, `allowedValues` e `constraints` (registrando `factCatalog.summarizedOrMaskedFacts`), e `example`, `lineage` e `optionSourceRef` nunca são copiados para a evidência da IA.
- **Imutabilidade de histórico:** versões históricas sem os campos aditivos permanecem válidas na leitura e comparação sem exigir migração SQL.
