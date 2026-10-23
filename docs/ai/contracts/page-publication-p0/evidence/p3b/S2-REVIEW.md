# Parecer independente S2 — corrupção privada SQL

Revisor `/root/backend_delivery`, diferente do autor do teste/produção; leitura de fontes e brutos, sem Maven ou conexão SQL. Fonte `bfec7fe744cd43f7e94f49dcf74516d780095a98`, árvore `c0939180126a3c7d20f361af914206045ff8df5e`; produção runtime R2 `0eb159862ba9fcd8fbcecb1ad851f6ba30fe5218`.

Conferidos 41 hashes de fonte por `git show`, dois brutos SHA256, comando/seletor, run-start/final e XML de `neon-private-corruption-bfec7fe7`. Resultado: 1 teste, 0 falhas/erros/skips, 164,035 s; sourceUnchanged e exit0. Log confirma PG17.11, role/schema/branch atribuídos, TLS1.3 e peer JDBC. A duração inclui setup.

O teste obtém catálogo, continuação e menu públicos antes de inserir negativos. Corrompe somente o hash do ledger de uma página e o raw hash da captura de outra; seu restante é correlacionado pelos helpers existentes. Com READ_HEAD negado, items/receipt/cursor e menu permanecem iguais e callbacks metadata/structure não observam as páginas privadas. Permitir READ_HEAD da captura USER de Bob mantém Alice fora pelo selector, preservando a projeção sem consumir a captura.

Os contrafactuais admitem o ledger corrompido para Alice e a mesma captura para Bob pelo principal/contexto/selector real da fixture: ambos os endpoints catálogo/URL retornam 503/no-store, sem ETag/receipt/items/document, inclusive com If-None-Match. Outra página USER íntegra de Bob é controle positivo. Depois, Alice reencontra exatamente as duas páginas públicas; heads/eventos são consultados por conexão fresh e ficam iguais ao baseline pós-inserção. Os inserts negativos não são mutações runtime.

Conclusão: **apto no recorte S2**; nenhum bloqueador novo. Prova o gate privado e seus contrafactuais com SQL real. Não prova host HTTP operacional, browser, 1.000 cadeias Published, isolamento atômico de autoridade externa ou execução Neon da árvore reconciliada. As demais rodadas e a integração têm aceite próprio. O README default incorreto foi corrigido no fechamento documental. Skills: a atualização existente já revisada pela coordenação cobre esse procedimento; integração/sync permanecem estado separado.
