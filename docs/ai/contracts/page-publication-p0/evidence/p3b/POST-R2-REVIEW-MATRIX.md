# Parecer independente: matriz mínima após R2

Revisor: p1d_groups, diferente do autor p2_catalog. Revisão de fonte e evidências, sem executar Maven ou conectar ao banco. Este arquivo externo foi solicitado pelo coordenador; nenhum checkout foi editado.

Fontes: produção `0eb159862ba9fcd8fbcecb1ad851f6ba30fe5218`, testes `1e1334554ab3d4160b2be8e2a7178408f6d1eb60` e addendum Published `e6c75aec7561920dda5fa0a3431f96827f891ef4` (duas assertions persistidas, conferidas pelo coordenador). Critério: proposta R2 `c2fad547b78964c717a2ccdaaf1b92cbec742105fffdbc7da36a6126bbfaf9fc`.

## Resultado já conferido

D32 pós-R2: `/tmp/praxis-page-p3b-512-evidence/neon-density32-prefetch-1e133455`. SHA/árvore, 40 hashes de fonte, dois brutos, comando/seletor, run-start/final e XML conferidos independentemente. Uma execução verde, fonte inalterada; operação aceita em 6.126s, 17 statements preparados e seis getters de isolamento. Todos os 32 IDs semeados foram carregados e observados pela extensão structure; dois vetores, um batch, managed baseline 0/peak 192 e heads/audit fresh inalterados. Os callbacks observados permanecem iguais ao baseline D32. Isso não aceita 129 ou 1.000 páginas, heap, SLA ou cancelamento de I/O. Retenção é amostra de bytes/entradas por owner e total simultâneo observado; CPU é medida real apenas do thread atual.

Não identifiquei bloqueador de fonte adicional para D129 seletivo. O fallback metadata com synchronization ativa sem transação real foi corrigido em 0eb e tem negativa sem SELECT. D129 em e6c75aec foi autorizado pelo coordenador e permanece pendente neste parecer.

## Provas novas e capacidade ainda pendentes

Executar serialmente, com seletor explícito e revisão do diagnóstico entre estágios:

| Seletor | Finalidade e limite |
| --- | --- |
| `PageRuntimeDensityPostgresIT#diagnoseOneHundredTwentyNineAuthorizedPagesAcrossBatchBoundary` | Comparação atribuível após R2; sucesso exige dois lotes, dois vetores, IDs completos e segundo batch start de volta ao baseline managed. Um diagnóstico 503 após orçamento não aprova capacidade nem segundo lote. |
| `PageRuntimeDensityPostgresIT#mostlyPrivateCatalogDoesNotStopBeforeAuthorizedLastBatchOrLoseGlobalOrder` | 127 páginas privadas e duas visíveis nos extremos; scan integral de ambos os lotes em cada GET, ordem global, sem falso fim nem head receipt privado. |
| `PageRuntimeDensityPostgresIT#demonstrateOneThousandAuthorizedPagesWithinExistingBudget` | Gate de 1.000: sucesso obrigatório no orçamento existente, todos os IDs carregados e observados por structure, dois vetores, consumo integral e retenção entre lotes. Falha diagnóstica não conta como aprovação. |
| `PageRuntimeBatchPostgresIT#corruptRootMetadataRejectsOtherwiseCorrelatedPublicationWithoutPartialCatalog` | Captura pública corrompida na fonte SQL, controle positivo, catálogo limit1 integralmente 503 e URL 503, sem parcial/receipt/304; heads/audit fresh preservados. |
| `PageRuntimeBatchPostgresIT#bytePrefixPreservesExactBoundaryOversizedAndMissingWorkspacesWithoutSkippingIdentities` | Fecha a prova pendente do prefixo de workspace, pesos PG exatos, oversized-first e sourceDraft ausente preservados. Não prova o prefixo RawRow novo. |
| `PageRuntimePostgresIT#independentPublishedCopyReadsItsOwnHeadAfterOriginWithdrawalAndAncestorGrantRevocation` | P2 cria cópia Published real com release/revision fixadas; catálogo/URL/menu usam head próprio após retirada da origem e negação de grants ancestrais. Admissão corrente negada continua 503. Exercita RawPageRow, PublishedKey e terceiro owner de metadata. As assertions fresh de origem retirada e release do destino foram acrescentadas em e6c75aec. |

## Repetições históricas justificadas pelo diff R2

Somente estes três métodos existentes de `PageRuntimePostgresIT` precisam de repetição focal pelo recorte alterado:

