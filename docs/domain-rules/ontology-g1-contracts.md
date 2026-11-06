# Contratos candidatos da ontologia — preparação de G1

Data: 04/11/2026. Status: **G1 aprovado como contrato candidato; G2 pode iniciar**, após a [correção dos seis achados](../../../praxis-ergon-showcase/docs/rules/ontology/curated/completion/remediation/README.md) e 900 testes focais (seção 10). A cobertura consta na [curadoria completa](../../../praxis-ergon-showcase/docs/rules/ontology/curated/completion/README.md). Complementa o [blueprint](C:/Users/rodrigo.moreira/.gemini/antigravity/brain/6fa001e2-f9ef-4b40-bbe7-2369a4d1945c/praxis-ontology-first-principles-blueprint.md). Não registra v2 no serviço nem modifica contratos ativos. G2–G5 seguem pendentes de implementação e prova.

## 1. Resultado verificável e limites

Foram materializados o [schema candidato](contracts/praxis-domain-rule-fact-catalog-v2.candidate.schema.json), uma [fixture de catálogo](contracts/examples/ontology-facts-v2.candidate.json), os [valores correspondentes](contracts/examples/ontology-facts-v2.values.json), um [fragmento de grafo com dois bindings de determinação](contracts/examples/determination-v02.candidate.json) e [testes JUnit](../../src/test/java/org/praxisplatform/config/service/DomainRuleFactCatalogCandidateContractTest.java). As fixtures são sintéticas e escritas deliberadamente; não documentam regras de negócio Ergon.

O [inventário Ergon](../../../praxis-ergon-showcase/docs/rules/ontology/README.md) contabiliza 1.056 pontos de escrita em 20 arquivos examinados, 18 famílias/274 bindings de produto e 38 definições/50 chamadas operacionais. Inclui construções inline, lifecycle, previews, adaptadores de campos customizados e diagnósticos, discriminados no JSON. Pontos de escrita não equivalem a fatos únicos nem a cobertura de todos os ramos de execução.

**Curadoria completa:** os 39 perfis das quatro famílias de chefias foram complementados por 49 perfis das 14 famílias restantes e 38 operacionais: **126 perfis**, com schemas, bindings e fixtures. Há destino para 575 paths de produto/fábrica, 50 chamadas operacionais e todos os 1.056 pontos do censo. O pacote inclui 36 projeções do compilador de determinações e extensão tipada de campos customizados. O inventário da seção 6.1 foi conferido por SELECT em `ps3b_config`, em 04/11/2026 às 11:30:23 UTC: três definições de prova e zero snapshots/heads. O destino de cada definição consta no pacote; nenhum dado foi alterado.

## 2. Contrato de fatos e fronteiras

O Config continua dono de `definition.factCatalog` e de seu ciclo de vida. O literal em uso permanece `praxis.domain-rule-fact-catalog.v1`. O candidato incompatível é `praxis.domain-rule-fact-catalog.v2`, no mesmo endpoint de fatos da definição.

As assinaturas de wire materializadas nos records internos do teste são:

```java
record Catalog(String schemaVersion, List<Operation> operations) {}
record Operation(String operationKey, JsonNode snapshotSchema, List<Fact> facts) {}
record Fact(String schemaPointer, Map<String, String> labels,
            Map<String, String> descriptions, String providerRef,
            List<String> evidenceRefs, String sensitivity, String redaction) {}
```

Esses records são fixtures de round-trip, não DTOs públicos novos. Em F4, substituir os DTOs canônicos e atualizar consumidores no mesmo corte, com documentação OpenAPI, cópias defensivas de listas/mapas/árvores e testes de imutabilidade. O envelope preserva `definitionId`, `ruleKey` e `definitionVersion`; não perder identidade ao trocar sua coleção de fatos por operações. O Engine recebe um contrato estrutural neutro, sem importar DTO Config, Spring, HTTP ou modos de formulário.

`operationKey` identifica uma operação de avaliação registrada pelo host, resolvida no escopo da definição/RuleSet e associada à operação canônica correspondente. A chave genérica `validate` de uma família não permite inferir CREATE/UPDATE/DELETE. Manter um registro explícito `(RuleSetRef, operationKey) -> provider + schema + operação canônica`; ausência, duplicidade ou associação ambígua impedem publicação/admissão. Comandos e previews têm identidade própria e não herdam schema de CRUD por nome.

