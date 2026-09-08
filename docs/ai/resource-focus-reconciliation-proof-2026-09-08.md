# Reconciliação de foco: reprodução e correção canônica

Data: 8 de setembro de 2026. Base: `7c9abb6c799a9603ad86c1090902eeae5dda533b`.
Escopo `local-pequena`: correção interna do resolver, testes e documentação.
Nenhum endpoint, DTO público, evento, permissão ou formato persistido foi alterado.

## Correção implementada após a reprodução

A preferência entre duplicatas conserva o candidato operacional completo quando
um candidato de catálogo compatível não tem verificação operacional. Reutiliza
`hasVerifiedOperationalBindingEvidence` e a política de proveniência existentes.
Não eleva o catálogo a autorização nem faz união de marcadores entre fontes.

Compatibilidade exige caminho, schema, operação, endpoint e método equivalentes
(somente caixa de método HTTP é indiferente), além de tenant/ambiente/release
compatíveis nos bundles. Fontes inconsistentes, evidência fraca, identidade
estrutural incompleta, escopos divergentes ou misturados não recebem a preferência.
A união já existente entre candidatos da mesma fonte também passa a exigir
compatibilidade estrutural e contextual.

O produtor auditado, `SearchApiResourcesToolExecutor.verifiedBindingCandidate`,
já publica os três marcadores de verificação e bundle com binding, schema e
capabilities, vinculados ao tenant/ambiente e `sourceRelease`. O enhancer do
catálogo já publica `domain_catalog` e sua release. Não se criou contrato paralelo.

Uma fixture de ranking existente esperava unir evidência sem escopo com evidência
identificada. Ela agora declara o mesmo bundle nas duas descobertas; controles
negativos independentes proíbem união com escopo ausente ou divergente. As
expectativas de aceitação/rejeição da intenção primária não foram relaxadas.

### Validação da correção

**351 testes aprovados**, zero falhas, erros e skips em reactor Maven offline:
273 resolver, 50 tools, 5 proveniência, 22 reconciliação e 1 Quickstart HTTP.
Finalizado em 8 de setembro de 2026 às 09:27:26, UTC−03:00.

O Surefire do Quickstart confirma no classpath o `target/classes` deste Config
corrigido, não um JAR Maven antigo. O teste de host cobre start/probe e token,
com serviço de turno mockado; não prova inferência, preview/apply ou navegador.
O reactor não instala nem sobrescreve coordenadas publicadas no Maven local.

A primeira execução restrita teve três erros de socket nas tools e uma fixture
de escopo incompatível. Depois do ajuste da fixture, a execução com permissão
para HTTP local passou integralmente. Nenhuma chamada paga, tag, release ou deploy.

Artefatos: documentação interna de authoring atualizada; OpenAPI, tipos Angular,
corpus HTTP, receitas e landing não precisam de regeneração, pois nenhum formato
público mudou. O laboratório implantado permanece sem novo gate aprovado.
A skill especializada instalada não tem contraparte exata no `codex-skills/`
deste checkout; o aprendizado fica registrado aqui, sem criar guidance paralelo.

As seções seguintes preservam a evidência histórica anterior à correção.

## Achado

No resolver, um candidato com `domain-binding` é aceito pelo gate de foco quando
sua identidade corresponde ao foco declarado. Ao acrescentar um segundo candidato
do catálogo para o mesmo caminho, operação, schema e endpoint, a deduplicação pode
eliminar a evidência que sustentava essa aceitação. O resultado passa a ser rejeitado.
O comportamento independe da ordem de entrada nas fixtures.

Mecanismo observado em `AgenticAuthoringIntentResolverService`:

1. `evidenceStrength` atribui 3 a `domain-catalog-grounding` e 2 ao binding da fixture.
2. `deduplicateCandidates` agrupa por `resourcePath` e escolhe o candidato mais forte.
3. `mergeCandidateEvidence` não combina evidências de fontes de recuperação diferentes.
4. Resta o candidato documental sem `llm-resource-focus` ou `domain-binding`.
5. Havendo incerteza explícita no foco, a política de confirmação rejeita o resultado.

