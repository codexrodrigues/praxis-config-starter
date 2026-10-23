# P3-B #512 — revisão independente de alinhamento de consumidores e guidance

Estado: **alinhamento de fonte apto no recorte revisado, com uma correção documental Config pendente**. Este parecer não aprova Neon, capacidade, artefato, publicação, adoção do host, HTTP operacional ou UI.

Revisor: `/root/ux_page_journey`, distinto dos autores do Config e das duas skills. Revisão por leitura; nenhum Maven, build, teste, banco, HTTP, servidor ou sincronização de skills instalada foi executado. Somente este parecer externo foi gravado.

## Fontes fixadas

| Owner/consumidor | Commit inspecionado | Worktree |
|---|---|---|
| Config produção | `0eb159862ba9fcd8fbcecb1ad851f6ba30fe5218` | `${CONFIG_SOURCE}` |
| Config HEAD de testes | `e6c75aec7561920dda5fa0a3431f96827f891ef4` | mesma worktree |
| Core | `db795d9da6264bc18ab4eb1c96bf292f949a21d0` | `${USER_HOME}/Dev/pessoal/praxis-plataform/praxis-ui-angular/.worktrees/core-page-runtime-p3b-512` |
| Quickstart | `0182e679d3c0f5b525d4e87954e1db869145c3df` | `${USER_HOME}/Dev/pessoal/praxis-plataform/praxis-api-quickstart/.worktrees/page-runtime-p3b-512` |
| Landing | `7ded893306c97aa4e731ebf018b9960785ac42c3` | `${USER_HOME}/Dev/pessoal/praxis-plataform/praxis-ui-landing-page/.worktrees/page-runtime-p3b-512` |
| Skills canônicas | `d9e22a7cae29bc078492baa58dc98c0a1b5c14ce` | `${USER_HOME}/Dev/pessoal/praxis-plataform/.worktrees/page-publication-p1-skills` |

Os checkouts Config/Core/Quickstart/Landing estavam limpos. Skills tinha apenas `scripts/__pycache__/` não rastreado, sem alteração das fontes examinadas. `git diff 0eb15986..e6c75aec -- src/main` é vazio: ambos apontam `src/main` para `1cfc6b18ccebc3c812ac8241de479d78f872255f` e `pom.xml` para `4860ad33bb0d33d3702814b40d86c17037ebf9a7`. O delta entre esses commits contém somente três arquivos de testes; não se atribui execução a eles neste parecer.

Instruções aplicadas: AGENTS do workspace, guia `docs/engineering/agentes-e-modelos.md`, instruções locais e governança canônica de skills já inspecionadas neste trabalho. Escopo: revisão transversal de contrato público existente; sem contrato novo e sem alterar checkout.

## Achado documental a corrigir antes do fechamento

**Config `README.md:512–514` ainda descreve defaults permissivos inexistentes.** As linhas dizem respectivamente que o provider default expõe tenant ativo, árvore de navegação vazia e lista de eventos vazia. `DefaultEnterpriseRuntimeNavigationProvider.java:6–11` lança `EnterpriseRuntimeProviderUnavailable.failure(request)`; os defaults de tenants/eventos seguem a mesma política de indisponibilidade. A autoconfiguração registra esses defaults quando o host não fornece seu provider (`EnterpriseRuntimeAutoConfiguration.java:61–78`). A navegação autenticada com versão esperada válida retorna 503/no-store, conforme a implementação, o design e os textos candidatos Quickstart/Landing.

Esse drift já conhecido permanece no HEAD inspecionado. A correção pertence ao escritor Config; não exige alteração de wire, Core ou provider do Quickstart. Ele impede chamar a documentação oficial final de integralmente alinhada enquanto essas três descrições persistirem. Nenhuma nova divergência funcional de consumidor foi identificada neste recorte.

## Wires e transporte