| Seletor | Por que repetir |
| --- | --- |
| `participatingReadsObserveOwnWritesAndDoNotFreezeEarlierSnapshotsUntilCommit` | Metadata/readset/prefetch novos participam do beforeCommit e de transações REQUIRED externas. Provar read→write→read, commit legítimo, rollback global e fresh state. |
| `continuationBindsTheAuthorizedProjectionAndRejectsContextAbaWithoutLeakingPrivateHeads` | O SQL de ledger e sua materialização mudaram. Revalidar app/namespace/tenant/env, contexto A→B→A, cursor e projeção autorizada sem receipts privados. Seus scopes usam operações/transações distintas; não alegar isolamento de RawPageKey na mesma synchronization. |
| `currentSelectorAndLastMappingExcludeContentBeforeReceiptsAndConditionalSuccess` | O fence final agora tem prefetch em janelas. Revalidar todos os mappings antes de grants finais, selector/cross-unit, metadata/structure atuais e negação antes de 304. |

## Duas lacunas SQL que os seletores atuais não fecham

1. **Prefixo RawRow real:** testar ledger e metadata no envelope SQL exato `jsonb_build_object(...)::text`, incluindo escaping/nulls, fronteira de 8MiB, limite de 128, missing e primeiro oversized. Medir que só o prefixo selecionado é transportado/retido e que todos os candidatos avançam sem truncamento ou aceitação por quota. O batch IT de workspace usa outro caminho; `ConfigImmutableSqlRowTest` usa JDBC simulado. Nenhum deles substitui esta prova de SQL novo.
2. **Corrupção privada no SQL real:** com READ_HEAD negado, ledger/captura malformados ou com hash inválido não devem executar semântica privada nem mudar receipt/cursor/conteúdo autorizado. Com READ_HEAD permitido e selector incompatível, somente a captura pode ser ignorada: o ledger já era validado antes do selector. A mesma fonte, quando grant/selector admitirem, deve falhar 503 sem resposta parcial/304. Usar controles contrafactuais e registros cuja correlação restante seja válida; não fabricar selector/assignment/workspace incoerentes para explicar a negativa. Units 0eb cobrem esses gates com I/O simulado, não a query PG nova.

O autor pode combinar estes cenários em addendum focal de `PageRuntimeBatchPostgresIT`, a ser revisto antes de executar. Não são necessários SPI, hook de produção, DDL ou autoridade paralela.

Refinamento unitário focal recomendado: RawPageRow com mesmo owner/synchronization, prefetch no namespace original → outro namespace → original, além de chaves app/tenant/env. Isso testa a chave do cache sem forçar alternância legítima de contexto dentro de uma transação runtime externa, cujos callbacks antigos devem recusar contexto obsoleto.

## Evidência histórica reutilizável

- `PageRuntimePostgresIT#fullVectorDetectsNullHeadPhantomsAndHeadAbaAcrossIndependentTransactions`: vetor/flush/recollect/tuples não mudaram; a negativa unitária de phantom/ABA continua válida. Não repetir A2 inteiro por reflexo.
- `PageRuntimePostgresIT#aCommittedPublicationWithLostResponseReconcilesThroughANewReadOwner`: mutação, COMMIT, ACK sintético e contrato HTTP/receipt não mudaram. C1 permanece prova histórica atribuída ao seu SHA, sem ser somada à suite final.
- `PageRuntimePostgresIT#publishedCatalogAndMenuShareTheHeadButUrlAndMenuKeepDistinctGrants`: D5 permanece coberto pela evidência histórica, unitário específico e pelo novo Published/B2 focal. O unitário `lastMetadataAndLastMappingCallbacksCannotRevokeGrantsAndStillReturn` cobre a revogação após callback. Reconsiderar somente se o próximo diff modificar esses caminhos.
- Os testes de `ConfigTransactionReadSetTest` verdes em 529 continuam válidos porque essa classe de produção não mudou desde aquele SHA: `nestedSavepointRollbackDiscardsPositiveBytesWithoutRenewingTransactionAdmission`, `suspendsForRequiresNewAndResumesTheOriginalConnectionReadSet`, `rejectsChangedConnectionAndRollbackOnlyEvenOnAHit`, `reusesOnlyPositiveImmutableBytesWithinTheSameTransactionAndOwner`, `prefetchChecksBeforeAndAfterLoaderAndRetainsAnEntireBoundedPrefix` e `repeatedPrefetchReplacementDoesNotDoubleCountBytesOrRetainMissingRows`. Cleanup é compartilhado entre read/prefetch. Não alegar que o teste NESTED simulado é prova SQL Neon; o A3 focal repete commit/rollback externo com os stores novos.
- Manter os 72 verdes em 0eb e o loop unitário posterior identificados por seus próprios SHAs/seletores. Não somar rodadas sobrepostas como suite única nem repetir todos os unitários sem mudança/falha nova.

## Limites do aceite

