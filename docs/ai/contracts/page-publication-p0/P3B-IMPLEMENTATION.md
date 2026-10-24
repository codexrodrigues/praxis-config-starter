# P3-B — implementação e limites de validação

Candidato da [issue #512](https://github.com/codexrodrigues/praxis-config-starter/issues/512), [PR #519](https://github.com/codexrodrigues/praxis-config-starter/pull/519). A biblioteca entrega leitura publicada, catálogo e projeção de navegação. As rodadas RawRow e corrupção privada foram concluídas e revisadas; integração, pacote privado e provas dos consumidores continuam verificações separadas. P3-C/P4 permanecem etapas de adoção HTTP e jornada visual.

## Fonte canônica e impacto

O Config materializa leitura publicada, catálogo paginado e navegação a partir do catálogo P2 e dos heads/revisions/capturas do lifecycle existente. Não há novo ponteiro, draft fallback, overlay de preferências, ACL paralela ou cache de decisão de autorização. A URL e o menu observam a mesma publicação da página; a visibilidade do grupo pode ocultar o menu sem retirar o acesso direto autorizado à página.

O pacote é `arquitetural`, `contrato-publico` e `transversal`. Os endpoints/DTOs de leitura e o provider beta de navegação evoluíram na fonte Config. A otimização R2 mantém o wire, mas altera o construtor Java público beta de PageRuntimeReadService para receber o PageCreationService existente; não é correto classificar todo R2 como sem contrato público alterado. AutoConfig e callers/fixtures diretos foram atualizados, sem overload preventivo. Core wire/generation, consumidor Quickstart, Landing e guidance possuem revisão e gates próprios coordenados no mesmo ciclo; não são provas de P3-C/P4.

## Leitura, autoridade e coerência

A leitura fixa um vetor interno completo de identidades/headETag/versão sob READ_COMMITTED, inclusive heads ausentes/retirados. Flush dos próprios writes JPA precede ambas as coletas JDBC fresh. Entre elas, os registros congelados são correlacionados e reproduzidos pelos codecs existentes, admitindo produtor, metadata, filhos e estrutura atuais. Mudança de vetor resulta em conflito explícito, sem retry interno ou renovação de budget.

READ_HEAD não basta para produtor/metadata: o helper frozen explícito continua obrigatório. Grants e selectors atuais são rechecados depois das extensões de fonte/estrutura/mapping. URL de página usa os grants próprios; navegação intersecta grupo e página. Fontes Published mantêm a cadeia histórica fixa, sem exigir READ/COPY ancestral perpétuo. Transações REQUIRED externas retêm os checks de autoridade/deadline até a conclusão; cada receipt testemunha seu ponto de leitura, não writes futuros na mesma transação. Read→write→read continua permitido; rollback externo não confirma os retornos provisórios.

O cursor é opaco, limitado e não autorizador. Ele vincula contexto/ator/escopo/query, a ordenação e o digest da projeção autorizada completa. Mudanças privadas que não alteram essa projeção não mudam seu receipt/cursor. Parent oculto e desconhecido usam a mesma resposta segura. Nodes/capabilities/headReceipts não expõem páginas privadas; children lazy não revela contagem privada.

`receiptEtag` é identidade semântica, enquanto `resolvedAt` varia por observação. GET emite HTTP `W/"receipt"` e usa o parser condicional canônico após admissão atual. Head ETag persistido continua forte para writes. Erros têm no-store e conteúdo sanitizado; sucesso usa private/no-cache. Provider custom mantém precedência: se altera nodes, deve produzir receipt/cursor da projeção final, não reutilizar os do fragmento Config.

## R2: evidência imutável em prefixos, sem cache de autoridade

O diagnóstico original em 97ed mostrou N+1 e thrashing: D32 foi aceito em 23,856 s com 106 statements concluídos e 38 getters de isolamento; D129 não foi aceito, terminando em SOURCE_UNAVAILABLE após 40,389 s, com apenas um vetor/lote e 128 workspaces carregados. Esse diagnóstico não provava causa exclusiva de deadline, capacidade, segunda passagem ou 129 admissões. Os brutos foram preservados.

R2 usa o readset Config existente, vinculado ao owner, conexão, holder JPA/JDBC e lifetime da transação. A admissão de isolamento é por transação; hits preservam as verificações baratas de identidade/rollback/autocommit. Suspensão/REQUIRES_NEW, savepoint rollback e completion mantêm isolamento/limpeza. Não há ThreadLocal novo, negative cache, grant/head/context/audience cache ou retenção de decisões verificadas.

Os stores carregam RawRows com todas as colunas originais, sem parse semântico de conteúdo privado. Ledger conserva app/tenant/env/namespace/page; metadata usa a identidade física draft/tenant/env/root/target e verifica o Binding completo no consumo. Parse/correlação/reprodução e todos os callbacks atuais continuam em cada uso; JsonNode/captura são novos. READ_HEAD negado continua anterior ao consumo do ledger; selector incompatível continua anterior à admissão da captura. A cerca final conserva todos os mappings antes de todos os grants.

Cada janela real é limitada por 128 entradas e 8 MiB de bytes UTF-8 serializados por owner, e seu prefixo é consumido antes do próximo prefetch desse owner. Missing/oversize progridem sem cache negativo ou truncamento. O bound não é global nem de heap: Published pode alcançar um terceiro owner de metadata e outros owners na mesma transação. Vetor O(N), workspace/patches, objetos decodificados e topK da resposta são custos separados. A query de pesos pode avaliar candidatos que não devolverão payload; não se promete evitar TOAST ou custo de varredura.

## Resultados atribuídos à fonte

As fontes de produção e testes são distintas quando necessário. Produção R2 atual é 0eb159862ba9fcd8fbcecb1ad851f6ba30fe5218; os ensaios posteriores fixam suas próprias árvores/testes. Nenhuma soma de rodadas sobrepostas é apresentada como suite única.

| Ensaio | Fonte | Resultado e limite |
|---|---|---|
| A, três casos | 2b0db3a5 | 3 verdes: lifecycle/P2 reais, vetor/ownwrites e admissão. Evidência histórica, antes de R2. |
| B, dois casos; C, um caso | ad62bd9a | B2 verdes; C1 verde com ACK perdido sintético depois de COMMIT real, novo owner e revalidação pull. Não é crash de processo ou push. |
| D32/D129 anteriores | 97edb0e5 | Diagnósticos acima, preservados; D129 não aprovou capacidade. |
| Readset/stores/owner unidades | 529b7484, 0eb15986, 9119837f | Rodadas focais atribuídas separadamente (86,72,1); não somadas como suite única. |
| D32 pós-R2 | 1e133455 | 32 IDs carregados e observados por structure; 6,126 s/40 s, 17 SQL concluídos, 6 getters, 2 vetores/1 lote. Callbacks iguais ao baseline D32. |
| D129 pós-R2 | e6c75aec | 129 IDs carregados e observados; 9,764 s/40 s, 2 vetores/2 lotes, managed [0,0], peak 768. |
| D1000 | e6c75aec | Sucesso obrigatório em 35,476 s/40 s, 1.000 IDs carregados e observados, 2 vetores/8 lotes, managed baseline 0 nos oito inícios, peak 768, heads/audit fresh inalterados. |
| Private129 | e6c75aec | 127 privadas/2 autorizadas, quatro lotes em duas leituras, ordem global/continuação e nenhum receipt privado. Não prova corrupção privada SQL. |
| Workspace prefix/captura pública | e6c75aec | 2 verdes: prefixo de 7 IDs em 2/1/1/1/2, fronteira de 8 MiB/oversize/missing; captura corrompida otherwise-correlated gera 503 sem parcial/304. Não confundir com RawRow. |
| Published + regressões R2 | e6c75aec | 4 verdes: cópia Published por head próprio após retirada da origem, ownwrites REQUIRED, cursor/ABA/escopos e autoridade final. |
| RawRow | bfec7fe7 | 4 verdes, 659,143 s incluindo setup/calibração: ledger/metadata no envelope PostgreSQL exato, prefixos 128+1, 8 MiB, escaping/nulls/missing e chave de escopo no mesmo owner/transação. Oversized é SELECT sintético do helper, sem INSERT na tabela. |
| Corrupção privada SQL | bfec7fe7 | 1 verde, 164,035 s incluindo setup: baseline anterior ao INSERT negativo, ledger/captura privados ignorados antes do gate existente; captura USER incompatível ignorada; controle Bob saudável e mesma fonte admitida negada 503 sem parcial/304; receipt/cursor públicos e heads/audit fresh preservados. |

D1000 mediu 108 prepared statements concluídos, 20,186 s nesses statements, 20 getters / 2,877 s e CPU real 10.789.284.000 ns apenas do thread atual. Callback counts foram 16.000 native / 48.000 metadata / 17.000 structure; 1.000 raízes semeadas e uma outra raiz observada, não inferir identidade por contagem bruta. Retenção amostrada: 128 entradas por owner, 936.960 bytes no ledger e 365.824 em metadata; máximo simultâneo observado 1.302.784 bytes / 256 entradas. São amostras de readsets, não heap/limite global. SQL falho não entra no tempo de SQL concluído; não deduzir CPU por subtração.

Os seeds densos são registros sintéticos correlacionados com transform/codec/FKs/reviews/approvals/quatro eventos e triggers ativos, não 1.000 jornadas authoring/HTTP. A fixture usa callbacks sem I/O remoto externo. O gate não é SLA nem prova de 1.000 cadeias Published. Os RawITs calibram envelopes por SELECT com DriverManagerDataSource sem pool, abrindo conexões/TLS por chamada; duração total/calibração/seed não é desempenho da leitura runtime medida.

## Evidência, recursos e pendências

Export portátil em [evidence/p3b](evidence/p3b/README.md): manifests por rodada com hashes de fontes fixadas por Git, hashes brutos/exportados distintos, logs/XML sanitizados, runners retidos, referências DDL e preflight de identidade/TLS sem credenciais. XML properties foram removidas. O inventário de dependências foi colhido no export a partir dos classpaths históricos e não prova os bytes dos JARs no instante de cada execução. O scan incluiu o manifest serializado final. Os corpos de fonte não são duplicados: commit, caminho e hash permitem recuperá-los pelo Git.

Neon exclusivo: ancient-leaf-72055027 / br-weathered-mountain-a48o0map / ep-floral-voice-a40bufyv.us-east-1.aws.neon.tech; schema p3b_runtime_512_20261023; runtime p3b_runtime_512_runtime. PG17.11, runtime sem SUPERUSER/BYPASSRLS/CREATEDB/CREATEROLE, JDBC verify-full e SSLSocket/peer reais atestados em cada rodada. V64–68/V70 existentes; nenhuma DDL nova em R2. Branch preservada com autosuspend 300, sem reuso por P3-C sem nova atribuição.

Fechamento: o SQL addendum e S2 foram revistos independentemente; main `a6927d80` foi reconciliada em `4ec36ce44f7e49a76c44628d2ea45ae45db60498`. O delta externo contém somente o snapshot AI registry (praxis-table/TableConfig/generatedAt) e seu teste de hash. Java runtime, POM e migrações permanecem idênticos às provas R2. Não houve Neon após essa fusão. A revisão final da documentação, pacote privado, consumidores e integração das skills pertence ao fechamento coordenado. O pacote será archive do SHA final, alterando SOMENTE project.version do POM copiado para 0.0.0-p3b.512-SNAPSHOT; comparar src/main byte a byte, POM interno/META-INF/JAR e dependências. Não instalar candidato sob rc.166 público. Quickstart usa repositório próprio e compile focal contra esse artefato; não prova host HTTP operacional.

Limites permanentes: budget cooperativo não cancela I/O bloqueado; autoridade externa é reatestada, não participa de fence atômico distribuído; recuperação é pull por head/ETag persistido. A regra P2 de readmitir produtor inclusive em WITHDRAW permanece; não se promete retirada emergencial com fonte indisponível. Revisão/integração e encerramento de issues pertencem à coordenação, sem release/deploy nesta tarefa.

## Artefatos derivados e skills

Core, Quickstart e Landing foram confrontados no [parecer de consumidores](evidence/p3b/CONSUMER-GUIDANCE-REVIEW.md), que identifica seus SHAs e limites. O drift dos defaults no README Config foi corrigido para 503. O corpus P0/scenarios não foi alterado para anunciar jornadas HTTP ou browser não executadas; manifesto HTTP operacional e renderer pertencem a P3-C/P4.

Impacto nas skills: **atualizar-existente**. As skills canônicas de persistence e Core foram ajustadas no checkout de skills `d9e22a7cae29bc078492baa58dc98c0a1b5c14ce` e revisadas contra R2 no parecer. Integração/publicação canônica e sincronização instalada devem ser registradas pela coordenação; o presente documento não as infere. Não há necessidade de uma terceira skill ou contrato para este fechamento documental.

## Projeção exata para cópia — 24/10/2026

Incremento `contrato-publico` e `transversal`, baseado em `origin/main` `7f63d5cc2b449657f11fc59d27176bac3477d8ad`, na branch `codex/published-page-copy-source`. A semântica de seleção/cópia exata já é suportada por `PageSourceReference.Published` e pelo workspace congelado; a classificação interna é `ja-suportado-mal-nomeado-ou-mal-materializado`. A ausência da content revision na resposta operacional é `lacuna-real-de-contrato` na fronteira pública: um cliente que só leu uma publicação autorizada não conseguia selecionar essa mesma origem para copiar sem inventar dados ou recorrer a outra superfície.

`PublishedPageResponse.copySource` reutiliza o tipo existente, sem novo DTO, endpoint ou identidade. `PageRuntimeReadService.published` deriva page/release/revision do mesmo snapshot que passou `current.visible`, incluindo reprodução, correlação, selector e admissão atuais. Acrescenta o selector ao digest do receipt, mantendo ETag HTTP fraco e o schema beta `praxis.published-page/v1`. Não concede permissão de copiar; criação continua verificando suas operações atuais. Não há nova I/O/admissão, DDL, cache, fallback, mudança de budget ou alteração no head genérico.

Impacto: DTO/owner Config e consumidores da resposta publicada; construtores Java do record precisam do novo argumento no mesmo ciclo beta, sem overload de compatibilidade. O wire JSON ganha `copySource` obrigatório e não nulo. Quickstart/Hero precisam adotar o novo artefato Config antes de usar o campo. Não há mudança necessária no Metadata SDK ou Core. `P3B-DESIGN` foi sincronizado; o schema/corpus P0 de entrada já define esse selector e não precisa de uma variante nova. O corpus HTTP e o site operacional devem ser sincronizados na adoção coordenada, sem anunciar disponibilidade a partir desta fonte candidata.

Validação mínima: `PageRuntimeReadServiceTest` e `PageRuntimeHttpTest`, ambos `@Tag("unit")`, com owner/codecs reais e JDBC/repositórios simulados ou MockMvc standalone. Os casos novos conferem a revisão pinada admitida, vínculo da referência com o head, alteração do digest anterior e serialização/decodificação do selector `published-page` existente. As negativas e os fences existentes dessas mesmas suites permanecem no recorte. Resultado focal: **21 testes, zero falhas/erros/skips** (`PageRuntimeHttpTest`: 8, 1,142 s; `PageRuntimeReadServiceTest`: 13, 2,584 s), Maven exit 0 em 19,319 s. Comando: `mvn -B -Dmaven.repo.local=/tmp/praxis-page-p3b-512-m2 -Dtest=PageRuntimeReadServiceTest,PageRuntimeHttpTest -Dgroups=unit -DfailIfNoTests=true test`. Nenhuma prova Neon histórica é atribuída a esta projeção nova, nem foi repetida por uma mudança sem SQL ou persistência. Os resultados são unitários/offline; não provam publicação Maven, adoção no host, sessão HTTP real ou jornada browser.

Skills: `atualizar-existente` na referência canônica `published-page-runtime` depois de estabilizar o contrato; a coordenação é responsável por essa entrega e sua integração/sincronização. Este incremento não modifica skills nem publica artefato.
