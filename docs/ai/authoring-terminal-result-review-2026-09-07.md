# Revisão: coerência do resultado terminal

## Escopo e fonte

Correção local no Config Starter, baseada em `c1247a7db9ccec5f8b34dedeb3ea6a27e79864ce` (`0.1.0-rc.153`). Sem novo campo, endpoint, autorização ou fonte semântica. Classificação de aderência: `ja-suportado-mal-nomeado-ou-mal-materializado`.

- `AgenticAuthoringTurnEngine` preserva uma lista explícita de sugestões, inclusive vazia. O fallback preenche somente resultados sem lista.
- `AgenticAuthoringPreviewMessageSynthesizerService` considera a composição declarada e os slots de widgets suportados pelo compilador, não IDs arbitrários encontrados em diagnósticos ou dados. Isso impede que CRUD ou um tipo de gráfico rejeitado contamine a descrição da prévia.
- A mensagem distingue consultas de prévia de escrita de registros e persistência de configuração.

## Revisão do consumidor

O `AgenticAuthoringTurnClientService` Angular usa `toQuickReplies(result.quickReplies) ?? toQuickReplies(intentResolution.quickReplies)`. A conversão devolve `[]` para uma lista vazia e `null` para ausência/não lista. Portanto, já preserva a supressão terminal; não requer reconstrução local de governança nem novo contrato.

Os endpoints, DTOs, envelope SSE, headers, ETag e `canApply` permanecem inalterados. Não há regeneração de OpenAPI, corpus HTTP, manifests ou contratos Angular. A documentação de streaming foi atualizada.

## Validação focal

Execução conjunta no starter:

```sh
mvn -o -q '-Dtest=AgenticAuthoringTurnEngineTest,AgenticAuthoringPreviewMessageSynthesizerServiceTest,AgenticAuthoringTurnStreamServiceTest,AiTurnEventServiceTest,AgenticAuthoringControllerTest,AgenticAuthoringTurnStreamHttpSseIntegrationTest' test
```

312 testes, zero falhas/erros/skips: motor 206; mensagens 25; serviço de stream 40; eventos 16; controller 16; HTTP/SSE 9. `git diff --check` aprovado.

A suite HTTP/SSE carregava um controller de paletas que exigia serviços fora de seu escopo. O teste agora isola esse controller como já fazia com outros controllers do Policy Studio. Não se alterou wiring de produção. A primeira execução conjunta também encontrou proibição de bind no sandbox; a repetição com permissão para sockets locais passou integralmente.

Downstream: Quickstart `bdb702ba70bffcdd7b8e3c81eb5ce54e48735ef2` (`2.0.0-rc.54`), em checkout limpo e reactor Maven com este starter, executou `AgenticAuthoringStreamIsolatedIntegrationTest`: um teste aprovado. O classpath do relatório Surefire confirma as classes do starter deste worktree. O teste usa HTTP local e verifica start/probe, identidade e token assinado; o serviço de turno é mockado. O Quickstart não recebeu alterações e não foi sobrescrito um artefato publicado no cache Maven.

## Limites de conclusão

Esta evidência permite revisar e integrar o patch; não é gate de release, inferência real, browser completo ou deploy. Nenhuma tag/publicação é parte desta mudança. A recusa original do laboratório, cujo evento não foi recuperado, não tem causa atribuída por estes testes. Após release/deploy autorizado, repetir a prova pública em sessão nova e reenvio antes de atualizar o material audiovisual do artigo.
