# Política de chamadas por fase na jornada humana

Classificação: `transversal`. Fonte canônica: Config, no resolver/planejador semântico e no adapter OpenAI.

## Inventário e mapa de impacto

| Necessidade | Aderência | Correção na fonte existente |
| --- | --- | --- |
| Identificar finalidade de cada chamada | `ja-suportado-mal-nomeado-ou-mal-materializado` | Propagar `AGENTIC_AUTHORING` também no pre-intent e nas resoluções de intenção |
| Manter esforço compacto quando a saída cresce | `suportado-parcialmente` | Usar o perfil já existente; capacidade de saída não decide profundidade do raciocínio |
| Diagnosticar timeout sem resposta | `ja-suportado-mal-nomeado-ou-mal-materializado` | Registrar seleção efetiva do adapter no trace antes do envio |
| Cancelar JSON em espera | `suportado-parcialmente` | Registrar o future no mecanismo de abort já usado por streaming |

Plano executado: reproduzir as falhas com HTTP local controlado; corrigir a fonte canônica; testar
provider, roteamento, deadlines e ciclo SSE; fechar o corte por um único gate browser/LLM delimitado.
Não há novo contrato, DTO, endpoint, input, manifest, header ou ETag. Angular consome o mesmo ledger;
API Quickstart prova o starter empacotado. Landing e o corpus HTTP não precisam regeneração, nem há
release npm neste corte. Docs operacionais recebem a política e esta evidência. O perfil também
habilita referências de hosted skills se o host já as configurou explicitamente; não cria referências.

## Comportamento e limites

Pre-intent e todas as fases de resolução agora usam `AiCallConfig.agenticAuthoringBuilder()`.
No adapter, authoring GPT-5 conserva a política compacta já existente independentemente do limite de
saída: `gpt-5-mini` envia `reasoning.effort=low` com 640, 1800 e 4096 tokens máximos. Outros modelos
continuam seguindo sua capacidade/política existente. Não se altera o modelo configurado ou a
precedência de seleção, e chamadas sem perfil preservam o comportamento anterior.

O modelo resolvido pelo adapter chega ao trace antes da chamada: timeout conserva o alias enviado;
uma resposta recebida pode substituí-lo pelo snapshot informado pelo provider. Isso não torna
conhecidos responseId, uso ou custo de uma chamada sem resposta. O future da chamada JSON participa
do abort do stream; cancelamento prévio impede admissão e cancelamento em espera libera o worker.
Isso não comprova que o provider deixou imediatamente de processar ou cobrar uma chamada aceita.

Os limites de fase permanecem 12 s/30 s e o stream 360 s. O contexto semântico/schema não foi
reduzido: remover campos de contrato para reduzir latência prejudicaria decisões canônicas. A revisão
identificou uma omissão de política na chamada full, não uma prova de que seu contexto seja excessivo.
Pre-intent e fast já usavam esforço baixo; portanto este ajuste não garante resolver todos os timeouts
observados no run 34003732255. Nenhuma regra textual de intenção foi introduzida.

A [referência oficial de modelos](https://developers.openai.com/api/docs/models/gpt-5-mini) identifica
o modelo mantido. A política local explicita o esforço em vez de depender do default do provider;
a validação HTTP confere o payload efetivamente enviado.

## Validação

Cinco regressões falharam antes da correção: perfil ausente em duas fases, esforço omitido com saída
4096, alias do modelo perdido no timeout e worker JSON não liberado pelo cancelamento. Depois,
131 testes focais passaram: adapter, resolver, pre-intent, management, router e integração de fallback/cancel.
A segunda bateria passou 96 testes: adapter (29), stream (40), HTTP SSE (9), serviço de stream (17)
e serialização de telemetria (1). São 199 testes distintos entre as duas baterias; 28 testes do adapter
foram reexecutados. Cobrem cancelamento antes de qualquer HTTP, início, terminal, replay e cancelamento
do ciclo de stream. Transporte local controlado, sem credenciais ou chamadas pagas.

O gate real permanece pendente nesta revisão inicial. Será uma jornada canônica Page Builder,
`gpt-5-mini`, no máximo três turnos humanos e zero retries de teste. Não se enfraquece o critério
first-pass. Limite da conta atestado pelo usuário, sem verificação independente ou valor inventado.


## Revisão adicional de interrupção da worker

A revisão da cadeia encontrou dois mecanismos existentes: `AiStreamService` registra contexto/abort;
`AgenticAuthoringTurnStreamService` interrompe sua tarefa. O adapter deve respeitar ambos. Foi
reproduzido um erro: thread previamente interrompida era classificada como erro de stream do provider.
A correção consulta o estado de interrupção antes da admissão JSON/stream e preserva a flag quando
a espera do stream é interrompida. Não cria contexto ou API paralela de cancelamento.

Passaram 92 testes focais (32 adapter, 40 authoring stream, 17 stream service, 3 fallback/cancel),
incluindo três novos casos: JSON e stream em worker previamente interrompida, e interrupção do stream
em espera. São 202 testes distintos no corte total; as baterias se sobrepõem. Essa correção adicional
não está no SHA `e80b93fd2bbd141c811f6ea00d91f212debb1582` do gate `34005676687`, já em execução.
A política de reasoning desse gate é a mesma; a evidência de interrupção adicional é local.

Config #460 foi integrado e seu CI 34005668733 passou. O guidance canônico de jornadas em
`codex-skills/praxis-generative-ui-authoring/references/pilot-scenarios.md` recebeu a distinção entre
orçamento, reasoning, modelo selecionado e uso observado. A referência foi espelhada diretamente na
skill instalada; sync/bootstrap não estão disponíveis neste workspace. A skill instalada de provider
operations não possui fonte correspondente neste checkout de codex-skills; não foi criada uma cópia
paralela como parte desta correção operacional.


## Preservação do cancelamento durante lookup de configuração

A auditoria posterior seguiu a interrupção até `AiProviderManagementService`: o lookup assíncrono
capturava `InterruptedException` como falha genérica, limpava a flag e prosseguia com os defaults.
Dois negativos reproduziram geração admitida indevidamente. A correção interrompe antes do lookup
quando a worker já foi cancelada e, se interrompida durante a espera, cancela o future, restaura a flag
e propaga `CancellationException`. Timeout de lookup continua usando o fallback configurado existente.
Aderência: `suportado-parcialmente`, corrigida na fronteira de provider, sem contrato novo.

137 testes focais passaram (13 management, 32 adapter, 9 router, 3 fallback/cancel, 34 pre-intent,
46 resolver). São 204 testes distintos considerando todas as baterias deste corte. Os negativos
anteriores de HTTP/deadline também foram reexecutados nessa bateria. Config #461 foi integrado em
`6d21f01d5c48d884b666aecd6c6911ce21f02915` e passou CI 34005970368; o complemento de lookup e a
interrupção da worker não estão no SHA do gate pago 34005676687. Nenhum gate pago adicional foi aberto.