Nenhum cache de grants, contextos, audience, heads, mappings, misses ou knownPage foi autorizado. JSON/capturas devem ser novos por consumo; binding/correlação e callbacks atuais permanecem obrigatórios em hits. Os bounds são por owner, e fontes Published podem alcançar três ou mais owners; não há teto global de 16/24MiB nem prova de heap. SQL/getters só medem execuções concluídas; não inferir CPU por subtração. Sem aumento de budget para fabricar capacidade. Guidance, consumidores, artefato e documentação final continuam gates do coordenador, separados desta matriz.

## Resultados posteriores conferidos independentemente

As execuções abaixo usam `e6c75aec7561920dda5fa0a3431f96827f891ef4`, árvore `23e72e90d087c4175095511c87bdede9004af9b9`, produção 0eb. Em cada diretório foram conferidos 40 hashes de fonte via Git, dois hashes brutos, comando/seletor, run-start/final, fonte inalterada, exit0 e XML. Cada execução tem um teste sem falhas/erros/skips; não são uma suite somada. Logs confirmam role/schema/branch atribuídos, PG17.11 e TLS1.3/peer JDBC.

- `neon-density129-prefetch-e6c75aec`: operação aceita em 9.764s/40s; 129 IDs carregados uma vez e 129 alvos semeados observados por structure, dois vetores/dois lotes, managed starts `[0,0]`, peak768 e heads/audit fresh iguais. Aprova o estágio 129 dessa fixture.
- `neon-density1000-prefetch-e6c75aec`: gate obrigatório aceito em 35.476s/40s; 1.000 IDs carregados uma vez e 1.000 alvos semeados observados por structure, dois vetores/oito lotes, managed starts oito vezes zero, peak768 e heads/audit fresh iguais. Aprova capacidade da fixture nativa sintética com policies sem I/O remoto externo; não constitui SLA, heap bound, cancelamento de I/O ou capacidade de 1.000 cadeias Published.
- `neon-private129-prefetch-e6c75aec`: XML1/0/0/0, 95.312s incluindo setup. Duas leituras limit1 atravessam quatro lotes no total; as 129 identidades são carregadas duas vezes. Das 127 privadas e duas visíveis, a última identidade do catálogo com order menor retorna primeiro; a primeira identidade retorna depois e só então termina a continuação. Os dois receipts de cada resposta pertencem apenas ao grupo e à página visível; heads/audit fresh permanecem iguais. Isso prova scan integral/ordem/fim e ausência de receipts privados, não admissão das 127 páginas privadas nem corrupção privada SQL.

Os três diretórios estão sob `/tmp/praxis-page-p3b-512-evidence/`. O addendum SQL RawRow-prefix/corrupção privada, os demais seletores pendentes e o aceite geral continuam separados destes resultados. Nenhuma nova execução foi feita pelo revisor.

Dois resultados adicionais na mesma fonte/árvore e com a mesma conferência independente de 40 fontes, dois brutos, comando, freeze e identidade TLS/runtime:

- `neon-workspace-capture-prefetch-e6c75aec`: XML2/0/0/0, 115.471s. Captura pública otherwise-correlated corrompida nega catálogo/URL 503 sem parcial/receipt/304 depois do primeiro alvo válido; fresh heads/audit iguais. Workspace-prefix preserva sete IDs em prefixos 2/1/1/1/2, fronteira exata 3+5MiB, primeiro oversized 8MiB+1 e sourceDraft ausente; fontes inválidas são negadas no runtime. Não é prova do prefixo RawRow novo.
- `neon-runtime-regressions-prefetch-e6c75aec`: XML4/0/0/0, 807.618s, somente os quatro métodos selecionados. Published-copy 224.316s: cópia P2 real com head próprio, origem persistida sem active release, release do destino persistida correta, catálogo/URL/menu concordantes e sem receipt ancestral; READ_SOURCE/COPY_SOURCE ancestrais não são chamados, metadata atual é chamada e sua revogação nega 503. Os heads/audit fresh permanecem iguais. Continuation 313.205s: ordem/identidade, cursor, scopes e contexto ABA, alteração privada sem alterar receipt autorizado. CurrentSelector 145.300s: USER/cross-unit, último mapping revogando grant, metadata/structure antes de sucesso condicional e parent 404 uniforme. Ownwrites 113.149s: REQUIRED observa retirada/republicação própria, rollback externo preserva head/audit por conexão fresh e o fluxo separado commita legitimamente. Não extrapolar as durações de setup/teste para latência runtime.

Esses resultados fecham os seletores correspondentes da matriz nesta fonte; não fecham S1/S2/S3 RawRow-prefix, corrupção privada SQL e chave no mesmo owner/transação, nem o pacote de integração/consumidores/guidance. O autor prepara o addendum test-only aprovado, sujeito a checkpoint/compile/revisão antes de executar Neon.