A estrutura `snapshotSchema` é a autoridade única para tipo, presença e cardinalidade. Os descritores apontam para nós do schema; `#/properties/neighborhood/properties/periods/items/properties/dtini` não é caminho JSON Logic. Não manter `path/valueType/nullable` redundantes no documento v2. Uma UI pode derivar uma projeção transitória, com sua origem no schema, sem gravá-la como segundo contrato.

### 2.1. Perfil estrutural materializado

| Elemento | Regra |
| --- | --- |
| Raiz | Objeto não nullable. Disponibilidade de `draft/current` definida por operação; na fixture CREATE, `current` é explicitamente null. |
| Escalares | `string`, `number`, `integer`, `boolean`, `null`; variantes nullable usam `[tipo,"null"]`. Números finitos; nenhuma coerção de string. |
| Objetos | `properties`, `required`, `additionalProperties` e `maxProperties` explícitos. Extra proibido ou schema homogêneo de valores de mapa. Nunca `additionalProperties: true`. |
| Coleções | `items` homogêneo e `minItems/maxItems` explícitos. Arrays de objetos e arrays aninhados permanecem estruturados. |
| Strings | `maxLength` obrigatório. Enum finito; null no enum somente se o tipo o admitir. Nulabilidade não ignora um enum que exclua null. |
| Datas | `format=date`: data civil real `YYYY-MM-DD`, sem timestamp. `format=date-time`: instante com segundos e offset explícito; validar calendário, sem normalizar para data civil. |
| Data e hora local | `format=praxis-local-date-time`: `YYYY-MM-DDTHH:mm:ss`, fração opcional de 1–9 dígitos e máximo de 29 caracteres, calendário válido e sem offset. Usado na vigência de `PARAMETROS_CONTA`; seleção inclusiva preserva a hora, sobreposição normativa aplica TRUNC somente no RuleSet. Persistência PostgreSQL tem precisão de microssegundos; admissão de escrita e eventual rejeição de precisão superior são gate de G3. |
| Decimal exato | `format=praxis-decimal`: string canônica de até 128 caracteres, igual ao resultado de `BigDecimal(text).toString()`. Preserva escala/expoente, sem número paralelo ou coerção por JavaScript Number. O tipo/schema define separadamente se null é permitido. |
| Referências | O schema submetido não admite `$ref`, composição recursiva ou acesso remoto. Os refs internos do meta-schema são da própria implementação. |
| Descritores | Exatamente um por nó estrutural abaixo da raiz, inclusive contêiner, `items` e `additionalProperties` tipado. Textos localizados, fonte/evidências e governança obrigatórios. |
| Escopos AST | `map/filter/all/some/none` vinculam var local ao item; `reduce` distingue item e acumulador. Acesso dinâmico não comprovável é rejeitado. Não fabricar paths globais com `[*]`. |

Duração e fuso presentes em `ProvaHorariosAcumulacao` não são datas civis. O candidato inclui `praxis-local-time` (HH:mm, segundos e fração opcionais, sem offset), `praxis-duration` (gramática de `java.time.Duration`, sem meses/anos de calendário) e `praxis-zone-id` (`ZoneId.of`). `date-time` preserva instantes com offset. Os formatos são validados executavelmente no gate G1, incluindo horários em 24:00, datas impossíveis, duração de calendário e fuso inexistente. Duração negativa é estruturalmente representável; o executor de horários deve devolver INCONCLUSIVE, conforme a decisão de G3. Replay fixa relógio, fuso e versão de runtime/tzdb.

### 2.2. Limites candidatos e verificações

