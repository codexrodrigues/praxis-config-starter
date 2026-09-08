# Investigação local: intenção desconhecida e confirmação do recurso

Data: 2026-09-08. Base: `61cf61543c17436ab47b4fa8af6e75e78e5f463d`.
Branch isolada: `codex/intent-grounding-review-20260908`.
Classificação da investigação inicial: `local-pequena` (testes) e `docs-apenas`.

## Correção autorizada em 8 de setembro

Após a investigação, o usuário autorizou seguir com a correção. Classificação:
`arquitetural`, corrigindo a fronteira de autoridade semântica existente, sem novo
campo, DTO, endpoint ou envelope. Aderência: `ja-suportado-mal-nomeado-ou-mal-materializado`.

Implementação:

- Removidas as promoções de discovery/seleção de projeção para criação e a
  recuperação de falha do provider por candidatos/texto.
- Revalidação da intenção primária depois da reconciliação: resultado vazio ou
  não resolvido mantém `unknown/unknown`, clarificação ou erro de provider, sem
  candidato selecionado. Um fallback legado habilitado não contorna essa barreira.
- O bloqueio não acrescenta indevidamente o aviso de foco rejeitado apenas porque
  o candidato acabou de ser limpo; a rejeição real feita pelo gate anterior mantém
  seu warning. Um retorno vazio após tentativa recebe o código existente de
  intenção não resolvida, não o rótulo incorreto de tentativa ausente.
- Quick replies sintetizadas a partir dos candidatos não se tornam decisões de
  criação. O Turn Engine preserva as clarificações publicadas pelo resolver e
  mantém `canApply=false`.
- Guia `docs/ai/agentic-authoring-streaming.md` atualizado.

As 21 expectativas atingidas na primeira bateria foram auditadas, não ignoradas:
9 casos passaram a exigir bloqueio após falta/falha da interpretação; 6 cenários
de ranking de regras agora recebem uma intenção de authoring explícita mockada;
3 cenários positivos de projeção/tabela passaram a fornecer resolução mockada
real em vez de depender de `Optional.empty()`; 2 consultas preservam sua rota;
1 diagnóstico deixou de confundir ausência de intenção com rejeição do foco.
Todos continuam executáveis. Acrescentadas 8 provas do resolver e 3 variantes do
terminal, mantendo as 3 variantes anteriores de recurso não confirmado.

Prova final conjunta: **552 testes aprovados, zero falhas/erros/skips** — 551 no
Config e 1 no Quickstart `bdb702ba70bffcdd7b8e3c81eb5ce54e48735ef2`, versão
`2.0.0-rc.54`. O reactor Maven offline usou as classes corrigidas do Config
`0.1.0-rc.153`; o `java.class.path` no relatório Surefire do host confirma
`intent-grounding-review-config/target/classes`. Não houve instalação/substituição
de uma coordenada publicada no cache Maven, alteração do POM do host nem deploy.

O teste do host, `AgenticAuthoringStreamIsolatedIntegrationTest`, comprova HTTP local
de start/probe, identidade e token assinado, com serviço de turno mockado. A prova
semântica e de terminal vem dos testes do Config, também com provider simulado.
Isso não equivale ao smoke com LLM real, ao browser em produção ou a `mvn verify`
integral. O gate remoto continua reprovado.

Log final: `/private/tmp/praxis-intent-fix-reactor-final.log`. Reactor temporário:
`/private/tmp/praxis-intent-fix-reactor.L4PjgC/pom.xml`. Testes focais:
`AgenticAuthoringIntentResolverServiceTest`, `AgenticAuthoringLlmIntentResolverServiceTest`,
`AgenticAuthoringCandidateProvenancePolicyTest`, `AgenticAuthoringSemanticDecisionPolicyTest`,
`AgenticAuthoringTurnEngineTest` e o teste do host acima. Uma execução anterior do
Turn Engine foi bloqueada pela sandbox ao abrir sockets; a repetição permitida
com HTTP local passou. Não contar as tentativas anteriores como provas adicionais.