Isso caracteriza perda de confirmação já aceita pela política atual. Não prova
que o binding representava autorização real: as fixtures são construídas e os
marcadores de verificação não foram obtidos de um backend em execução.

## Limite em relação ao smoke remoto

O run `34186469223` apresentou rejeição de foco com `llmResolved=true` no terminal.
Nas fixtures públicas deste estudo, a rejeição ocorre no gate antecipado, que
converte esse booleano para `false`. Portanto, não é uma reprodução completa da
assinatura remota, nem comprovação de sua causa raiz. O artifact sanitizado não
permite reconstruir os candidatos reais nem suas identidades e proveniências.

Na primeira execução, duas expectativas de `llmResolved=true` falharam. Foram
corrigidas para documentar o gate antecipado efetivamente exercitado; não houve
mudança no código de produto para fazer os testes passarem.

## Controles da reprodução

`AgenticAuthoringResourceFocusReconciliationTest` cobre:

- catálogo sem confirmação, catálogo com foco confirmado e binding aceito;
- candidato ausente e identidade divergente, que permanecem bloqueados;
- duplicatas da mesma fonte com preservação de foco, em ambas as ordens;
- duplicatas de fontes diferentes com perda do binding, em ambas as ordens;
- evidência lexical fraca que não pode contaminar a fonte governada;
- caminhos diferentes que não compartilham confirmação;
- resolver público: binding isolado aceito, catálogo isolado bloqueado e
  binding acrescido do catálogo também bloqueado.

São testes de caracterização do comportamento atual, não prova de correção.
Modelo mockado; nenhum provedor, embedding, workflow pago ou deploy foi acionado.

Validação final: **282 testes aprovados**, zero falhas, erros ou skips — 273 da
suite existente do resolver e 9 desta reprodução. Comando Maven offline:

```sh
mvn -o -B -Dtest=AgenticAuthoringResourceFocusReconciliationTest,AgenticAuthoringIntentResolverServiceTest test
```

Build concluído em 8 de setembro de 2026 às 01:41:25, UTC−03:00. Não foram
executados integração HTTP, navegador, inferência real nem suites integrais:
o escopo focal é a política de reconciliação do resolver.

## Inventário antes de propor correção

Classificação da necessidade: `suportado-parcialmente`.

- Já existem candidatos com operação, schema, endpoint, método e evidências.
- Já existe `AgenticAuthoringEvidenceBundle`, com fonte e referências, tenant,
  ambiente e release por evidência. As fixtures desta etapa não exercitam esse bundle.
- Já existe uma política contra mistura de proveniência fraca e governada.
- A deduplicação usa somente o caminho como chave; uma correção não pode presumir
  que compartilhar caminho implica compartilhar operação, contexto ou autoridade.
- A correspondência de identidade ainda usa convenção de caminho derivada de
  chave pontuada. É uma auditoria pendente, não causa remota demonstrada.

Não há evidência suficiente para declarar `lacuna-real-de-contrato` ou criar DTO,
endpoint ou nova camada de confirmação.

## Plano que orientou a correção

Auditar os produtores de candidatos e seus bundles para definir quais evidências
podem conservar uma confirmação entre candidatos de identidade e contexto
compatíveis. Não unir indiscriminadamente listas de marcadores nem elevar catálogo
documental a autorização operacional. Preservar a rejeição de intenção não resolvida,
identidade divergente, recurso ausente e evidência lexical fraca.

Mapa de impacto preliminar:

- Dono: resolver e política de proveniência do Config Starter.
- Consumidores: authoring/Page Builder e demais consumidores do mesmo resolver.
- Docs: registrar semântica de reconciliação se o comportamento for alterado.
- Playground: precisa de validação posterior; estes testes não aprovam a vitrine.
- Validação mínima: regressão positiva para confirmação conservada, negativos de
  origem/identidade/operação/contexto divergentes e suite focal do resolver.
- Contrato público: nenhum alterado nesta etapa; reavaliar se a auditoria provar
  ausência de informação canônica necessária.

Nenhum artefato derivado de runtime, corpus HTTP ou documentação pública exige
sincronização nesta etapa exclusivamente de teste e diagnóstico. Uma nova execução
paga não é recomendada antes de fechar a correção e suas provas locais.