| Limite | Valor | Onde aplicar |
| --- | ---: | --- |
| Operações por catálogo | 64 | Meta-schema |
| Descritores por operação | 256 | Meta-schema |
| Propriedades/chaves por objeto | 256 | Meta-schema + valores |
| Strings em valores | 64.000 code points no schema e 64.000 unidades UTF-16 no Engine | Aplicar ambas as restrições; não supor equivalência para caracteres fora do BMP |
| Itens por coleção | 10.000 | Schema + limite Engine |
| Profundidade estrutural de schema | 32, raiz em 0 | Validador semântico |
| Nós estruturais por schema | 4.096, incluindo raiz | Validador semântico |
| Bytes UTF-8 do catálogo serializado | 1.024.000 | Antes de aceitar/persistir |
| Profundidade/nós/bytes de fatos | 64 / 10.000 / 256.000 | Limites atuais do Engine; valores concretos |
| Ponteiro / providerRef / evidenceRef | 2.048 / 255 / 1.024 caracteres | Meta-schema |
| Idiomas / evidências por descritor | 16 / 32 | Meta-schema |

O validador documental do teste verifica os limites do catálogo/schema e invariantes adicionais: operações/descritores únicos, ponteiros existentes, cobertura descritiva, required pertencente a properties e limites numéricos/cardinalidades coerentes. A validação de valores verifica tipos, estrutura e formatos de data, incluindo mapas tipados. Antes de G1, testar também todas as fronteiras de bytes/profundidade/nós; antes de G3, mover a semântica compartilhada ao Engine, aplicar os limites antes da descida recursiva e provar rejeição determinística sem stack overflow. O helper de teste não deve virar um segundo validador de produção no Config.

Reter sensibilidade/redaction existentes (`NON_SENSITIVE/PERSONAL/SENSITIVE/SECRET` e `NONE/MASK/HASH/OMIT`). Autorização e política de IA continuam canônicas. Um contêiner não pode tornar um filho restrito publicável. Não tratar os modos de redaction como uma ordem numérica inventada; respeitar a política efetiva por nó e recusar publicação sem proteção demonstrável. O snapshot interno não é mutilado para facilitar sua exibição.

## 3. Binding conceitual e migração dos construtores

O descritor Metadata deve declarar, por operação, os bindings de todas as raízes conceituais, além do schema registrado pelo host. A identidade de destino inclui serviço proprietário, contexto, recurso/conceito e papel; FK composta conserva todos os componentes e tenant. Referências externas têm identidade e evidência explícitas, sem conceder operação CRUD.

Casos concretos a fechar antes de G1:

| Caso | Contrato requerido |
| --- | --- |
| `titular/substituto` apontam para vínculo | Papéis distintos, `numfunc` + `numero/numvinc` e isolamento de tenant; não somente pessoa. |
| `cargoAglutinador` | Self-reference ao recurso; não pode degradar o proprietário. |
| `Substituicao.funcao` | Registrar travessia efetiva via `designacao.funcaoId`; não fingir FK direta do rascunho. |
| `Substituicao.impedimento` | A curadoria substitui a união implícita por `feriasImpedimento` e `afastamentoImpedimento`, com bindings independentes, FK, tenant e vínculo titular. Preservar as duas referências quando fornecidas; exclusividade, estado e escolha de fim pertencem ao RuleSet. Não manter alias da raiz antiga. |
| `effectiveParametroConta/effectiveStatutoryRule/politica` | Seleção de política e overrides não são conceitos 1-hop. Projetar candidatos primitivos, preservar precedência no dono da decisão e provar customização. |
| `existing*/active*` | Coleções conhecidas migram para neighborhood; não usar filtro textual de prefixo para definir o que é conceito. |

A fonte legada `v7x/aps/fontes_ergon/pck_ferias.pkb:565–577` confirma o caso em que `PERMITE_FER_ESTE_PER` igual a S/N sobrepõe os quatro parâmetros de período. Isso impede substituir a seleção efetiva por uma simples renomeação de raiz. A leitura focal comprova esse trecho, não toda a paridade de férias.

O corte beta altera `DomainRuleSetDescriptor` e suas fábricas. Migrar também `DomainRuleSetCatalogProvider.ofDefinitions/of`: hoje essas rotas sintetizam descritores sem bindings. Exigir informação explícita quando houver conceitos; manter conveniência vazia apenas para escopos comprovadamente sem conceitos. Não acrescentar overload que esconda o requisito.

