# MinimalFormPlan provider schema projection

## Contexto

O contrato `minimal-form-plan.v1.schema.json` e a autoridade canonica para o plano intermediario.
Ele usa propriedades opcionais e valores JSON livres que nao pertencem ao subconjunto estrito de
Structured Outputs aceito por todos os provedores. Enviar o contrato diretamente para esse transporte
fazia a validacao local da OpenAI rejeitar `required` antes mesmo de uma chamada HTTP.

## Classificacao de aderencia

- compilacao de schema para o provedor: `suportado-parcialmente`;
- validacao do documento canonico: `ja-suportado-mal-nomeado-ou-mal-materializado`;
- bloqueio de intent invalida antes do plano: `ja-suportado-mal-nomeado-ou-mal-materializado`;
- evidencia do intent no smoke: `ja-suportado-so-ux` operacional.

Nao foi identificado motivo para criar DTO, endpoint ou versao paralela do `MinimalFormPlan`.

## Solucao

`AgenticAuthoringProviderSchemaCompiler` agora tambem compila schemas documentais completos. A
projecao e criada somente em memoria e:

1. fecha objetos com `additionalProperties=false`;
2. exige no transporte todas as propriedades declaradas;
3. torna propriedades canonicas opcionais anulaveis;
4. transporta valores JSON livres como texto JSON compacto;
5. remove palavras-chave que nao fazem parte do subconjunto do provedor.

Depois da resposta, o mesmo compilador remove placeholders nulos opcionais e restaura os valores JSON
antes de `AgenticAuthoringMinimalFormPlanValidator` executar. O schema em resources e sua copia
documental permanecem inalterados e continuam sendo a autoridade.

O endpoint de plano tambem reutiliza o gate semantico existente: quando recebe um
`intentResolution` invalido, inelegivel ou sem candidato selecionado, ele retorna a falha governada sem
invocar o provedor. Requisicoes internas sem intent continuam disponiveis para os fluxos de
clarificacao ja existentes.

## Evidencia operacional

`Invoke-QuickstartAgenticAuthoringPlanHttpE2E.ps1` cria o diretorio de artefatos e grava
`intent-resolution.json` imediatamente apos a resolucao. Antes de pedir o plano, valida:

- `valid=true`;
- `operationKind=create`;
- `artifactKind=form`;
- `changeKind=create_artifact`;
- `selectedCandidate.resourcePath=/api/operations/incidentes`.

Assim, qualquer falha posterior conserva a decisao semantica que levou ao plano.

O smoke HTTP/SSE local tambem usa a rota canonica explicita como grounding posterior a decisao
semantica. A validacao real revelou e fechou duas inconsistencias adicionais:

- pontuacao final da frase nao pode integrar o `resourcePath`; `/api/operations/incidentes.` e
  normalizado para `/api/operations/incidentes`;
- quando nao existe `submitUrl` explicito, o candidato usa o proprio `resourcePath` como destino e
  como `path` de `/schemas/filtered`, nunca o verbo literal `post`.

Antes do stream, o smoke declara `contextHints.agenticApplyTarget` conforme
`praxis-agentic-authoring-apply-target.v1`. Depois do terminal, valida a identidade atestada pelo
backend antes de persistir, ler e remover a configuracao. Uma recusa de aplicacao agora imprime o
motivo governado em vez de encerrar silenciosamente.

## Compatibilidade

- sem alteracao em request, response, controller ou persistencia;
- sem alteracao no schema canonico publicado;
- sem alteracao necessaria em Angular ou landing page;
- o teste externo de shadow usa a mesma projecao transitória do runtime.

## Validacao minima

- compilacao do schema completo e invariancia do contrato canonico;
- round-trip dos valores livres e propriedades opcionais;
- regressao dos planos de edicao de componente;
- rejeicao de intent invalida antes do `AgenticAuthoringPlanService`;
- normalizacao de recurso explicito com pontuacao de frase e materializacao correta de
  `schemaUrl`/`submitUrl`;
- suite focal de controller, compiler, plan e preview;
- smoke HTTP/SSE real com plano, compilacao, preview, apply, persistencia, cleanup, probe, replay e
  cancelamento.