| Ponto | Correspondência de fonte |
|---|---|
| Schemas | `PageCatalogResponse.java:6–9` usa `praxis.page-catalog/v1`; `PublishedPageResponse.java:9–13` usa `praxis.published-page/v1`; `EnterpriseRuntimeNavigationResponse.java:8–19` exige `praxis-enterprise-runtime-navigation.v1`. Core model `enterprise-runtime-context.model.ts:88–97` usa esse mesmo literal de navegação. |
| Forma da navegação | Java mantém `nodes`, `capabilities`, `headReceipts` não nulos e sem membros null por `List.copyOf`, exige `resolvedAt` e contexto/receipt não vazios. Core exige os mesmos campos, `nextCursor: string | null`, `resolvedAt: string`, e reutiliza `UiLayoutReleaseHeadReceipt` pelo import local do owner na linha 1. Sem DTO paralelo de receipt ou novo cliente de página. |
| Node | Java `EnterpriseRuntimeNavigationNode.java:5–20` inclui `icon`, exige ID não vazio e preserva `children == null`; lista presente não aceita null. Core model `:71–85` é compatível com os campos opcionais/null do host genérico. No owner Config, `PageRuntimeReadService.java:142–164` materializa grupos com children null e entradas de página com lista vazia, ícone admitido e identidade pageId+bindingId. |
| Consulta lazy | `navigation` usa a mesma rota `/api/praxis/runtime/navigation`, sem parent para roots, parent UUID de grupo para bindings. Provider recebe `(request, expectedContextVersion, parentRef, cursor, pageSize)`. Controller `:153–175` lê `X-Praxis-Context-Version` e padrão50/intervalo1–100. Core service `:123–170` usa os mesmos nomes e faixa; cursor é string opaca limitada4096, interpretação fechada permanece no servidor. |
| Contexto e gerações | Core captura `contextRevision` antes de `await ready`, exige ready/version não vazia e reconfirma geração+versão depois de ready, depois de preparar headers, em sucesso/erro e no contexto da resposta. Reutiliza ObsoleteError e preserva erros HTTP da geração corrente. `contextVersion`, receipt e headEtag não substituem a geração local, inclusive A→B→A. |
| Conditional e erros | `PublishedReadHttp.java:12–31` valida receipt, produz `W/"receipt"`, comparação fraca após o owner e private/no-cache; erro sanitizado/no-store nos handlers. Head forte continua separado para escritas. Core não envia If-None-Match nem guarda corpo. Navegação mapeia ausência/negação para404 uniforme, conflitos para409, indisponibilidade/integridade para503; sessão401 e parâmetros400. Erros de conversão usam a família sanitizada já existente, admitida pelo design. |
| Catálogo/publicação | `PageRuntimeController.java:16–36` mantém `/api/praxis/config/pages` com cursor/limit e `/{pageId}/published`, ambos exigindo contexto e usando o helper conditional após resolução. Não foi criada segunda rota de navegação nem fallback para draft/B0/personalização. Core não anuncia cliente de catálogo/publicação neste corte. |

Os hashes/receipts públicos derivam da projeção autorizada; os heads incluídos são apenas o root de grupos autorizado e as páginas retornadas (`PageRuntimeReadService.java:118–179,441–482`). O digest do cursor vincula ator/app/tenant/env/namespace/contexto/parent/tamanho; `PageRuntimeCursor.java:18–73` valida formato fechado e revalida posição/visão. Um cursor bem formado não constitui grant. Esta constatação é de leitura de implementação, não prova executada de todos os estados HTTP.

## Construtor, autoconfiguração e consumidores efetivos

R2 altera intencionalmente o construtor Java beta de `PageRuntimeReadService.java:51–78`: recebe `PageCreationService`, sem overload paralelo. `UiLayoutResolutionAutoConfiguration.java:260–276` passa o bean `pages` existente e as wrappers dele, usando o mesmo recurso JDBC/transactions. `PageCreationService.java:53–67,80–93` mantém um `PageCatalogStore` e fornece seu prefetch e seus adapters; não se criou segundo owner de catálogo para aquecimento, bean de adapter ou ciclo novo. Navegação continua dependendo de delegação explícita do host; registrar PageCreationHost/PageRuntimeReadService não substitui um provider customizado nem instala menus.

Quickstart não contém referência direta a `PageRuntimeReadService`, `EnterpriseRuntimeNavigationNode`, `EnterpriseRuntimeNavigationResponse` ou `EnterpriseRuntimeNavigationProvider` em `src`; portanto não tem construtor Java antigo a migrar neste recorte. `EnterpriseRuntimeConfiguration.java:22–59` continua composição de contexto, sem provider de navegação. O POM permanece no Config público rc.166 e Metadata rc.178; não se declara adoção do candidato por existir fonte compilável.

O teste Quickstart alterado `EnterpriseRuntimeContextQuickstartIntegrationTest.java:258–296` obtém escolha admitida, seleciona, relê ready, extrai a versão confirmada e a envia ao default fail-closed. Limpa somente as invocações de setup antes da asserção de ausência de uso do serviço de contexto pelo handler. Não fabrica versão e não instala provider. Compilação e execução HTTP/Neon não foram feitas neste parecer e continuam pendentes nos cortes atribuídos.

Quickstart `README.md:1175` explica esse limite e separa compilação do consumidor, P3-C HTTP e P4 visual. Landing `consumer-integration-quickstart.md:214–237` condiciona navigation à presença do provider e ready, usa choiceRef+expectedSelectionVersion e mantém explícitas a ausência de renderer/routes e adoção dos pacotes. Não há drift novo nesses dois documentos candidatos.

## Guidance versus R2 implementado

As duas skills existentes continuam suficientes; classificação **atualizar-existente**, incremento canônico presente em d9e22a7, publicação/integração e sincronização instalada não inferidas.