Consumidores encontrados: `ErgonDomainRuleSetCatalogProvider`, `QuickstartDomainRuleSetCatalogConfiguration` e `SemanticDomainCatalogServiceTest`, além das fábricas internas. Os testes de integração devem cobrir todas as entradas da SPI, não só o construtor principal.

## 4. Determinações e targets dos bindings

O produtor continua sendo `ReactiveDeterminationMetadataCompiler.compile`. `node.metadata.reactiveDetermination` preserva sua saída: `id`, `trigger`, `scope`, `capability`, `inputs`, `outputs` e `provenance`. Papéis de gatilho/contexto derivam dessa estrutura; não criar `edge.metadata`.

Targets candidatos a implementar/testar em F2:

| bindingType | target |
| --- | --- |
| `api_operation` | Cópia exata de `compiled.capability`: `operationId`, `method`, `href`, `requestSchemaUrl` e `responseSchemaUrl`. |
| `form_config` | `schemaOperationId` e `formMode` do scope; `schemaUrl` resolvida pelo resolver canônico para request do formulário; `configurationPath="/x-ui/reactiveDeterminations"`; `determinationId`; `configuration` com a saída compilada integral. |

A entrada do array é selecionada por id dentro do scope, não por índice. Não montar URL no projetor. A fixture literal pode fixar URLs como contrato independente, mas a implementação deve obtê-las dos resolvers. Rejeitar target cujo scope/id/configuração divirja do nó ou cujo endpoint/método/schema não corresponda à operação resolvida.

Uma mesma determinação em CREATE e EDIT publica nós/bindings distintos. Para `ON_INIT`, preservar sourcePaths vazio quando assim compilado. Arestas são recurso/saída `governed_by` determinação e determinação `depends_on` entrada; não afirmar causalidade individual saída–entrada. O teste focal já prova que o schema v0.2 aceita metadados no nó e rejeita metadados na aresta; **equivalência com catálogo HTTP/compilador real continua G2**.

## 5. Autoridade, histórico e reparo

Contrato candidato do resolvedor compartilhado no Config: receber escopo confiável, identidade completa do destino e candidatos elegíveis; retornar definição autoritativa íntegra, referência documental não resolvida ou conflito explícito. Não resolver só por nodeKey, ordem de consulta ou timestamp de ingestão.

1. Resolver o proprietário a partir do vínculo confiável produtor/recurso. Serviço de A que cita B é proveniência da referência, não proprietário de B.
2. Aplicar elegibilidade do ciclo de vida da superfície; ingestão não ativa federação.
3. Classificar referência nova (`placeholder=true`/`referenced-concept`) e histórica (`source=api-resource` com tag `referenced-concept`), corroboradas por bindings/release/ownership.
4. Selecionar definição elegível do proprietário pela revisão/head canônico. Proprietários conflitantes bloqueiam uso operacional; sem proprietário, nenhuma promoção automática.
5. Preservar a unidade inteira, inclusive política, identidade, payload, evidências e sourceRelease. Aplicar autorização/IA após resolução; negação não permite fallback permissivo.
6. Conservar enriquecimentos humanos aprovados sob a proveniência de Knowledge; referências contribuem relações/evidências separadas.

Aplicação obrigatória: `DomainKnowledgeProjectionService`, `DomainCatalogIngestionService.searchSummaries/contextLatest`, `DomainCatalogPromptContextService`, `DomainFederationQueryService`, `Domain360CatalogService`, caches e RAG. Não assumir que todos leem Knowledge.

Reparo: derivar novamente de releases elegíveis imutáveis, sem alterar seus bytes/hash/versão/aprovação. Processar identidade inteira em transação; registrar checkpoint/diagnóstico seguro; reconstruir derivados após commit e mantê-los indisponíveis se a reconstrução falhar. Provar replay em ambas as ordens, segunda execução idempotente e retomada com PostgreSQL real antes de G4. APIs já existentes de change set e publicação RAG conservam a autoridade; não criar linha paralela de ownership.

## 6. Coordenada do host e ativação

Fixar como **coordenada candidata do corte incompatível** `ergon-policy-studio/2.0`. A constante em produção permanece `ergon-policy-studio/1.0`; a troca só ocorrerá com todas as famílias, regras operacionais, schemas, registry e composições migrados. Esta coordenada não decide major Maven/npm.