Escopo dos consumidores: Angular foi inspecionado e continua usando a clarificação
e `canApply` do backend; nenhum contrato gerado mudou. Quickstart é somente prova
downstream. Não há atualização de OpenAPI, headers/ETag, corpus HTTP, manifests ou
landing: não houve alteração de forma pública nem implantação. As skills instaladas
usadas já exigem fail-closed e não rotear por palavras; os pacotes especialistas
correspondentes não existem nesta cópia de `codex-skills/`, portanto não foi criada
uma cópia canônica especulativa nem executado sync local. A migração dessas skills
permanece uma lacuna de organização separada desta correção.

As seções seguintes registram a investigação anterior à correção. A referência a
dois testes vermelhos descreve aquele estado histórico, não um resultado final.

## Conclusão e limites

Os códigos `intent-operation-unknown` e `intent-artifact-unknown` não identificam,
sozinhos, uma resposta desconhecida da LLM. O resolver pode substituir uma resposta
resolvida por `unknown` quando não confirma o foco semântico contra o recurso.
Essa transformação foi reproduzida localmente, com o fallback textual desativado.

Foi encontrado também um defeito independente: uma resposta não resolvida pode ser
promovida para `create/table/create_artifact` por uma normalização que continua
sensível a palavras do pedido mesmo com `legacyKeywordFallbackEnabled=false`.
Isso viola a fronteira de resolução semântica: um candidato recuperado não é,
por si só, uma decisão de criar. Dois testes de regressão permanecem vermelhos.

Não foi demonstrado que esse segundo defeito causou o smoke remoto
`34171631443`. A falha remota permaneceu bloqueada; os testes locais expõem uma
promoção no sentido oposto. Não houve execução/persistência de artefato nos testes.
`valid=true` nesta prova é o resultado do resolver, não autorização final de apply.

## Prova determinística

As fixtures foram construídas para separar hipóteses; não são replay do payload
real do provider. O provider é mockado, Maven roda offline e não há chamadas pagas.
As novas provas invocam `resolve`, não apenas helpers privados, e usam o valor
`false` adotado por padrão em `AgenticAuthoringAutoConfiguration`.

| Cenário novo | Resultado observado | Asserção |
|---|---|---|
| LLM resolvida, identidade exata e binding governado | Criação preservada | Passou |
| Mesmo binding, com incerteza residual no foco | Criação preservada | Passou |
| LLM resolvida, foco incerto e evidência sem binding confirmado | `unknown`, aviso de foco não confirmado | Passou |
| Binding de missões, foco canônico em incidentes | `unknown`, aviso de foco não confirmado | Passou |
| LLM não resolvida, orientação exigindo resolução completa e binding confirmado | Criação válida indevida | Falhou |
| Identidade exata, sem incerteza residual, evidência sem binding | Criação preservada pelo gate de identidade | Passou |
| LLM não resolvida, sem orientação/foco semântico, pedido “Crie uma tabela operacional de missões.” | Criação válida indevida | Falhou |
| Mesma fixture anterior, apenas pedido “Preciso de ajuda com este recurso.” | `unknown` preservado | Passou |

A penúltima e a última prova diferem apenas pelo texto do pedido. Ambas têm o
mesmo candidato, o mesmo contexto de discovery e a mesma resposta não resolvida.
Essa comparação isola a influência textual. O contexto de discovery descreve
`artifactKind=table`, mas não contém uma decisão semântica resolvida de criar.

Quatro regressões existentes de reconciliação/foco também passaram. Três testes
do `AgenticAuthoringLlmIntentResolverServiceTest` passaram, verificando:

- Resposta rápida não resolvida de tabela continua para `intent_full`.
- Duas respostas não resolvidas permanecem não resolvidas nesse serviço.
- Decisão rápida sem a surface necessária continua para resolução completa.

Total: **15 testes, 13 aprovados, 2 falhas de asserção, 0 erros, 0 ignorados**.
Não executar release nem integrar esta branch como verde.

Logs locais:

