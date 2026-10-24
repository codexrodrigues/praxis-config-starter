# P3-C — fechamento do artefato público

A fonte Configmain/deebc691 tem árvore idêntica à candidata c6f7c786. As provas
P0/P2/P3-A/P3-B e Quickstartb1/b2 são reutilizadas nos limites originais; não são
nova prova de publicação/browser. O delta desde166 também inclui SSE/registry.

A tentativa oficial167 gerou a tag2513a330, com mudança somente de project.version.
O gate executou3674 testes, falhando5 e errando5 em quatro classes com expectativas
anteriores à implementação de grupos/childSources e às restrições de root de página.
Construção do bundle e upload foram SKIPPED. A tag é preservada como não publicada.

Correção exclusivamente de testes: request fechado continua fechado e inclui a
propriedade tipada childSources; P0 comprova projeção/reprodução de página e grupos,
rejeita patch incorreto e KIND desconhecido; composição genérica Form/Table preserva
as mesmas garantias e explicita root UUID único para página/app-shell. Nenhum teste
foi desabilitado; produção, POM, timeout, grants e workflow permanecem iguais.

125 testes focais passaram sem banco, incluindo o decoder adjacente. Revisão
independente de código e fixtures concluída. O recibo sanitizado identifica hashes
na pasta evidence/p3c-release-20261023. A próxima tentativa é168, pelo workflow
oficial após integrar a correção; nenhuma publicação manual ou repetição de upload.

O Quickstart só contará adoção pública após resolver POM/JAR e checksums no Central,
comparar o JAR aninhado e passar a prova HTTP Neon com runtime ordinário e sessões
reais. A primeira página será mínima, sem navegação; cópia/menu/browser são gates
posteriores. Este registro não aprova o PR463 nem um deploy.

Skills runtime-persistence/security-config: existente-adequada para corrigir testes
obsoletos; operational-proof recebe guidance do novo percurso focal do host, na
fonte canônica. Não há contrato novo nem corpus público de payloads a regenerar.