Reutilizar `PublishedRuleSnapshot.requiredHostContractVersion` com comparação exata, acrescida da comparação estrutural e da validação de executores/AST. Token correto com folha antiga continua incompatível. Contrato declarado por cliente não certifica provider instalado.

**Não criar um segundo mecanismo de rollout.** O [fluxo canônico existente](staged-activation-readiness-v1.md) e `DomainRuleRolloutService` já possuem candidato imutável, admissão, policy, preload isolado, probes, quorum, revisão/ETag e ativação:

1. Publicar revisões novas e obter aprovação real; não modificar snapshots antigos.
2. Criar rollout escopado ao candidato/hash e ao head esperado. Primeiro head: `If-None-Match: *`, política governada REQUIRED e quorum positivo; head existente: `If-Match` forte.
3. Config só admite candidato READY com integridade/compatibilidade; host novo prepara/compila fora do runtime ativo e envia probe seguro. Heartbeat do head atual não certifica candidato.
4. Para este corte incompatível, preparar política REQUIRED pelo fluxo governado também em heads existentes. O modo OBSERVE_ONLY do produto não dispensa integridade/admissão.
5. Infraestrutura contém operações, instala o conjunto candidato e coordena a ativação usando `X-Rule-Rollout-ID`/ETag, com revalidação sob lock. Host adota atomicamente provider/registry/schema/plano compatíveis.
6. Smoke real e prova de retomada antecedem liberação de tráfego.

O preload isolado é preparação canônica já existente, não alias de fatos nem autorização para duas semânticas ativas. A extensão de admissão tipada deve integrar o verificador interno já usado na criação/readiness/ativação de rollout e no host.

Rollback segue a recuperação canônica: ETag atual forte, snapshot anterior verificado e provider/registry/schema anterior compatível. Não depende de quorum do candidato; conter tráfego até restaurar o par íntegro. Reverter apenas head deixa o host incompatível.

### 6.1. Inventário operacional do ambiente e escopo de governança verificado

1. **Mapeamento Neon e bancos separados no Ergon:**
   - `ErgonPersistenceConfiguration.ConfigStore.configDataSource` exige banco (`current_database()`) e usuário (`current_user`) distintos entre domínio (`SPRING_DATASOURCE_*`) e Config (`CONFIG_DATASOURCE_*`), abortando o startup caso coincidam.
   - O projeto Neon `praxis-config` (`ancient-leaf-72055027`, branch `production` / `br-aged-lake-a4eb715l`, database `neondb`) atende ao `praxis-api-quickstart` (14 definições `desenv`/`local`); não é utilizado pelo Ergon.
   - O `praxis-ergon-showcase` utiliza o projeto Neon `holy-mouse-40140108`:
     - `main` (`br-bitter-bonus-b7i0vfko`, database `neondb`, 16 tabelas em `public`);
     - `issue-305-host-proof-20261027` (`br-super-sound-b7urizmd`, database `neondb`, 16 tabelas em `public` + 32 em `praxis_bulk`, branch base sem TTL);
     - `ps3b-gate-20261103` (`br-purple-dust-b70ss3dj`), que separa o database de domínio **`ps3b_ergon`** (`public` com 30 tabelas até V10 + `praxis_bulk` com 32 tabelas) e o database Config **`ps3b_config`** (`public` com 66 tabelas Config + `ps3b_tx_probe`).
2. **Escopo de governança e estado atual de definições/snapshots/heads:**
   - No Ergon, `tenantId` Config = código decimal do órgão (`empCodigo`) em String (`"1"`, `"2"`, `"3"`, `"7"`), `serviceKey` / `ownerServiceKey` = `"praxis-ergon-showcase"`, e `environment` vem da autoridade `ERGON_CONFIG_SCOPE:<organization>:<environment>` validada por `ErgonConfigAccess`.
   - Em `ps3b_config`, existem **0 snapshots** em `domain_rule_snapshot`, **0 heads ativos** em `domain_rule_snapshot_head` e **3 definições aprovadas de prova** em `domain_rule_definition` (`tenant_id='2'`, `environment='stage'`, `service_key='praxis-ergon-showcase'`, incluindo `customer.orgao2.replace.chefia.erg-01154`).
   - A ausência de snapshots/heads ativos persistidos é esperada: o Ergon opera em memória com as 18 famílias (`productRuleSet` e `orgao2FactoryRuleSet` em `ErgonRuleEngineFacade`), `scenarios.json` e o fluxo de preparação/admissão (`ErgonPolicyComposition.prepare` / `admitGovernedSnapshot`).