- Core `SKILL.md:79–104` coincide com a implementação db795: receipt owner-local, icon/nullchildren, geração antes de ready e guards completos, erros obsoletos, parâmetros copiados, sem cache; diferencia o spec HTTP público, build de consumidor e eventual E2E visual. Nenhum consumidor visual novo foi implementado.
- Persistence `references/published-page-runtime.md:80–130` coincide com o caminho R2 observado. `ConfigTransactionReadSet.java:28–81` admite loader SQL dentro da transação e verifica holder/conexão/JPA antes/depois; limites128/8MiB por owner, replacement descontado e cleanup savepoint/completion estão em `:84–116`. Mutação continua pelos guards existentes. Não há memoização de autoridade nem renovação de prazo.
- `PageCatalogStore.java:15–17,67–108,130–151` conserva todas as colunas brutas e parse/correlação no consumo. `PageCatalogLifecycleBridge.java:108–124` executa delegate antes de registered/byPage. `PageRuntimeReadService.java:334–348` conserva READ_HEAD→frozen/selector→native admission. Negar grant evita o consumo semântico do ledger/capture; negar selector não desfaz o ledger já verificado.
- O catálogo usa `PageKey(application,tenant,environment,namespace,page)` distinto de `PublishedKey` com release/revision (`PageCatalogLifecycleBridge.java:232–259`) no mesmo owner. `JdbcUiLayoutMetadataCaptureStore.java:134–185` usa identidade física RowKey, verifica scope antes do hit e binding completo pelo codec a cada consumo, criando objetos novos a partir das strings. A consulta bruta não valida documento privado antecipadamente.
- `ConfigImmutableSqlRow.prefix` escolhe prefixo por quantidade/peso e verifica UTF-8 efetivo, avança missing/primeiro oversized sem retenção negativa, e não elimina candidato. `PageRuntimeReadService.java:351–390` consome cada prefixo antes do refill; `:393–435` mantém todos os callbacks de mapping antes da cerca final de grants, contexto/audience finais e segundo vetor fresh. Outer beforeCommit repete autoridade, sem terceiro vetor, permitindo read→write→read dentro de REQUIRED.
- A skill exige prova real de criação P2 de fonte PUBLISHED e não equipara1000 seeds nativas a1000 cadeias. O HEAD de testes contém o cenário correspondente em `PageRuntimePostgresIT.java:332–383`: publica destino próprio, retira origem, nega grants ancestrais, compara catálogo/URL/menu e nega dependência atual. Sua presença confirma alinhamento do procedimento; não atesta execução verde.
- Nenhuma alegação na skill converte SQL/texto de workspace/bytes retidos por owner em limite de heap global, nem diagnóstico503 em capacidade aceita. O terceiro store metadata alcançável pela bridge permanece uma consideração separada. O gate1000 e suas métricas não foram avaliados por este revisor.

## Hashes focais das skills

Calculados com os helpers canônicos somente para leitura de hashes; nenhum script de auditoria/sync/teste foi reexecutado.

| Artefato | SHA256 |
|---|---|
| Core SKILL.md | `A179F90E763A97C2E53152BD548BB64E1A66739A5E36FD2E6577F1E26C168E3F` |
| Core skill tree | `D1482251DD8FDCAE8061D13CCF3CD421E2B543EA3D5342C0E6DD44F7558087E8` |
| Persistence SKILL.md | `49E5C21FDEC9FF7AB66360F998EE061245DC5E6EDA2DE68E4E306A90E6469145` |
| Published-page runtime reference | `5BE558639B9D293224AE713E84D9AC604A98C66EE5EAA94C01D24F0487BD9636` |
| Persistence skill tree | `A0634F92E2B4D039569DDA282A07608308F7D1F45EC948306EF867BCD68A5B26` |

As duas entradas do manifest correspondem aos hashes SKILL/tree observados. O fechamento transitive/audits temporários anteriores não foi reexecutado nem reclassificado como auditoria de instalação real.

## Limites e próximo checkpoint

Não identifiquei bloqueador novo no wire, consumidor efetivo ou procedimento das duas skills. Corrigir a tabela do README Config antes de afirmar alinhamento documental final. A aprovação global continua dependente das negativas SQL, gate1000/métricas, evidência final vinculada à fonte/dependências, pacote privado e compilação Quickstart atribuída. D32/D129 foram informados como aprovados pela coordenação; não foram revalidados aqui. P3-C host HTTP e P4 renderer/rotas/E2E permanecem fora deste aceite. Nenhum resultado deste relatório prova publicação do pacote ou disponibilidade do endpoint no host/site.

A preparação Quickstart anterior continua válida para um futuro artefato privado `0.0.0-p3b.512-SNAPSHOT`, após recebimento de JAR/POM finais, vínculo SHA/hash e atribuição exclusiva. JARs históricos Config rc.162/Metadata rc.171 copiados por process-test-resources são dados de fixture, não o candidato do classpath. Nenhum artefato privado foi recebido, instalado ou compilado nesta revisão.
