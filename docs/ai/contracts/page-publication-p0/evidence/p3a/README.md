# Evidência P3-A — Config #511

Gates executados e exportados para revisão final, com diagnóstico de falhas anteriores e fontes individuais. Integração Config/guidance permanece sob coordenação; este pacote não antecipa merge.

O [manifesto](manifest.json) vincula cada rodada ao commit/tree e aos hashes obtidos por `git show <sha>:<arquivo>`, além do classpath efetivo, DDL, JAR local e identidade Neon. As propriedades dos XML foram removidas; testcases e falhas permanecem. Logs/XML brutos ficam nos caminhos registrados em `/tmp`, com hashes separados da versão sanitizada. Nenhum arquivo de credenciais foi exportado.

| Pacote | Escopo e interpretação |
| --- | --- |
| `unit-da6` | 101 XML preservados do baseline unitário; o log original tem 116, incluindo 15 core depois substituídos. |
| `core-5d8` | 16 casos core após correção/precondição; não somar aos 15 anteriores. |
| `initial-f044` | Rodada Neon original completa: três passaram, duas falhas e um erro preservados. |
| `corrections-5d8` | Somente os três casos afetados: freshness JPA, callback de estrutura e COMMIT real. |
| `races-ca7` | Seis corridas controladas com ambas as ordens de vitória, rollback, auditoria e retry explícito. |
| `density-e565-baseline` | Timeout de 40s após 704 páginas; rollback fresh comprovado, sem aceite parcial. |
| `prefix-8a6` | Prefixo SQL: limite exato, singleton oversized, origem sem draft, sete IDs sem omissão. |
| `density-8a6` | Mil páginas completas, dois passes positivos e sentinela negativa no último registro, budget40s. |
| `addendum-a-8a6` | Sete casos: faults SQL, plano alterado, head ausente, REQUIRED oposta, três transições e savepoint. |
| `addendum-b-8a6` | Cinco casos: quatro passaram; scope falhou por DENIED em vez de NOT_FOUND no outro namespace. Diagnóstico preservado. |
| `snapshot-kind-initial` | Cinco negativas passaram; positivo fixture usava patch vazio, corrigido preservando authored config. |
| `namespace-correction-final` | SQL/readset com namespace, isolamento na mesma transação e known/unknown404; 1/0/0/0 em133,078s no SHA final. |
| `snapshot-kind-final` | Delta defensivo final: root exige KIND esperado antes do único codec/reprodução; owner real com repos correlacionados;6/0/0/0 no SHA final. |

Os resultados são complementares e atribuídos às suas fontes; não são uma suite única na árvore final. A correção de namespace acrescenta o predicado SQL e a dimensão da chave do readset; o caso afetado de B5 é repetido de forma focal. O caminho válido do delta do binder só compara KIND antes do mesmo codec, sem repetir schema, SQL ou callbacks. A mudança anterior de produção entre 5d8 e 8a6 limita-se ao prefixo de lotes, comprovado pelos gates SQL/densidade; não foram repetidos por reflexo os casos menores já válidos.

Fonte final de produção/testes: `307e1079e08692279aa3b0b417441ff58a66b494`. O JAR foi apenas empacotado localmente, sem install/deploy/release. Detalhes e limites em [P3A-IMPLEMENTATION](../../P3A-IMPLEMENTATION.md).

O banco foi exclusivamente a branch `br-holy-water-a4ihmkkj` do projeto `ancient-leaf-72055027`, schema `p3a_group_511_20261022`, runtime `p3a_group_511_runtime`. Os logs comprovam endpoint atribuído, PostgreSQL17.11, TLSverify-full/TLSv1.3 e peer. O preparo owner e a prova runtime são separados. Foram aplicadas apenas V64–V68/V70, sem DDL P3, mutação do pai/schema herdado ou reutilização do recurso P2.

Limites: sem prova de HTTP real Quickstart/Hero, browser/Angular, P3-B, migração integral ou SLA. As barreiras não representam observação pg_locks. O seed denso usa inserção runtime correlacionada e triggers ativos, não mil jornadas HTTP. 8MiB é orçamento de texto de workspace por prefixo comum, não heap nem conjunto total de registros; primeiro item oversized é retornado isoladamente e passa ao verifier.