3. **Instaladores de migração e política de branches:**
   - Domínio (`public`): `src/main/resources/db/migration/V5__*.sql` a `V10__*.sql` (`spring.flyway.enabled=false`, `ddl-auto=validate`).
   - Lote (`praxis_bulk`): `InstallBulkStorage.java`.
   - Config (`public` no banco Config separado): `InstallConfigStorage.java` (com `CONFIG_MIGRATION_USERNAME` distinto de `CONFIG_DATASOURCE_USERNAME`).
   - Testes que exijam Neon real para o Ergon devem usar branch descartável apenas no projeto `holy-mouse-40140108` (com TTL ≤ 24h, derivada da branch base atribuída e removida após captura das evidências).

## 7. Mapa de consumidores e derivados

| Dono | Fontes a migrar no corte |
| --- | --- |
| Config | `dto/DomainRuleFactDescriptor.java`, `DomainRuleFactCatalogResponse.java`, `DomainRuleService.java`, catálogo confiável de implementação, verificador de snapshots/rollouts, OpenAPI e schemas empacotados/documentais. |
| Engine | Contratos/planner/compiler de snapshot, validação estrutural pura, AST local e contratos de executores; manter dependências neutras. |
| Metadata | `DomainRuleSetDescriptor.java`, `DomainRuleSetCatalogProvider.java`, `SemanticDomainCatalogService.java` e integração com compilação canônica de determinações. |
| Ergon | Providers e construções inline/posicionais inventariadas, regras/templates/executores, `ErgonPolicyComposition`, facade e catálogo; inclui previews e lifecycle. |
| Quickstart | `src/main/java/com/example/praxis/apiquickstart/config/QuickstartDomainRuleSetCatalogConfiguration.java` e testes/downstream afetados. |
| Angular Core | `projects/praxis-core/src/lib/models/domain-rule.model.ts` e `services/domain-rule.service.ts`; projeção estrutural reutilizável e testes. |
| Policy Studio | Repositório `praxis-policy-studio`: `src/app/core/projection-catalog.service.ts`; em `features/catalog`: `catalog-presentation.ts`, `catalog-workspace.component.html`, `scenario-facts-editor.component.*`, `scenario-sandbox.facade.ts` e `workspace-authoring.facade.ts`. |
| Derivados | AI-RESOURCE-BLUEPRINT, GUIA-00, guias de domínio, exemplos/HTTP corpus, docs públicas e skills fontes em praxis-codex-skills conforme impacto. Não alterar skills instaladas como substituto da fonte. |

Studio hoje trata `nullable` como `required` em superfícies e monta drafts por path plano. A migração deve separar ausência de null, selecionar operação, editar árvores/items/mapas e preservar governança no round-trip. Atualizar só o tipo TypeScript não basta. Não fabricar cenários ALLOW/DENY por rótulo/tipo como evidência de paridade.

Classificação: alteração arquitetural/transversal de contrato público, incompatível no wire e no host. Corte beta limpo no monorepo; leitura histórica versionada permanece evidência, sem trilha de authoring/execução v1 concorrente. Canal, semver de pacotes, consumidores externos e workflows precisam ser confirmados antes de eventual publicação; nenhum bump/tag nesta etapa.

## 8. Registro histórico do aceite de G1 — superado pela revisão

**Aceite anterior de G1, superado pelos seis achados G1-R01–G1-R06.** O [pacote completo](../../../praxis-ergon-showcase/docs/rules/ontology/curated/completion/README.md) e o [recibo histórico](../../../praxis-ergon-showcase/docs/rules/ontology/curated/completion/verification.json) registram a execução anterior. A seção 9 conserva a revisão que reabriu G1; a seção 10 registra as correções e o novo aceite. O recibo histórico não foi alterado.