- `/private/tmp/praxis-intent-grounding-review-matrix.log` (12 testes, 2 falhas).
- `/private/tmp/praxis-intent-grounding-review-llm-tests.log` (3 testes aprovados).
- A execução exploratória anterior de 6 testes está em
  `/private/tmp/praxis-intent-grounding-review-tests.log`; não somar esses testes novamente.

Reprodução focal:

```sh
mvn -o -B -Dtest='AgenticAuthoringIntentResolverServiceTest#semanticOnly*' -DfailIfNoTests=true test
```

## Caminhos canônicos identificados

Em `AgenticAuthoringIntentResolverService`:

1. `failClosedForUnconfirmedAiAuthoredResourceSelection` transforma uma resolução
   válida em `unknown` e acrescenta
   `llm-resource-selection-unconfirmed-by-ai-authored-focus`.
2. `hasUnconfirmedAiAuthoredResourceFocus` verifica identidade, incerteza e provas
   de binding. Não remover esse bloqueio para recuperar botões ou passar o smoke.
3. `shouldNormalizeGroundedResourceDiscoveryAuthoringDrift` é chamado sem exigir
   `legacyKeywordFallbackEnabled`; aceita operação/artefato desconhecido.
4. `hasBusinessDataAuthoringSignal` aceita `isBusinessDataAuthoringPrompt`, que usa
   listas de palavras. A normalização promove a tupla para criação mesmo sem
   intenção semântica resolvida. A advertência resultante é
   `llm-resource-discovery-authoring-drift-normalized`.

Em `AgenticAuthoringTurnEngine`, a clarificação de recurso não confirmado omite
quick replies e mantém `canApply=false`. O terminal já transporta
`intentResolution`, incluindo os warnings existentes. O evento
`consultative.grounded-clarification` já possui o booleano
`unconfirmedAiAuthoredResourceFocus`. Há evidência canônica disponível; não é
necessário começar inventando outro contrato de domínio.

## Próximos passos, em ordem

1. Corrigir a promoção de intenção não resolvida no Config, usando os dois testes
   vermelhos como regressões. Não trocar a condição por outra heurística textual;
   uma recuperação deve depender de decisão semântica canônica válida, preservar
   pedido de clarificação/resolução completa e manter checks de recurso independentes.
2. Auditar as demais normalizações que atribuem `create` depois da resposta LLM,
   incluindo seleção de projeções. Testar provedores falhos, pedidos consultivos,
   intenção ambígua e decisões semânticas válidas. Não implementar por reflexo uma
   alteração transversal antes desse inventário.
3. Melhorar a evidência do smoke reutilizando os warnings/códigos e booleanos já
   publicados, em projeção sanitizada com allowlist explícita. Não exportar prompts,
   mensagens, dados de registros nem o `llmDiagnostics.request` completo. Isso deve
   distinguir foco rejeitado de resposta LLM não resolvida antes de nova chamada paga.
4. Só depois da correção local e da prova downstream determinística considerar um
   novo smoke pago deliberadamente autorizado. O gate remoto continua reprovado.

Mapa de impacto para a eventual correção (não implementada nesta investigação):

- Fonte canônica: resolver e políticas semânticas do Config Starter.
- Consumidores: Turn Engine, Quickstart de prova e Page Builder Angular.
- Docs: guidance de authoring; laboratório público só após comportamento comprovado.
- Mínimo: regressões focais, prova determinística do terminal/SSE no Quickstart e
  preservação dos gates de preview/apply. Sem presumir sucesso production-like.
- Contrato: nenhuma lacuna de schema demonstrada; comportamento existente mal
  materializado/normalizado. Não criar endpoint ou DTO novo nesta etapa.
- Risco: fluxos que hoje dependem da promoção implícita podem voltar corretamente
  à clarificação e precisam de continuação governada, não de bypass.

Na etapa inicial de investigação não houve alteração em código de produção,
contrato público, artefato derivado, biblioteca Angular, skill ou implantação.
Os testes novos e este diagnóstico ficaram locais na worktree isolada; nenhum
merge/push foi feito naquela etapa. A correção posterior está descrita no início.
