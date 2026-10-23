# Evidência portátil P3-B #512

Este export conserva 17 rodadas atribuídas, logs focais anteriores, preflight/TLS/privilégios e pareceres independentes. Não agrega resultados sobrepostos numa suite final. [Manifest](manifest.json), [validação](../../P3B-VALIDATION.md) e [implementação](../../P3B-IMPLEMENTATION.md) definem os limites.

Cada run mantém commit/árvore/comando, hashes de fontes e brutos e XML/log sanitizados. As fontes são recuperáveis com `git show <sourceCommit>:<path>`; seus corpos não foram duplicados. V64–68/V70 e seus hashes estão em `neon-preparation.json`; o preparo usa somente schema/role atribuídos. Não execute runners ou prepare sem nova atribuição exclusiva e credenciais privadas. `${CONFIG_SOURCE}`, `${MAVEN_HOME}`, `${P3B_M2}`, `${PRIVATE_ASSIGNMENT}` e `${USER_HOME}` substituem caminhos da máquina original; os runners são material histórico e exigem configuração explícita antes de reuso.

`originalSha256` identifica o bruto externo; `exportedSha256` identifica o arquivo versionado depois da sanitização. XML properties foram removidas; caminhos privados foram substituídos e valores conhecidos de credenciais foram escaneados inclusive no manifest serializado. Não tratar hashes originais como hashes dos arquivos exportados. Os brutos originais permanecem no recurso temporário do ensaio e não são necessários para ler os resultados sanitizados.

`dependencies-at-export.json` registra 208 JARs identificados pelo classpath XML original, com hashes coletados no fechamento. Essa coleta não certifica os bytes históricos dos JARs durante cada ensaio. POM e fontes históricos permanecem fixados por Git. O pacote privado final terá source archive, POM/JAR/META-INF e dependências próprios, fora deste export e sem instalar sob a coordenada pública rc.166.

`run-neon.py`, `run-focal.py`, `run-unit-classes.py` e `prepare.py` são os runners retidos. O manifest registra seus hashes atuais e não substitui hashes históricos diferentes nas rodadas. Falhas iniciais de fixture e diagnóstico D129 indisponível foram conservados para não fabricar uma campanha integralmente verde.

`POST-R2-REVIEW-MATRIX.md` e `CONSUMER-GUIDANCE-REVIEW.md` preservam pareceres emitidos antes do fechamento e podem conter pendências históricas. Resultados posteriores estão em `P3B-VALIDATION.md` e `S2-REVIEW.md`; a correção README do parecer de consumidores foi aplicada. Nenhum parecer isolado concede aprovação de release, deploy ou P3-C/P4.