| Condição de G1 | Resultado e evidência |
| --- | --- |
| Universo e destino semântico | 18 famílias, 274 bindings de produto e 38 definições/50 chamadas operacionais contabilizados; 575 paths com destino; ledger revisado dos 1.056 pontos. |
| Schemas e operações | 126 perfis: 39 do lote inicial, 49 das outras famílias e 38 operacionais. HTTP por identidade real, OLD/NEW, conceitos, coleções e nulabilidade explícitos. |
| Formatos, limites e governança | Valores máximos e máximo+1 exercitados; rejeição antecipada de schema excessivamente profundo; LocalDate/Instant/LocalTime/Duration/ZoneId validados; proteção de filhos em contêineres. |
| Contratos dinâmicos | Nove projeções do adaptador e 141 pontos internos com destino; seis tipos de campo, escopo e hash da definição. Duas fixtures sintéticas por órgão, sem alegar definições reais persistidas. |
| Determinações | 18 definições/36 escopos compilados pelo compilador canônico, com golden completo e dois bindings por escopo; inputs contextuais não viram sourcePaths. |
| Histórico, customizações e ativação | SELECT confirma três definições, zero snapshots/heads. Candidato de nova versão para a customização; provas IAM retidas sem execução. Aprovação e ativação seguem o mecanismo da seção 6. |
| Consumidores, construtores e derivados | Donos e corte incompatível identificados nas seções 3–7; host/2.0 candidato, sem alias preventivo. Publicação/consumo real e artefatos públicos são gates de implementação posteriores. |

Execução final: **287 testes no Config + 301 no host = 588**, zero falhas, erros ou ignorados. Inclui os testes do primeiro lote repetidos sobre as fontes atuais; não somar execuções históricas. O teste cruzado exige `ontology.g1.host-contracts`: sem a propriedade, a execução isolada ignorada não comprova G1.

```powershell
# Config, JDK 21 / Maven 3.9.6
mvn -B "-Dtest=DomainRuleG1SupplementContractTest,DomainRuleG1BoundaryContractTest,DomainRuleG1CompletionContractTest,DomainRuleFactCatalogCandidateContractTest,DomainRuleCuratedHostCandidateContractTest,DomainCatalogContractSynchronizationTest" "-Dontology.g1.host-contracts=D:/Developer/praxis-plataform/praxis-ergon-showcase/docs/rules/ontology/curated" test

# Host, compilador separado por limitação Lombok observada no processo Maven
mvn -B "-Dmaven.compiler.fork=true" "-Dmaven.compiler.executable=D:/Developer/JAVA/graalvm-jdk-21.0.2+13.1/bin/javac.exe" "-Dtest=OntologyG1CompletionContractTest,OntologyCuratedLeadershipContractTest,OperationalRuleSetTest,ErgonReactiveDeterminationMetadataTest" test
```

Os testes são focais e não iniciam Spring/JDBC/Flyway. O build padrão do host, suite integral, paridade completa de negócio, HTTP em execução e instalação de consumidor pelo registry público não foram certificados. A leitura Neon está registrada separadamente; nenhuma escrita, instalação, migração, branch, head ou snapshot foi alterado. Artefatos derivados de produção serão atualizados no corte real, sem apresentar o candidato documental como contrato já suportado.

**Na revisão da seção 9, G2 ficou condicionado à correção e revalidação de G1; a condição foi atendida na seção 10.** Seu escopo futuro continua sendo a implementação dos bindings e da projeção canônica metadata-driven com regressões de identidade/papéis e HTTP. G3 migra providers/regras e prova paridade por código; G4 integra Config/consumidores/reparo; G5 executa aprovação, publicação, ativação e rollback. A passagem a G2 não dispensa nenhum dos gates seguintes nem autoriza publicação antecipada.

## 9. Revisão de integridade antes de G2 — 04/11/2026

**Decisão histórica da revisão: G1 reaberto / CHANGES REQUESTED; resolvida na seção 10.** O [parecer](D:/Developer/praxis-plataform/praxis-ergon-showcase/docs/rules/ontology/curated/completion/review/README.md) reproduz identidade incorreta de ação, retificação monetária com representação incoerente, dupla recomposição de saldo, excesso de orçamento com 300 linhas de parâmetros, nulabilidade inadequada em coleção persistida e texto corrompido em seis catálogos.

Corrigir contratos e bindings, adicionar regressões independentes por cenário e reexecutar os gates focais antes de novo aceite. As 13 sondas desta revisão são observações de contracasos/controles; não são uma aprovação adicional. Os 588 testes anteriores continuam históricos. Não promover helpers de testes a uma segunda implementação de validação em produção.

A correção deve definir transformação monetária verificável, origem primitiva dos argumentos, aplicabilidade por ação, nulabilidade por origem, população completa dentro de orçamento e preservação UTF-8. O parecer explicita também critérios de UX e governança por persona para G3/G4. G2 permanece sem início, e esta revisão não acessou nem alterou banco.

## 10. Correções G1-R01–G1-R06 e novo aceite — 04/11/2026

**G1 aprovado no escopo de especificação candidata**, com [900 testes focais e recibo próprio](../../../praxis-ergon-showcase/docs/rules/ontology/curated/completion/remediation/verification.json): 370 no Config e 530 no host, sem falhas, erros ou ignorados. Os 588 anteriores permanecem históricos. As execuções finais usaram fontes copiadas para diretórios de build isolados; a propriedade `ontology.g1.host-contracts` apontou explicitamente para o host dessa cópia.

As soluções e regressões estão no [registro completo](../../../praxis-ergon-showcase/docs/rules/ontology/curated/completion/remediation/README.md): identidade por `WorkflowAction.id` e constantes por chamada/etapa; representação decimal única `praxis-decimal`; origem primitiva do saldo nas 50 chamadas; recorte relacional completo com orçamento conjunto; nulabilidade a partir da origem persistida; restauração fiel de 3.795 ocorrências UTF-8. O documento também registra pré-condição/NEW para comandos de tempo/licença, a rejeição de uma ação válida de outra chamada e a preservação dos códigos atômicos.

A capacidade de todos os 126 perfis tem referência automatizada de limite e limite+1; duas extensões sintéticas por órgão foram testadas junto às quatro coleções de Vínculo. Isso define o envelope do candidato e a rejeição integral de excesso, sem prometer capacidade ilimitada ou desempenho em banco. Casos de carga que excedam o envelope exigem contrato de agregado neutro ou executor governado antes da admissão em G3/G4.

O meta-schema candidato agora admite `praxis-decimal`, mas nenhuma API de runtime passou a aceitar v2. O Engine é o dono futuro da validação compartilhada; Config e Studio devem consumir essa implementação em G3/G4. O editor deve preservar escala e distinguir ausência/null/zero; a simulação deve separar falha técnica de inelegibilidade; lote e auditoria devem manter ação individual, OLD/NEW e versão/hash. Não copiar helpers JUnit para produção.

G2 pode iniciar sua implementação metadata-driven. G3–G5 conservam paridade por código, capacidade real, isolamento, concorrência, persistência, publicação/admissão, UX, corte e rollback como gates próprios. Não houve acesso ao Neon, alteração de produção ou publicação nesta correção. Os artefatos públicos derivados serão atualizados com a implementação, conforme o mapa da seção 7.

## 11. Entrega do candidato Metadata G2 — 04/11/2026

O [registro G2](../../../praxis-ergon-showcase/docs/rules/ontology/g2/README.md)
documenta a publicação canônica das determinações e dos bindings conceituais,
com 642 testes locais: 94 Metadata, 532 Ergon e 16 Quickstart. O formato de catálogo
v0.2 do Config permanece inalterado; seu schema é confrontado com a cópia de teste
do produtor. Esta etapa não implementa ingestão, projeção, admissão ou Studio v2.

O aceite do host se refere à base G1 preservada acrescida dos deltas G2 registrados.
A primeira campanha sobre a árvore compartilhada teve seis falhas de aderência a
G1 por mudanças concorrentes. Sua reconciliação e a reexecução dos gates afetados
precedem o aceite integrado G3/G4. O recibo de 900 testes da seção 10 continua
histórico e intacto. Não houve acesso ou alteração no Neon nem publicação externa.
